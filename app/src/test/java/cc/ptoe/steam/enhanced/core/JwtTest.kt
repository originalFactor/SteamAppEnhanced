package cc.ptoe.steam.enhanced.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString(payloadJson.toByteArray()) + ".sig"
    }

    @Test
    fun `decodes a payload with sub and exp`() {
        val payload = Jwt.decodePayload(jwt("""{"sub":"76561197960265728","exp":1700000000}"""))
        assertEquals(76561197960265728L, Jwt.steamId(payload))
        assertEquals(1_700_000_000L, Jwt.expiryEpochSeconds(payload))
    }

    @Test
    fun `accepts the base64 alphabet with standard punctuation and padding`() {
        // Steam's tokens are base64url, but a proxy may re-encode as standard base64.
        val standard = Base64.getEncoder()
            .encodeToString("""{"sub":"1"}""".toByteArray())
        assertEquals(1L, Jwt.steamId(Jwt.decodePayload("h.$standard.s")))
    }

    @Test
    fun `rejects malformed tokens`() {
        assertNull(Jwt.decodePayload(null))
        assertNull(Jwt.decodePayload(""))
        assertNull(Jwt.decodePayload("no-dots"))
        assertNull(Jwt.decodePayload("a.b")) // not valid base64 -> not json
    }

    @Test
    fun `a non-object payload is rejected`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        // A JSON array is not a claim set.
        assertNull(Jwt.decodePayload("h.${enc.encodeToString("[1,2]".toByteArray())}.s"))
    }

    @Test
    fun `missing claims read as null`() {
        val payload = Jwt.decodePayload(jwt("""{"other":"x"}"""))
        assertNull(Jwt.steamId(payload))
        assertNull(Jwt.expiryEpochSeconds(payload))
    }

    @Test
    fun `exp as a string is still read`() {
        val payload = Jwt.decodePayload(jwt("""{"exp":"1700000000"}"""))
        assertEquals(1_700_000_000L, Jwt.expiryEpochSeconds(payload))
    }
}
