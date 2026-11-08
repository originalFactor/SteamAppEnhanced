package cc.ptoe.steam.enhanced.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class VaultCryptoTest {

    @Test
    fun `dek is 32 random bytes and unique per call`() {
        val a = VaultCrypto.generateDek()
        val b = VaultCrypto.generateDek()
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `pin wraps and unwraps the dek`() {
        val dek = VaultCrypto.generateDek()
        val salt = VaultCrypto.randomSaltB64()
        val wrapped = VaultCrypto.wrapDek("123456", salt, dek)
        assertArrayEquals(dek, VaultCrypto.unwrapDek("123456", salt, wrapped))
    }

    @Test
    fun `wrong pin returns null`() {
        val dek = VaultCrypto.generateDek()
        val salt = VaultCrypto.randomSaltB64()
        val wrapped = VaultCrypto.wrapDek("123456", salt, dek)
        assertNull(VaultCrypto.unwrapDek("654321", salt, wrapped))
    }

    @Test
    fun `wrong salt returns null`() {
        val dek = VaultCrypto.generateDek()
        val wrapped = VaultCrypto.wrapDek("123456", VaultCrypto.randomSaltB64(), dek)
        assertNull(VaultCrypto.unwrapDek("123456", VaultCrypto.randomSaltB64(), wrapped))
    }

    @Test
    fun `tampered blob is rejected by the GCM tag`() {
        val dek = VaultCrypto.generateDek()
        val salt = VaultCrypto.randomSaltB64()
        val wrapped = VaultCrypto.wrapDek("123456", salt, dek)
        val raw = Base64.getDecoder().decode(wrapped)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(raw)
        assertNull(VaultCrypto.unwrapDek("123456", salt, tampered))
    }

    @Test
    fun `blob layout is nonce then ciphertext then tag`() {
        val dek = VaultCrypto.generateDek()
        val raw = Base64.getDecoder().decode(VaultCrypto.encryptPayload(dek, "x"))
        // 12-byte nonce + 1 byte ciphertext + 16-byte tag
        assertEquals(12 + 1 + 16, raw.size)
    }

    @Test
    fun `payload round trips and a wrong dek yields null`() {
        val dek = VaultCrypto.generateDek()
        val plain = """{"shared_secret":"s3cr3t"}"""
        val blob = VaultCrypto.encryptPayload(dek, plain)
        assertEquals(plain, VaultCrypto.decryptPayload(dek, blob))
        assertNull(VaultCrypto.decryptPayload(VaultCrypto.generateDek(), blob))
    }

    @Test
    fun `a wrap written at a higher round count still opens with that count`() {
        // The count is recorded per wrap, so older installs are never locked out.
        val dek = VaultCrypto.generateDek()
        val salt = VaultCrypto.randomSaltB64()
        val wrapped = VaultCrypto.wrapDek("123456", salt, dek, iterations = 2048)
        assertNotNull(VaultCrypto.unwrapDek("123456", salt, wrapped, iterations = 2048))
        assertNull(VaultCrypto.unwrapDek("123456", salt, wrapped, iterations = 1))
    }
}
