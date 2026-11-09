package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.crypto.SteamRsa
import cc.ptoe.steam.enhanced.core.Jwt
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.proto.ProtoField
import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import kotlin.io.encoding.Base64

/**
 * Steam guard types that may gate an auth session.
 *
 * Values follow `EAuthSessionGuardType`: 1 none, 2 email code, 3 device code,
 * 4 device confirmation, 5 email confirmation.
 */
enum class GuardType(val wireValue: Int) {
    NONE(1),
    EMAIL_CODE(2),
    DEVICE_CODE(3),
    DEVICE_CONFIRMATION(4),
    EMAIL_CONFIRMATION(5),
    UNKNOWN(0);

    companion object {
        fun fromWire(value: Int): GuardType =
            entries.firstOrNull { it.wireValue == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Result of a poll: either still pending, or tokens are ready. */
data class PollResult(
    val complete: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accountName: String? = null,
    /** Set when Steam refreshed the QR challenge (direction-A logins). */
    val newChallengeUrl: String? = null,
)

/**
 * Drives Steam's modern `IAuthenticationService` login flow over plain HTTPS
 * (no SteamKit/CM connection required).
 *
 * Ported from AVA's `core/protocol/steam_auth_session.dart`. Supports password
 * login (including direction-A QR), submitting a Steam Guard code, then polling
 * for the resulting JWT tokens.
 *
 * The session is a mutable object because begin/poll round-trips carry
 * `client_id` and `request_id` forward.
 */
class SteamAuthSession(
    private val api: SteamApiClient,
    deviceName: String = "Android",
) {
    private val deviceName = deviceName.trim().ifBlank { "Android" }

    var clientId: Long = 0L
        private set
    var steamId: Long = 0L
        private set
    var requestId: ByteArray = ByteArray(0)
        private set
    var allowedConfirmations: List<GuardType> = emptyList()
        private set
    var qrChallengeUrl: String? = null
        private set
    private var weakToken: String? = null

    val canRecoverAuthenticator: Boolean
        get() = steamId > 0L && !weakToken.isNullOrBlank() &&
            allowedConfirmations.any { it == GuardType.DEVICE_CODE || it == GuardType.DEVICE_CONFIRMATION }

    /** The weak token authorizes only the recovery challenge, not a saved login. */
    fun newAuthenticatorRecovery(): AuthenticatorRecovery {
        check(canRecoverAuthenticator) { "Steam did not offer authenticator recovery for this sign-in." }
        return AuthenticatorRecovery(api, steamId, checkNotNull(weakToken))
    }

    val acceptsApproval: Boolean
        get() = allowedConfirmations.any {
            it == GuardType.DEVICE_CONFIRMATION || it == GuardType.EMAIL_CONFIRMATION
        }

    /** EAuthTokenPlatformType.MobileApp — makes logins look like the phone app. */
    private val platformMobileApp = 3

    /**
     * Begins a credentials (username/password) auth session.
     *
     * Returns the guard types Steam will accept — the caller must submit one of
     * them before [poll] can complete.
     */
    suspend fun beginWithCredentials(username: String, password: String) {
        reset()
        // 1. RSA public key for the account. Per-session, so it cannot be cached.
        val rsaReq = ProtoWriter().writeString(1, username)
        val rsaResp = api.callProtobuf(
            IFACE,
            "GetPasswordRSAPublicKey",
            request = rsaReq,
            useGet = true, // bConstMethod: POST is rejected with HTTP 405
        ).parse()
        val modulusHex = rsaResp[1]?.asString ?: error("no rsa modulus")
        val exponentHex = rsaResp[2]?.asString ?: error("no rsa exponent")
        // Dart's int in the upstream implementation is 64-bit. Kotlin's
        // ProtoField.asInt truncates this uint64 and breaks RSA key matching.
        val timestamp = rsaResp[3]?.takeIf { it.wireType == 0 }?.varint
            ?: error("Steam did not return an RSA key timestamp. Start sign-in again.")

        val encryptedPassword = SteamRsa.encryptPassword(password, modulusHex, exponentHex)

        // 2. Begin the session.
        val req = ProtoWriter()
            .writeString(1, deviceName)
            .writeString(2, username)
            .writeString(3, encryptedPassword)
            .writeUint64(4, timestamp)
            .writeBool(5, false)
            .writeVarint(6, platformMobileApp)
            .writeVarint(7, 1) // persistence: persistent
            .writeMessage(9, deviceDetails())
            .writeString(8, "Mobile")

        val fields = api.callProtobuf(
            IFACE,
            "BeginAuthSessionViaCredentials",
            request = req,
        ).parseAll()
        consumeBeginResponse(fields, isQr = false)
    }

    /**
     * Begins a QR (direction A) auth session; returns the challenge URL to render
     * as a QR code.
     */
    suspend fun beginWithQr(): String {
        reset()
        val req = ProtoWriter()
            .writeString(1, deviceName)
            .writeVarint(2, platformMobileApp)
            .writeMessage(3, deviceDetails())
            .writeString(4, "Mobile")

        val fields = api.callProtobuf(
            IFACE,
            "BeginAuthSessionViaQR",
            request = req,
        ).parseAll()
        consumeBeginResponse(fields, isQr = true)
        return qrChallengeUrl.orEmpty()
    }

    /**
     * Parses a BeginAuthSession response.
     *
     * Credentials: 1=client_id, 2=request_id(bytes), 4=allowed_confirmations[],
     * 5=steamid, 6=weak_token (recovery only).
     * QR:          1=client_id, 2=challenge_url(string), 3=request_id(bytes),
     * 5=allowed_confirmations[], 6=version.
     */
    private fun consumeBeginResponse(fields: List<ProtoField>, isQr: Boolean) {
        reset()
        val confs = mutableListOf<GuardType>()
        val confField = if (isQr) 5 else 4
        for (f in fields) {
            when {
                f.number == 6 && !isQr && f.wireType == 2 -> weakToken = f.asString.takeIf(String::isNotBlank)
                f.number == 1 -> clientId = f.varint ?: 0L
                f.number == 2 && isQr -> qrChallengeUrl = f.asString
                f.number == 2 -> requestId = f.bytes ?: ByteArray(0)
                f.number == 3 && isQr -> requestId = f.bytes ?: ByteArray(0)
                f.number == confField && f.wireType == 2 && f.bytes != null -> {
                    // allowed_confirmations (repeated message): inner field 1 = type.
                    val inner = ProtoReader(f.bytes).parse()
                    confs.add(GuardType.fromWire(inner[1]?.asInt ?: 0))
                }

                f.number == 5 && !isQr && f.wireType == 0 -> steamId = f.varint ?: 0L
            }
        }
        check(clientId != 0L && requestId.isNotEmpty()) {
            "Steam returned an incomplete sign-in session. Start sign-in again."
        }
        if (isQr) check(!qrChallengeUrl.isNullOrBlank()) {
            "Steam did not return a sign-in QR code. Start sign-in again."
        }
        allowedConfirmations = confs
        dlog(
            "begin(${if (isQr) "qr" else "creds"}): clientId=$clientId " +
                "requestId=${requestId.size}B steamId=$steamId " +
                "confs=${allowedConfirmations.joinToString(",") { it.name }}",
        )
    }

    private fun reset() {
        clientId = 0L
        steamId = 0L
        requestId = ByteArray(0)
        qrChallengeUrl = null
        weakToken = null
        allowedConfirmations = emptyList()
    }

    /**
     * Submits a Steam Guard code (email or device code).
     *
     * Note `steamid` is **fixed64** here, not a varint — sending it as a varint
     * makes Steam reject the call.
     */
    suspend fun submitSteamGuardCode(code: String, type: GuardType) {
        val codeType = if (type == GuardType.EMAIL_CODE) 2 else 3
        val req = ProtoWriter()
            .writeUint64(1, clientId)
            .writeFixed64(2, steamId)
            .writeString(3, code)
            .writeVarint(4, codeType)
        try {
            api.callProtobuf(IFACE, "UpdateAuthSessionWithSteamGuardCode", request = req)
        } catch (e: SteamApiException) {
            // DuplicateRequest (29): the code was already accepted for this session
            // (e.g. the same 30s TOTP was submitted moments ago). That is not a
            // failure — proceed to polling. Genuine errors (mismatched/expired code)
            // still propagate.
            if (e.eresult != EResult.DUPLICATE_REQUEST) throw e
            dlog("guard code already accepted (DuplicateRequest) — proceed to poll")
        }
    }

    /** Polls once for completion. */
    suspend fun poll(): PollResult {
        val req = ProtoWriter()
            .writeUint64(1, clientId)
            .writeBytes(2, requestId)
        val fields = api.callProtobuf(IFACE, "PollAuthSessionStatus", request = req).parse()

        val newClientId = fields[1]?.varint ?: 0L
        if (newClientId != 0L) clientId = newClientId
        val accessToken = fields[4]?.asString
        val refreshToken = fields[3]?.asString
        val newChallenge = fields[2]?.asString
        if (!newChallenge.isNullOrEmpty()) qrChallengeUrl = newChallenge

        val complete = !refreshToken.isNullOrEmpty()
        if (complete) weakToken = null
        return PollResult(
            complete = complete,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountName = fields[6]?.asString,
            newChallengeUrl = newChallenge,
        )
    }

    /**
     * Builds a [SessionData] once polling has produced tokens.
     *
     * For QR logins the steamid is not in the begin/poll messages — it is the
     * `sub` claim of the returned JWT, so fall back to decoding it from the token.
     */
    fun toSessionData(result: PollResult): SessionData {
        var sid = steamId
        if (sid == 0L) {
            sid = steamIdFromJwt(result.refreshToken)
                ?: steamIdFromJwt(result.accessToken)
                ?: 0L
        }
        return SessionData(
            steamId = sid,
            accessToken = result.accessToken,
            refreshToken = result.refreshToken,
        )
    }

    /**
     * `device_details`: matches the Steam mobile app (os_type AndroidUnknown,
     * gaming_device_type 528) so logins look like the official app.
     */
    private fun deviceDetails(): ProtoWriter = ProtoWriter()
        .writeString(1, deviceName) // device_friendly_name
        .writeVarint(2, platformMobileApp) // platform_type
        .writeVarint(3, -500) // os_type = EOSType.AndroidUnknown
        .writeVarint(4, 528) // gaming_device_type

    companion object {
        private const val IFACE = "IAuthenticationService"

        /** Extracts the steamid (`sub`) from a Steam JWT access/refresh token. */
        fun steamIdFromJwt(jwt: String?): Long? = Jwt.steamId(Jwt.decodePayload(jwt))

        /** Base64 helper kept for callers that log raw proto payloads in tests. */
        internal fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    }
}
