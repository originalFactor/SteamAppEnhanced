package cc.ptoe.steam.enhanced.store

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.crypto.VaultCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/** Protects the vault key with Android Keystore; reads old PIN records only for migration. */
class VaultKeyStore(private val prefs: SharedPreferences) {
    fun exists(): Boolean = (prefs.contains(DEVICE_RECORD) || prefs.contains(RECORD))

    fun needsMigration(): Boolean = !prefs.contains(DEVICE_RECORD) && prefs.contains(RECORD)

    fun storeKey(dek: ByteArray) {
        require(dek.size == VaultCrypto.DEK_LENGTH)
        writeProtected(DEVICE_RECORD, Base64.encode(dek))
        check(prefs.edit().remove(RECORD).remove("failed_attempts").remove("retry_at").commit())
    }

    fun readKey(): ByteArray? = readProtected(DEVICE_RECORD)?.let {
        Base64.decode(it).also { key -> require(key.size == VaultCrypto.DEK_LENGTH) }
    }

    fun unwrapWithPin(pin: String): ByteArray? {
        val record = readProtected(RECORD) ?: return null
        val json = checkNotNull(JsonCoerce.parseObject(record)) { "Invalid vault record" }
        val salt = checkNotNull(JsonCoerce.asStringOrNull(json["salt"]))
        val blob = checkNotNull(JsonCoerce.asStringOrNull(json["wrap"]))
        val iterations = JsonCoerce.asInt(json["iter"], VaultCrypto.PIN_KDF_ITERATIONS)
        return VaultCrypto.unwrapDek(pin, salt, blob, iterations)
    }

    /** Protects additional credentials with the same non-exportable platform key. */
    fun writeProtected(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(name.encodeToByteArray())
        val record = Base64.encode(cipher.iv + cipher.doFinal(value.encodeToByteArray()))
        check(prefs.edit().putString(name, record).commit()) { "Could not save secure preferences" }
    }

    fun readProtected(name: String): String? {
        val record = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(record)
        require(bytes.size >= NONCE_LENGTH + 16) { "Invalid secure preferences record" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, NONCE_LENGTH))
        cipher.updateAAD(name.encodeToByteArray())
        return cipher.doFinal(bytes, NONCE_LENGTH, bytes.size - NONCE_LENGTH).decodeToString()
    }

    fun removeProtected(name: String) {
        check(prefs.edit().remove(name).commit()) { "Could not remove secure preferences" }
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@synchronized existing
        check(create && !exists()) { "The device vault key is unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val PREFS_NAME = "steam_vault"
        private const val DEVICE_RECORD = "vault_key"
        private const val RECORD = "pin_wrap"
        private const val KEY_ALIAS = "steam-enhanced-vault-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_LENGTH = 12
        private val KEY_LOCK = Any()
    }
}
