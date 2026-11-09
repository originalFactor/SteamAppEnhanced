package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.R
import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Auth-per-operation Keystore key. Device authentication retrieves only the vault key. */
@RequiresApi(30)
internal class DeviceUnlock(private val activity: Activity) : DeviceVaultUnlock {
    private val prefs = activity.getSharedPreferences("device_unlock", Context.MODE_PRIVATE)
    override val enabled: Boolean get() = prefs.contains(RECORD)
    val available: Boolean
        get() = activity.getSystemService(BiometricManager::class.java)
            .canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun enable(dek: ByteArray): Boolean {
        val cipher = withContext(Dispatchers.IO) {
            Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        }
        val authorized = authenticate(cipher, activity.getString(R.string.msg_biometric_enable)) ?: return false
        withContext(Dispatchers.IO) {
            val ciphertext = Base64.encode(authorized.iv + authorized.doFinal(dek))
            check(prefs.edit().putString(RECORD, ciphertext).commit()) { "Could not save device unlock." }
        }
        return true
    }

    override suspend fun unlock(): ByteArray? {
        val (cipher, bytes) = withContext(Dispatchers.IO) {
            val stored = prefs.getString(RECORD, null) ?: error("Device unlock is disabled.")
            val bytes = Base64.decode(stored)
            val cipher = Cipher.getInstance(CIPHER).apply {
                init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, 12))
            }
            cipher to bytes
        }
        val authorized = authenticate(cipher, activity.getString(R.string.ava_unlock_title)) ?: return null
        return withContext(Dispatchers.IO) { authorized.doFinal(bytes, 12, bytes.size - 12) }
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(RECORD).commit()) { "Could not disable device unlock." }
        KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(ALIAS) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Device unlock is unavailable. Open the vault to enable device unlock again." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    ).build(),
            )
        }.generateKey()
    }

    private suspend fun authenticate(cipher: Cipher, title: String): Cipher? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                BiometricPrompt.Builder(activity).setTitle(title).setAllowedAuthenticators(AUTHENTICATORS)
                    .build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (!continuation.isActive) return
                                when (errorCode) {
                                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> continuation.resume(null)
                                    else -> continuation.resumeWithException(
                                        IllegalStateException("Device unlock is unavailable. Open the vault to enable device unlock again."),
                                    )
                                }
                            }
                        },
                    )
            }
        }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val RECORD = "wrapped_key"
        private const val ALIAS = "steam-enhanced-device-unlock"
        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
