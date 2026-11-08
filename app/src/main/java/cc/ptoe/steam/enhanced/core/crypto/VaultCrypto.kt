package cc.ptoe.steam.enhanced.core.crypto

import kotlin.io.encoding.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app's own at-rest encryption for the `maFiles/` vault.
 *
 * Ported from AVA's `core/crypto/vault_crypto.dart`. Unlike [MaFileCrypto]
 * (which is byte-compatible with the legacy SDA/.NET format and used only at
 * the import/export boundary), this scheme is internal and can evolve freely.
 *
 * It uses a random 256-bit Data Encryption Key (DEK) with AES-256-GCM
 * (authenticated). The DEK is never derived from the PIN — it is generated
 * randomly and kept in Android Keystore-backed storage. The PIN only *wraps*
 * the DEK ([wrapDek]) as a binding gate.
 *
 * Blob layout for every ciphertext (base64 encoded):
 * ```
 * nonce(12) || ciphertext || tag(16)
 * ```
 */
object VaultCrypto {
    const val DEK_LENGTH: Int = 32 // AES-256
    const val NONCE_LENGTH: Int = 12 // GCM standard nonce
    const val MAC_SIZE_BITS: Int = 128 // 16-byte tag
    const val PIN_SALT_LENGTH: Int = 16

    /**
     * PBKDF2-SHA256 rounds for the PIN-derived key-encryption key — the RFC
     * minimum, on purpose.
     *
     * A 6-digit PIN has only 10^6 candidates, so no feasible round count
     * survives an offline attack (one GPU clears the whole space at 100k rounds
     * in ~20s); iterations buy nothing but unlock latency. The real barrier is
     * the Keystore master key: off-device the wrapped DEK is unreadable
     * regardless of the PIN. The KDF's only job is to map the PIN onto a 256-bit
     * key. The count is recorded per-wrap (see `VaultKeyStore`), so old wraps
     * keep opening and get re-wrapped on their next unlock.
     */
    const val PIN_KDF_ITERATIONS: Int = 1

    private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val base64: Base64 = Base64
    private val base64Decoder: Base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** A fresh random 256-bit DEK. */
    fun generateDek(): ByteArray = SecureRandomBytes.bytes(DEK_LENGTH)

    /** A fresh random 16-byte salt, base64 encoded (for [wrapDek]). */
    fun randomSaltB64(): String = base64.encode(SecureRandomBytes.bytes(PIN_SALT_LENGTH))

    /** Independent, password-strength key for an encrypted WebDAV snapshot. */
    internal fun deriveSyncKey(passphrase: String, salt: String): ByteArray {
        require(passphrase.length >= 8 && base64Decoder.decode(salt).size == PIN_SALT_LENGTH)
        return deriveKek(passphrase, salt, 600_000)
    }

    /** Derives the PIN key-encryption key with PBKDF2-HMAC-SHA256. */
    private fun deriveKek(pin: String, saltB64: String, iterations: Int): ByteArray {
        require(pin.isNotEmpty()) { "PIN is empty" }
        require(iterations >= 1) { "iterations must be >= 1" }
        return pbkdf2Sha256(
            password = pin.toByteArray(Charsets.UTF_8),
            salt = base64Decoder.decode(saltB64),
            iterations = iterations,
            dkLen = DEK_LENGTH,
        )
    }

    /** AES-256-GCM encrypt [plain] under [key]; returns `nonce||ct||tag` (base64). */
    private fun seal(key: ByteArray, plain: ByteArray): String {
        val nonce = SecureRandomBytes.bytes(NONCE_LENGTH)
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(MAC_SIZE_BITS, nonce),
        )
        val ct = cipher.doFinal(plain)
        return base64.encode(nonce + ct)
    }

    /** Inverse of [seal]; returns null on a wrong key or tampered data. */
    private fun open(key: ByteArray, blobB64: String): ByteArray? = try {
        val raw = base64Decoder.decode(blobB64)
        val minLength = NONCE_LENGTH + (MAC_SIZE_BITS / 8)
        if (raw.size < minLength) {
            null
        } else {
            val nonce = raw.copyOfRange(0, NONCE_LENGTH)
            val ct = raw.copyOfRange(NONCE_LENGTH, raw.size)
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(MAC_SIZE_BITS, nonce),
            )
            cipher.doFinal(ct)
        }
    } catch (_: Exception) {
        null // AEADBadTagException (bad tag) or malformed input
    }

    /**
     * Wraps [dek] with a PIN-derived key. Returns a base64 blob to store in
     * Keystore-backed secure storage.
     */
    fun wrapDek(
        pin: String,
        pinSaltB64: String,
        dek: ByteArray,
        iterations: Int = PIN_KDF_ITERATIONS,
    ): String {
        require(dek.size == DEK_LENGTH) { "DEK must be $DEK_LENGTH bytes" }
        return seal(deriveKek(pin, pinSaltB64, iterations), dek)
    }

    /**
     * Unwraps a blob produced by [wrapDek]. Returns null on a wrong PIN, wrong
     * salt, or tampering. A blob that decrypts to the wrong length is also
     * rejected — it means the caller's parameters don't match the wrap.
     */
    fun unwrapDek(
        pin: String,
        pinSaltB64: String,
        blobB64: String,
        iterations: Int = PIN_KDF_ITERATIONS,
    ): ByteArray? {
        val dek = open(deriveKek(pin, pinSaltB64, iterations), blobB64)
        return if (dek != null && dek.size == DEK_LENGTH) dek else null
    }

    /** Encrypts a maFile payload string with the raw [dek]. Returns base64. */
    fun encryptPayload(dek: ByteArray, plaintext: String): String {
        require(dek.size == DEK_LENGTH) { "DEK must be $DEK_LENGTH bytes" }
        return seal(dek, plaintext.toByteArray(Charsets.UTF_8))
    }

    /** Decrypts a payload produced by [encryptPayload]; null on wrong DEK/tamper. */
    fun decryptPayload(dek: ByteArray, blobB64: String): String? {
        require(dek.size == DEK_LENGTH) { "DEK must be $DEK_LENGTH bytes" }
        val plain = open(dek, blobB64) ?: return null
        return try {
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** PBKDF2-HMAC-SHA256 per RFC 2898 (SHA-256 block size is 64 bytes). */
    private fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))

        val hashLen = mac.macLength // 32 for SHA-256
        val blockCount = (dkLen + hashLen - 1) / hashLen
        val out = ByteArray(blockCount * hashLen)
        val u = ByteArray(hashLen)
        val t = ByteArray(hashLen)

        for (block in 1..blockCount) {
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
