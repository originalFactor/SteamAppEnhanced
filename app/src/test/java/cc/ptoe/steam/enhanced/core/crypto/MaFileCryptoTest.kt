package cc.ptoe.steam.enhanced.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Byte-compatibility lock for the legacy SDA maFile scheme. The PBKDF2 vectors
 * are RFC 6070; the round trips are the contract that keeps existing users'
 * `.maFile` files opening.
 */
class MaFileCryptoTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `PBKDF2 RFC 6070 vector c=4096 dkLen=20`() {
        val salt = Base64.getEncoder().encodeToString("salt".toByteArray())
        val key = MaFileCrypto.deriveKey("password", salt, iterations = 4096, dkLen = 20)
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(key))
    }

    @Test
    fun `PBKDF2 RFC 6070 vector c=1 dkLen=20`() {
        val salt = Base64.getEncoder().encodeToString("salt".toByteArray())
        val key = MaFileCrypto.deriveKey("password", salt, iterations = 1, dkLen = 20)
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", hex(key))
    }

    @Test
    fun `PBKDF2 default derives a 32 byte key`() {
        val salt = MaFileCrypto.randomSalt()
        assertEquals(32, MaFileCrypto.deriveKey("hunter2", salt).size)
    }

    @Test
    fun `salt is 8 bytes and iv is 16 bytes`() {
        assertEquals(8, Base64.getDecoder().decode(MaFileCrypto.randomSalt()).size)
        assertEquals(16, Base64.getDecoder().decode(MaFileCrypto.randomIv()).size)
    }

    @Test
    fun `encrypt then decrypt yields original`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val plain = """{"shared_secret":"abc","account_name":"tester"}"""

        val cipher = MaFileCrypto.encrypt("hunter2", salt, iv, plain)
        assertNotEquals(plain, cipher)
        assertEquals(plain, MaFileCrypto.decrypt("hunter2", salt, iv, cipher))
    }

    @Test
    fun `wrong password returns null instead of throwing`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val cipher = MaFileCrypto.encrypt("right", salt, iv, "secret data")
        assertNull(MaFileCrypto.decrypt("wrong", salt, iv, cipher))
    }

    @Test
    fun `unicode plaintext round trips`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val plain = "账户：测试 — émoji 🎮"
        val cipher = MaFileCrypto.encrypt("пароль", salt, iv, plain)
        assertEquals(plain, MaFileCrypto.decrypt("пароль", salt, iv, cipher))
    }

    @Test
    fun `decryptBatch maps unreadable items to null and keeps the good one`() {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val good = MaFileCrypto.encrypt("pw", salt, iv, """{"a":1}""")

        val results = MaFileCrypto.decryptBatch(
            "pw",
            listOf(
                Triple(salt, iv, good),
                // Same ciphertext but an empty IV: cannot even be attempted.
                Triple(MaFileCrypto.randomSalt(), "", good),
                // Different salt, so the derived key differs -> unreadable.
                Triple(MaFileCrypto.randomSalt(), iv, good),
            ),
        )
        assertEquals("""{"a":1}""", results[0])
        assertNull(results[1])
        assertNull(results[2])
    }
}
