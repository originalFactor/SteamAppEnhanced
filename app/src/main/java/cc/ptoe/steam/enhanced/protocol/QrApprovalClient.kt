package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.requireAccessToken
import java.math.BigInteger
import kotlin.io.encoding.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Parsed components of a Steam login QR challenge URL, e.g.
 * `https://s.team/q/<version>/<client_id>`.
 */
data class QrChallenge(val version: Int, val clientId: Long) {
    companion object {
        /**
         * Only Steam's own hosts issue login QR codes — reject anything else so a
         * crafted QR (e.g. `https://evil.example/q/1/123`) cannot be treated as a
         * legitimate challenge to approve.
         */
        private val ALLOWED_HOSTS = setOf(
            "s.team",
            "steamcommunity.com",
            "www.steamcommunity.com",
        )

        /** Parses the challenge URL embedded in a login QR code. */
        fun tryParse(raw: String): QrChallenge? {
            val input = raw.trim()
            // java.net.URI throws a checked URISyntaxException on anything that is
            // not a URI at all — a scanned code is attacker-controlled input, so a
            // malformed string must return null, never propagate.
            val uri = runCatching { java.net.URI(input) }.getOrNull() ?: return null
            if (uri.scheme != "https") return null
            if (uri.host?.lowercase() !in ALLOWED_HOSTS) return null
            // Path looks like /q/<version>/<client_id> (host s.team or steamcommunity).
            val segs = uri.path?.trim('/')?.split('/') ?: return null
            val qIdx = segs.indexOf("q")
            if (qIdx >= 0 && segs.size >= qIdx + 3) {
                val version = segs[qIdx + 1].toIntOrNull() ?: return null
                val clientId = parseUint64(segs[qIdx + 2]) ?: return null
                return QrChallenge(version, clientId)
            }
            return null
        }

        /**
         * Steam client ids are random uint64s, so about half of them exceed the
         * signed 2^63-1 ceiling of `toLongOrNull` — which would silently turn half
         * of all real login QR codes into "not a Steam QR". Parse through BigInteger
         * and keep the two's-complement (possibly negative) Long; the wire layer
         * (varint / HMAC little-endian) treats longs as raw 64-bit patterns.
         */
        fun parseUint64(s: String): Long? {
            val big = s.toBigIntegerOrNull() ?: return null
            if (big.signum() < 0 || big.bitLength() > 64) return null
            return big.toLong()
        }
    }
}

/**
 * Details of a pending login session (`GetAuthSessionInfo`) shown to the user
 * before they approve or deny it.
 */
data class AuthSessionInfo(
    val clientId: Long,
    val version: Int,
    val ip: String,
    val city: String,
    val country: String,
    val deviceName: String,
) {
    val location: String
        get() = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ")
}

/**
 * Direction B: this app acts as the approver for a login started elsewhere —
 * either by scanning the login QR, or by polling the account's own pending login
 * sessions (GetAuthSessionsForAccount) so they can be approved like the official
 * app's pop-up, without a push notification.
 *
 * Ported from AVA's `core/protocol/qr_approval_client.dart`.
 */
class QrApprovalClient(private val api: SteamApiClient) {

    /**
     * Lists client ids of pending login sessions awaiting approval for [account].
     *
     * `GetAuthSessionsForAccount` IS a bConstMethod — POST is rejected with HTTP
     * 405, hence `useGet = true`.
     */
    suspend fun pendingLoginClientIds(account: SteamGuardAccount): List<Long> {
        val token = requireAccessToken(account)
        val fields = api.callProtobuf(
            IFACE,
            "GetAuthSessionsForAccount",
            ProtoWriter(),
            accessToken = token,
            useGet = true,
        ).parseAll()
        val ids = mutableListOf<Long>()
        for (f in fields) {
            if (f.number != 1) continue
            if (f.varint != null) {
                ids.add(f.varint) // unpacked repeated uint64
            } else if (f.bytes != null) {
                ids.addAll(f.asPackedVarints()) // packed repeated uint64
            }
        }
        return ids
    }

