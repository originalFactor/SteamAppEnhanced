package cc.ptoe.steam.enhanced.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values come from AVA's own test suite, which cross-checked them
 * against an independent Python implementation of the Steam TOTP / confirmation
 * algorithms. Keeping these exact strings makes the Kotlin port verifiable
 * against the Flutter original rather than merely self-consistent.
 */
class SteamTotpTest {
    private val sharedSecret = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=" // b"12345678901234567890"
    private val identitySecret = "YWJjZGVmZ2hpamtsbW5vcHFyc3Q=" // b"abcdefghijklmnopqrst"

    @Test
    fun `auth code matches reference vector at 1700000000`() {
        assertEquals("R87JJ", SteamTotp.generateAuthCode(sharedSecret, 1_700_000_000))
    }

    @Test
    fun `auth code matches reference vector at 1634000000`() {
        assertEquals("D6C35", SteamTotp.generateAuthCode(sharedSecret, 1_634_000_000))
    }

    @Test
    fun `auth code uses Steam alphabet and is 5 chars`() {
        val code = SteamTotp.generateAuthCode(sharedSecret, 1_699_999_999)
        assertEquals(5, code.length)
        assertTrue(SteamTotp.looksLikeAuthCode(code))
    }

    @Test
    fun `auth code is stable within a 30s window`() {
        assertEquals(
            SteamTotp.generateAuthCode(sharedSecret, 1_700_000_000),
            SteamTotp.generateAuthCode(sharedSecret, 1_700_000_009),
        )
    }

    @Test
    fun `auth code changes across window boundary`() {
        assertNotEquals(
            SteamTotp.generateAuthCode(sharedSecret, 1_700_000_009),
            SteamTotp.generateAuthCode(sharedSecret, 1_700_000_010),
        )
    }

    @Test
    fun `confirmation hash matches reference vector for tag conf`() {
        assertEquals(
            "lARGXtefNbogvcyP7DZJI0+XBYQ=",
            SteamTotp.generateConfirmationHash(1_700_000_000, "conf", identitySecret),
        )
    }

    @Test
    fun `confirmation hash matches reference vector for tag allow`() {
        assertEquals(
            "v53H1MfBVFOCKLLTFJpiE7RCHWY=",
            SteamTotp.generateConfirmationHash(1_700_000_000, "allow", identitySecret),
        )
    }

    @Test
    fun `confirmation hash truncates tags longer than 32 chars`() {
        val longTag = "a".repeat(32) + "IGNORED-SUFFIX"
        assertEquals(
            SteamTotp.generateConfirmationHash(1_700_000_000, "a".repeat(32), identitySecret),
            SteamTotp.generateConfirmationHash(1_700_000_000, longTag, identitySecret),
        )
    }

    @Test
    fun `secondsRemaining counts down within window`() {
        assertEquals(30, SteamTotp.secondsRemaining(1_700_000_010))
        assertEquals(29, SteamTotp.secondsRemaining(1_700_000_011))
        assertEquals(1, SteamTotp.secondsRemaining(1_700_000_039))
    }

    @Test
    fun `device id is stable per steamid and well formed`() {
        val id = SteamTotp.generateDeviceId(76561197960265728L)
        assertEquals(id, SteamTotp.generateDeviceId(76561197960265728L))
        assertTrue(Regex("^android:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id))
        assertNotEquals(id, SteamTotp.generateDeviceId(76561197960265729L))
    }
}
