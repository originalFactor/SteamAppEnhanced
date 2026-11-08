package cc.ptoe.steam.enhanced.core

import kotlin.io.encoding.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Steam Guard time-based code + mobile confirmation signing.
 *
 * Ported from AVA's `core/steam_totp.dart`, which is logic-equivalent to the C#
 * `SteamGuardAccount.GenerateSteamGuardCode` and
 * `GenerateConfirmationHashForTime` (geel9/SteamAuth).
 *
 * Note this is Steam's *custom* alphabet, not RFC 6238's decimal truncation —
 * the two produce completely different codes for the same secret.
 */
object SteamTotp {
    /** Steam's custom code alphabet (26 chars). */
    private const val CODE_CHARS = "23456789BCDFGHJKMNPQRTVWXY"

    private const val CRYPTO_ALGORITHM = "HmacSHA1"

    private val base64Decoder: Base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val base64Encoder: Base64 = Base64

    private val sharedSecretRegex = Regex("^[23456789BCDFGHJKMNPQRTVWXY]{5}$")

    /**
     * Generates the 5-character Steam Guard login code for [sharedSecret]
     * (base64) at the given Steam server [time] (unix seconds).
     *
     * [time] must be Steam's clock (see `SteamTime`), not the device's: a wrong
     * offset silently produces a valid-looking code Steam rejects.
     */
    fun generateAuthCode(sharedSecret: String, time: Long): String {
        val key = base64Decoder.decode(sharedSecret.trim())

        // 8-byte big-endian counter = time / 30.
        var counter = time / 30
        val timeBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            timeBytes[i] = (counter and 0xFF).toByte()
            counter = counter ushr 8
        }

        val hmac = hmacSha1(key, timeBytes)
        val start = hmac[19].toInt() and 0x0F
        var fullCode = ((hmac[start].toInt() and 0x7F) shl 24) or
            ((hmac[start + 1].toInt() and 0xFF) shl 16) or
            ((hmac[start + 2].toInt() and 0xFF) shl 8) or
            (hmac[start + 3].toInt() and 0xFF)

        val sb = StringBuilder(5)
        for (i in 0 until 5) {
            sb.append(CODE_CHARS[fullCode % CODE_CHARS.length])
            fullCode /= CODE_CHARS.length
        }
        return sb.toString()
    }

    /**
     * Generates the base64 confirmation hash (the `k` query param) for a given
     * [time], [tag] and [identitySecret] (base64).
     *
     * Buffer = 8-byte big-endian time, optionally followed by the tag bytes
     * (tag truncated to 32 chars by Steam convention). HMAC-SHA1, base64.
     *
     * Steam signs accept/deny with the *operation* as the tag — `allow` /
     * `cancel`, not `accept` / `reject`.
     */
    fun generateConfirmationHash(time: Long, tag: String, identitySecret: String): String {
        val key = base64Decoder.decode(identitySecret.trim())
        val tagBytes = if (tag.isEmpty()) {
            ByteArray(0)
        } else {
            val truncated = if (tag.length > 32) tag.substring(0, 32) else tag
            truncated.toByteArray(Charsets.UTF_8)
        }

        val buffer = ByteArray(8 + tagBytes.size)
        var t = time
        for (i in 7 downTo 0) {
            buffer[i] = (t and 0xFF).toByte()
            t = t ushr 8
        }
        System.arraycopy(tagBytes, 0, buffer, 8, tagBytes.size)

        return base64Encoder.encode(hmacSha1(key, buffer))
    }

    /**
     * Deterministic SDA-style device id (`android:<uuid>`) derived from the
     * SteamID.
     *
     * Steam accepts any well-formed device id on `mobileconf` as long as it is
     * stable and non-empty; deriving it from the SteamID means a maFile that
     * lost its `device_id` field still confirms without needing to persist a
     * generated one.
     */
    fun generateDeviceId(steamId: Long): String {
        val digest = checkNotNull(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest("android-uuid:$steamId".toByteArray(Charsets.UTF_8)),
        )
        val hex = buildString(40) {
            for (b in digest) append("%02x".format(b.toInt() and 0xFF))
        }
        val s = hex.padEnd(32, '0').substring(0, 32)
        fun seg(a: Int, b: Int) = s.substring(a, b)
        return "android:${seg(0, 8)}-${seg(8, 12)}-${seg(12, 16)}-${seg(16, 20)}-${seg(20, 32)}"
    }

    /** Seconds remaining in the current 30s TOTP window for [time]. */
    fun secondsRemaining(time: Long): Int = (30 - (time % 30)).toInt()

    /** True when [code] is shaped like a Steam Guard code (5 chars, Steam alphabet). */
    fun looksLikeAuthCode(code: String): Boolean = sharedSecretRegex.matches(code)

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(CRYPTO_ALGORITHM)
        mac.init(SecretKeySpec(key, CRYPTO_ALGORITHM))
        return mac.doFinal(data)
    }
}