    /** Fetches details (IP / location / device / version) for a pending login. */
    suspend fun sessionInfo(account: SteamGuardAccount, clientId: Long): AuthSessionInfo {
        val token = requireAccessToken(account)
        val req = ProtoWriter().writeUint64(1, clientId)
        val f = api.callProtobuf(
            IFACE,
            "GetAuthSessionInfo",
            req,
            accessToken = token,
        ).parse()
        return AuthSessionInfo(
            clientId = clientId,
            version = f[8]?.asInt ?: 0,
            ip = f[1]?.asString.orEmpty(),
            city = f[3]?.asString.orEmpty(),
            country = f[5]?.asString.orEmpty(),
            deviceName = f[7]?.asString.orEmpty(),
        )
    }

    /** Approves (or rejects) a scanned login challenge using [account]'s session. */
    suspend fun respond(
        account: SteamGuardAccount,
        challenge: QrChallenge,
        approve: Boolean,
        rememberPassword: Boolean = true,
    ): Boolean = respondToSession(
        account,
        version = challenge.version,
        clientId = challenge.clientId,
        approve = approve,
        rememberPassword = rememberPassword,
    )

    /**
     * Approves/denies a specific auth session (by client id + version).
     *
     * Signs the (version, client_id, steamid) tuple with the account's shared
     * secret (HMAC-SHA256) and calls
     * `UpdateAuthSessionWithMobileConfirmation`.
     *
     * [rememberPassword] is the official app's "remember my password on this
     * device" box: field 6 `persistence`, ESessionPersistence Persistent (1) or
     * Ephemeral (0). The proto defaults the field to Persistent, so it is always
     * written — leaving it out would mean "remember" whatever the box said.
     */
    suspend fun respondToSession(
        account: SteamGuardAccount,
        version: Int,
        clientId: Long,
        approve: Boolean,
        rememberPassword: Boolean = true,
    ): Boolean {
        val accessToken = requireAccessToken(account)
        val signature = signature(
            account,
            version = version,
            clientId = clientId,
            steamId = account.steamId,
        )

        val req = ProtoWriter()
            .writeVarint(1, version)
            .writeUint64(2, clientId)
            .writeFixed64(3, account.steamId)
            .writeBytes(4, signature)
            .writeBool(5, approve)
            .writeVarint(6, if (rememberPassword) 1 else 0)

        val fields = api.callProtobuf(
            IFACE,
            "UpdateAuthSessionWithMobileConfirmation",
            req,
            accessToken = accessToken,
        ).parse()
        dlog("qr approve clientId=$clientId approve=$approve fields=${fields.size}")
        // Empty success response (eresult OK) means accepted.
        return fields.isEmpty() || (fields[1]?.asBool ?: true)
    }

    companion object {
        private const val IFACE = "IAuthenticationService"

        /**
         * HMAC-SHA256 over the little-endian (version | client_id | steamid) tuple,
         * keyed with the account's base64 shared secret.
         *
         * This is the signature Steam expects for
         * UpdateAuthSessionWithMobileConfirmation — and it is **not** the same
         * layout the revoke signature uses (that one signs decimal ASCII).
         */
        fun signature(
            account: SteamGuardAccount,
            version: Int,
            clientId: Long,
            steamId: Long,
        ): ByteArray {
            val key = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(account.sharedSecret.orEmpty().trim())
            val buf = java.io.ByteArrayOutputStream()
            buf.write(le16(version))
            buf.write(le64(clientId))
            buf.write(le64(steamId))
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(buf.toByteArray())
        }

        private fun le16(v: Int): ByteArray =
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        private fun le64(v: Long): ByteArray {
            val out = ByteArray(8)
            var x = v
            for (i in 0 until 8) {
                out[i] = (x and 0xFF).toByte()
                x = x ushr 8
            }
            return out
        }

        /** Parses an unsigned decimal uint64 into a raw-bit-pattern Long. */
        fun signedFromDecimal(decimal: String): Long? =
            BigInteger(decimal).takeIf { it.bitLength() <= 64 }?.toLong()
    }
}
