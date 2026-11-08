package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.DeviceSession
import cc.ptoe.steam.enhanced.core.model.DeviceSessionList
import cc.ptoe.steam.enhanced.core.model.DeviceUsage
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.requireAccessToken
import java.math.BigInteger
import kotlin.io.encoding.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Steam device / session management (`IAuthenticationService`).
 *
 * Ported from AVA's `core/protocol/sessions_client.dart`. Field numbers follow
 * SteamDatabase/Protobufs `steam/steammessages_auth.steamclient.proto`.
 *
 * [enumerate] lists the account's logged-in devices; [revoke] signs a device out
 * remotely.
 *
 * The revoke `signature` scheme was reverse-engineered from the official Steam
 * app: HMAC-SHA256 over the token_id's **decimal ASCII**, keyed with the shared
 * secret. This is deliberately NOT the little-endian layout the QR-approve
 * signature uses — mixing the two makes every revoke fail with a bare eresult.
 */
class SessionsClient(private val api: SteamApiClient) {

    /**
     * Lists every refresh token (logged-in device / browser session) for the
     * account, newest activity first, and flags which one is *this* device.
     */
    suspend fun enumerate(account: SteamGuardAccount): DeviceSessionList {
        val token = requireAccessToken(account)
        // include_revoked defaults to false — an empty body is the "active only"
        // request. EnumerateTokens is NOT a bConstMethod (unlike the neighbouring
        // GetAuthSessionsForAccount), so it MUST be POST: sending it as GET is the
        // wrong call shape.
        val reader = api.callProtobuf(
            IFACE,
            "EnumerateTokens",
            ProtoWriter(),
            accessToken = token,
        )
        val devices = mutableListOf<DeviceSession>()
        var requestingTokenId = ""
        for (f in reader.parseAll()) {
            when (f.number) {
                1 -> f.bytes?.let { devices.add(device(it)) }
                2 -> requestingTokenId = f.asUInt64String
            }
        }
        // Most-recently-updated first; a stable, user-meaningful order.
        devices.sortByDescending { it.timeUpdated }
        dlog(
            "sessions enumerate: ${devices.size} device(s), " +
                "current=${requestingTokenId.ifEmpty { "?" }}",
        )
        return DeviceSessionList(devices = devices, requestingTokenId = requestingTokenId)
    }

    /**
     * Remotely signs [device] out of the account (`RevokeRefreshToken`).
     *
     * [permanent] true → `k_EAuthTokenRevokePermanent` (1, deauthorize); false →
     * `k_EAuthTokenRevokeLogout` (0). Steam answers a success with an empty body
     * (eresult OK); [SteamApiClient.callProtobuf] throws on any non-OK eresult, so
     * returning normally means it took — no `success` bool is read.
     *
     * The caller must not pass the current device ([DeviceSessionList.isCurrent]) —
     * that would sign the app itself out; the UI hides revoke for it.
     */
    suspend fun revoke(
        account: SteamGuardAccount,
        device: DeviceSession,
        permanent: Boolean = true,
    ) {
        val token = requireAccessToken(account)
        require(device.tokenId.isNotEmpty()) { "device has no token_id" }
        val signature = revokeSignature(account.sharedSecret, device.tokenId)
        val req = ProtoWriter()
            .writeFixed64(1, decimalToSignedLong(device.tokenId))
            .writeFixed64(2, account.steamId)
            .writeVarint(3, if (permanent) 1 else 0)
            .writeBytes(4, signature)
        api.callProtobuf(
            IFACE,
            "RevokeRefreshToken",
            req,
            accessToken = token,
        )
        dlog(
            "sessions revoke: token=${device.tokenId} " +
                "action=${if (permanent) "permanent" else "logout"} OK",
        )
    }

    private fun device(bytes: ByteArray): DeviceSession {
        var tokenId = ""
        var description = ""
        var timeUpdated = 0L
        var platformType = 0
        var loggedIn = false
        var lastSeen: DeviceUsage? = null
        for (f in ProtoReader(bytes).parseAll()) {
            when (f.number) {
                1 -> tokenId = f.asUInt64String // token_id (fixed64)
                2 -> description = f.asString // token_description
                3 -> timeUpdated = f.varint ?: 0L // time_updated
                4 -> platformType = f.asInt // platform_type (enum)
                5 -> loggedIn = f.asBool // logged_in
                10 -> f.bytes?.let { lastSeen = usage(it) } // last_seen
            }
        }
        return DeviceSession(
            tokenId = tokenId,
            description = description,
            timeUpdated = timeUpdated,
            platformType = platformType,
            loggedIn = loggedIn,
            lastSeen = lastSeen,
        )
    }

    private fun usage(bytes: ByteArray): DeviceUsage {
        var time = 0L
        var country = ""
        var state = ""
        var city = ""
        for (f in ProtoReader(bytes).parseAll()) {
            when (f.number) {
                1 -> time = f.varint ?: 0L
                4 -> country = f.asString
                5 -> state = f.asString
                6 -> city = f.asString
            }
        }
        return DeviceUsage(time = time, country = country, state = state, city = city)
    }

    companion object {
        private const val IFACE = "IAuthenticationService"

        /**
         * The `signature` for [revoke]: HMAC-SHA256 keyed with the base64-decoded
         * [sharedSecretB64], over the ASCII of [tokenIdDecimal] (the token_id
         * rendered in base 10).
         *
         * Reverse-engineered from Steam Android 3.10.9 — deliberately NOT the
         * little-endian integer layout the QR-approve signature uses. Returns the
         * raw 32 MAC bytes.
         */
        fun revokeSignature(sharedSecretB64: String?, tokenIdDecimal: String): ByteArray {
            val key = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(sharedSecretB64.orEmpty().trim())
            val message = tokenIdDecimal.toByteArray(Charsets.US_ASCII)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }

        /**
         * token_id is stored as an unsigned decimal string; to write it as a wire-1
         * fixed64 we need the Long with the same 64 bits.
         *
         * `BigInteger.toLong()` would clamp values above 2^63, so reinterpret the
         * low 64 bits as signed first — [ProtoWriter.writeFixed64] re-widens
         * negatives back to the original unsigned bytes.
         */
        fun decimalToSignedLong(decimal: String): Long = BigInteger(decimal).toLong()
    }
}
