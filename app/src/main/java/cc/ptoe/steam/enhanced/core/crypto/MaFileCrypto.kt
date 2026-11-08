package cc.ptoe.steam.enhanced.core.crypto

import kotlin.io.encoding.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts `*.maFile` payloads, byte-for-byte compatible with the
 * legacy C# `FileEncryptor` (Steam Desktop Authenticator .NET).
 *
 * Ported from AVA's `core/crypto/ma_file_crypto.dart`. The scheme must not
 * change — existing user files depend on it:
 *
 * ```
 * key    = PBKDF2(password, salt, 50000 rounds, HMAC-SHA1) -> 32 bytes
 * cipher = AES-256-CBC, PKCS7 padding
 * salt:  8 random bytes,  base64
 * iv:    16 random bytes, base64
 * output: base64(ciphertext)
 * ```
 *
 * This is the *interop* scheme, used only at the import/export boundary. AVA's
 * own at-rest vault uses [VaultCrypto] instead.
 */
object MaFileCrypto {
    const val PBKDF2_ITERATIONS: Int = 50_000
    const val SALT_LENGTH: Int = 8
    const val KEY_SIZE_BYTES: Int = 32
    const val IV_LENGTH: Int = 16

    private const val HMAC_ALGORITHM = "HmacSHA1"
    private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val BLOCK_SIZE = 64 // HMAC-SHA1 block size, for PBKDF2

    private val base64: Base64 = Base64
    private val base64Decoder: Base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** 8-byte cryptographically random salt, base64 encoded. */
    fun randomSalt(): String = base64.encode(SecureRandomBytes.bytes(SALT_LENGTH))

    /** 16-byte cryptographically random IV, base64 encoded. */
    fun randomIv(): String = base64.encode(SecureRandomBytes.bytes(IV_LENGTH))

    /**
     * PBKDF2-HMAC-SHA1 key derivation.
     *
     * Exposed for cross-implementation tests (RFC 6070 vectors). Defaults match
     * the maFile scheme (50000 rounds, 32 bytes).
     */
    fun deriveKey(
        password: String,
        saltB64: String,
        iterations: Int = PBKDF2_ITERATIONS,
        dkLen: Int = KEY_SIZE_BYTES,
    ): ByteArray {
        require(password.isNotEmpty()) { "Password is empty" }
        require(saltB64.isNotEmpty()) { "Salt is empty" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(dkLen >= 1) { "dkLen must be >= 1" }
        return pbkdf2(
            password = password.toByteArray(Charsets.UTF_8),
            salt = base64Decoder.decode(saltB64),
            iterations = iterations,
            dkLen = dkLen,
        )
    }

    /**
     * Decrypts a base64 ciphertext.
     *
     * Returns `null` on a wrong key / bad padding, mirroring the C# behaviour of
     * returning null instead of throwing.
     */
    fun decrypt(
        password: String,
        saltB64: String,
        ivB64: String,
        encryptedB64: String,
        iterations: Int = PBKDF2_ITERATIONS,
    ): String? {
        require(password.isNotEmpty() && saltB64.isNotEmpty() &&
            ivB64.isNotEmpty() && encryptedB64.isNotEmpty()) {
            "Empty argument to decrypt"
        }
        return try {
            val key = deriveKey(password, saltB64, iterations)
            val iv = base64Decoder.decode(ivB64)
            val cipherText = base64Decoder.decode(encryptedB64)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Encrypts plaintext and returns base64 ciphertext. */
    fun encrypt(
        password: String,
        saltB64: String,
        ivB64: String,
        plaintext: String,
        iterations: Int = PBKDF2_ITERATIONS,
    ): String {
        require(password.isNotEmpty() && saltB64.isNotEmpty() &&
            ivB64.isNotEmpty() && plaintext.isNotEmpty()) {
            "Empty argument to encrypt"
        }
        val key = deriveKey(password, saltB64, iterations)
        val iv = base64Decoder.decode(ivB64)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return base64.encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Decrypts several payloads. Each item is `(salt, iv, ciphertext)` and maps
     * to its plaintext (or null on a wrong key).
     *
     * AVA offloads this to a background isolate so 50k-round PBKDF2 derivations
     * don't block the UI thread; callers here should run it on
     * [kotlinx.coroutines.Dispatchers.Default] for the same reason.
     */
    fun decryptBatch(
        password: String,
        items: List<Triple<String, String, String>>,
        iterations: Int = PBKDF2_ITERATIONS,
    ): List<String?> = items.map { (salt, iv, ct) ->
        if (salt.isEmpty() || iv.isEmpty() || ct.isEmpty()) {
            null
        } else {
            decrypt(password, salt, iv, ct, iterations)
        }
    }

    /**
     * PBKDF2-HMAC-SHA1 per RFC 2898. Implemented directly rather than through
     * `SecretKeyFactory` so the iteration/dkLen pair is exact and the RFC 6070
     * vectors are readable — two `SecretKeyFactory` implementations disagree
     * about how to accept a non-default key length.
     */
    private fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        // HMAC keys longer than the block size are hashed first; every Steam
        // password is shorter, but the standard requires the branch.
        val keyBytes = if (password.size > BLOCK_SIZE) {
            Mac.getInstance(HMAC_ALGORITHM).apply {
                init(SecretKeySpec(password, HMAC_ALGORITHM))
            }.doFinal(password)
        } else {
            password
        }
        mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))

        val hashLen = mac.macLength // 20 for SHA-1
        val blockCount = (dkLen + hashLen - 1) / hashLen
        val out = ByteArray(blockCount * hashLen)
        val u = ByteArray(hashLen)
        val t = ByteArray(hashLen)

        for (block in 1..blockCount) {
            // U1 = PRF(password, salt || INT_BE32(block))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                ),
            )
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hashLen)
            for (i in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until hashLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hashLen, hashLen)
        }
        return out.copyOf(dkLen)
    }
}
