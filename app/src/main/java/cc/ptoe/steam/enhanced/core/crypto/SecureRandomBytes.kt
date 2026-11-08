package cc.ptoe.steam.enhanced.core.crypto

import java.security.SecureRandom

/**
 * Cryptographically random bytes / hex, from the platform CSPRNG.
 *
 * Mirrors AVA's `core/crypto/secure_random.dart`. Everything that touches a
 * secret — salts, IVs, the vault DEK, community `sessionid` values — goes
 * through here rather than `kotlin.random.Random`, which is not required to be
 * unpredictable.
 */
object SecureRandomBytes {
    private val random = SecureRandom()

    fun bytes(size: Int): ByteArray {
        require(size >= 0) { "size must be >= 0" }
        val out = ByteArray(size)
        random.nextBytes(out)
        return out
    }

    /** Lower-case hex of [size] random bytes' worth of entropy (2 chars/byte). */
    fun hex(charCount: Int): String {
        require(charCount >= 0) { "charCount must be >= 0" }
        val bytes = bytes((charCount + 1) / 2)
        val sb = StringBuilder(charCount)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.substring(0, charCount)
    }

    private const val HEX = "0123456789abcdef"
}
