package cc.ptoe.steam.enhanced.core.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import kotlin.io.encoding.Base64
import javax.crypto.Cipher

/**
 * RSA encryption of the Steam password using the public key returned by
 * `GetPasswordRSAPublicKey` (modulus + exponent as hex strings).
 *
 * Ported from AVA's `core/crypto/steam_rsa.dart`. PKCS#1 v1.5 padding, result
 * base64 encoded — matches SteamKit / the Steam web login form.
 */
object SteamRsa {
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /**
     * Encrypts [password] with the account's per-session Steam RSA key.
     *
     * [modulusHex] / [exponentHex] come verbatim from
     * `IAuthenticationService/GetPasswordRSAPublicKey`. Both are plain hex
     * (no `0x` prefix, no separators); a malformed value throws, which the
     * caller must surface as a login failure rather than sending the password
     * unencrypted.
     */
    fun encryptPassword(password: String, modulusHex: String, exponentHex: String): String {
        val modulus = BigInteger(modulusHex.trim(), 16)
        val exponent = BigInteger(exponentHex.trim(), 16)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val out = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encode(out)
    }
}
