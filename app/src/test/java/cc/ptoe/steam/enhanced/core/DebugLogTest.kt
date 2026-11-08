package cc.ptoe.steam.enhanced.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction pass exists so an opt-in "attach debug log" report can never mail
 * a token to the developer inbox. These tests pin the values that must disappear.
 */
class DebugLogTest {

    @Test
    fun `redacts keyed secrets`() {
        val input = "access_token=abc123 refresh_token: xyz shared_secret=c2VjcmV0 password=hunter2"
        val out = DebugLog.redactSecrets(input)
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("c2VjcmV0"))
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("<redacted>"))
        // The surrounding message stays readable.
        assertTrue(out.contains("access_token"))
    }

    @Test
    fun `redacts a padded base64 secret such as a steam shared secret`() {
        // 20 raw bytes -> 28 base64 chars ending in '='.
        val secret = java.util.Base64.getEncoder()
            .encodeToString("12345678901234567890".toByteArray())
        val out = DebugLog.redactSecrets("secret is $secret ok")
        assertFalse(out.contains(secret))
        assertTrue(out.contains("secret is "))
    }

    @Test
    fun `redacts long bare tokens`() {
        val jwtLike = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payloadparthere.signature"
        val out = DebugLog.redactSecrets("token $jwtLike end")
        assertFalse(out.contains("payloadparthere"))
    }

    @Test
    fun `leaves ordinary messages intact`() {
        val input = "← getlist HTTP 200 512B tag=list"
        assertEquals(input, DebugLog.redactSecrets(input))
    }

    @Test
    fun `leaves steam iface and method paths readable`() {
        // A URL path like Iface/Method must not be mistaken for a credential.
        val input = "GET IAuthenticationService/GetPasswordRSAPublicKey (12B)"
        assertEquals(input, DebugLog.redactSecrets(input))
    }

    @Test
    fun `ring buffer keeps only the newest lines`() {
        DebugLog.clear()
        repeat(450) { DebugLog.log("line $it") }
        val lines = DebugLog.lines()
        assertEquals(400, lines.size)
        assertTrue(lines.last().contains("line 449"))
    }
}
