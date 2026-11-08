package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.communityCookies
import cc.ptoe.steam.enhanced.net.newCommunitySessionId
import cc.ptoe.steam.enhanced.net.requireAccessToken
import cc.ptoe.steam.enhanced.net.MissingAccessTokenException
import cc.ptoe.steam.enhanced.net.storeCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class QrChallengeTest {

    @Test
    fun `parses a standard s team challenge`() {
        val c = QrChallenge.tryParse("https://s.team/q/1/123456789")
        assertEquals(1, c!!.version)
        assertEquals(123456789L, c.clientId)
    }

    @Test
    fun `parses a steamcommunity challenge`() {
        val c = QrChallenge.tryParse("https://steamcommunity.com/q/2/987654321")
        assertEquals(2, c!!.version)
        assertEquals(987654321L, c.clientId)
    }

    @Test
    fun `rejects non-Steam hosts so a crafted QR cannot be approved`() {
        assertNull(QrChallenge.tryParse("https://evil.example/q/1/123"))
        assertNull(QrChallenge.tryParse("https://s.team.evil.example/q/1/123"))
    }

    @Test
    fun `rejects non-https schemes`() {
        assertNull(QrChallenge.tryParse("http://s.team/q/1/123"))
        assertNull(QrChallenge.tryParse("javascript:alert(1)//q/1/123"))
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(QrChallenge.tryParse(""))
        assertNull(QrChallenge.tryParse("not a url"))
        assertNull(QrChallenge.tryParse("https://s.team/"))
        assertNull(QrChallenge.tryParse("https://s.team/q/x/y"))
    }

    @Test
    fun `a client id above 2^63 still parses`() {
        // Steam client ids are random uint64s; about half exceed Long.MAX_VALUE.
        val big = "18446744073709551615" // 2^64 - 1
        val c = QrChallenge.tryParse("https://s.team/q/1/$big")
        assertEquals(-1L, c!!.clientId) // raw bit pattern, not null
    }

    @Test
    fun `a client id beyond 64 bits is rejected`() {
        val tooBig = "18446744073709551616" // 2^64
        assertNull(QrChallenge.tryParse("https://s.team/q/1/$tooBig"))
    }
}

class SignaturesTest {

    private val sharedSecret = Base64.getEncoder()
        .encodeToString("12345678901234567890".toByteArray())

    private fun account() = SteamGuardAccount(
        sharedSecret = sharedSecret,
        identitySecret = Base64.getEncoder().encodeToString("abcdefghijklmnopqrst".toByteArray()),
        deviceId = "android:1111",
        session = SessionData(steamId = 76561197960265728L, accessToken = "at"),
    )

    @Test
    fun `qr approve signature is hmac-sha256 over little-endian version clientid steamid`() {
        val sig = QrApprovalClient.signature(
            account(),
            version = 1,
            clientId = 0x0102030405060708L,
            steamId = 76561197960265728L,
        )
        assertEquals(32, sig.size)

        // Reproduce independently: le16(version) || le64(clientId) || le64(steamId)
        val buf = java.io.ByteArrayOutputStream()
        buf.write(byteArrayOf(1, 0))
        for (v in listOf(0x0102030405060708L, 76561197960265728L)) {
            var x = v
            repeat(8) {
                buf.write((x and 0xFF).toInt())
                x = x ushr 8
            }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                Base64.getDecoder().decode(sharedSecret),
                "HmacSHA256",
            ),
        )
        assertTrue(sig.contentEquals(mac.doFinal(buf.toByteArray())))
    }

    @Test
    fun `qr signature differs per client id and version`() {
        val a = QrApprovalClient.signature(account(), 1, 100, 76561197960265728L)
        val b = QrApprovalClient.signature(account(), 1, 101, 76561197960265728L)
        val c = QrApprovalClient.signature(account(), 2, 100, 76561197960265728L)
        assertFalse(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun `revoke signature signs the decimal ascii not the integer layout`() {
        val tokenId = "12345678901234567890"
        val sig = SessionsClient.revokeSignature(sharedSecret, tokenId)
        assertEquals(32, sig.size)

        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                Base64.getDecoder().decode(sharedSecret),
                "HmacSHA256",
            ),
        )
        assertTrue(sig.contentEquals(mac.doFinal(tokenId.toByteArray(Charsets.US_ASCII))))

        // Crucially it must NOT equal the little-endian integer layout — mixing the
        // two schemes makes every revoke fail.
        val le = java.io.ByteArrayOutputStream()
        var x = java.math.BigInteger(tokenId).toLong()
        repeat(8) {
            le.write((x and 0xFF).toInt())
            x = x ushr 8
        }
        val leSig = mac.doFinal(le.toByteArray())
        assertFalse(sig.contentEquals(leSig))
    }

    @Test
    fun `decimal token id converts to the same 64 bits`() {
        // 2^63 has its top bit set; the conversion must not clamp.
        val decimal = "9223372036854775808"
        val asLong = SessionsClient.decimalToSignedLong(decimal)
        assertEquals(Long.MIN_VALUE, asLong)
        // And writing it as fixed64 then reading it back as unsigned restores it.
        val bytes = cc.ptoe.steam.enhanced.core.proto.ProtoWriter()
            .writeFixed64(1, asLong).toBytes()
        val field = cc.ptoe.steam.enhanced.core.proto.ProtoReader(bytes).parse()[1]!!
        assertEquals(decimal, field.asUInt64String)
    }
}

class CommunitySessionTest {

    @Test
    fun `confirmation cookies omit sessionid and carry the mobile client version`() {
        val cookies = communityCookies(
            SteamGuardAccount(
                session = SessionData(steamId = 1, accessToken = "tok"),
            ),
            confirmationVariant = true,
        )
        assertFalse(cookies.containsKey("sessionid"))
        assertEquals("android", cookies["mobileClient"])
        assertEquals("777777 3.6.4", cookies["mobileClientVersion"])
        // Signed with the identity secret instead, so no sessionid is expected.
        assertEquals("1||tok", cookies["steamLoginSecure"])
    }

    @Test
    fun `default cookies carry sessionid but no mobile client version`() {
        val cookies = communityCookies(
            SteamGuardAccount(session = SessionData(steamId = 1, accessToken = "tok")),
            sessionId = "abc123",
        )
        assertEquals("abc123", cookies["sessionid"])
        assertEquals("android", cookies["mobileClient"])
        assertFalse(cookies.containsKey("mobileClientVersion"))
    }

    @Test
    fun `store cookies deliberately omit the mobile marker`() {
        val cookies = storeCookies(
            SteamGuardAccount(session = SessionData(steamId = 1, accessToken = "tok")),
            sessionId = "s1",
        )
        assertFalse(cookies.containsKey("mobileClient"))
        assertEquals("s1", cookies["sessionid"])
        assertEquals("1||tok", cookies["steamLoginSecure"])
    }

    @Test
    fun `session id is 24 hex chars and unique`() {
        val a = newCommunitySessionId()
        val b = newCommunitySessionId()
        assertEquals(24, a.length)
        assertTrue(Regex("^[0-9a-f]{24}$").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `requireAccessToken rejects a refresh-only session`() {
        val refreshOnly = SteamGuardAccount(
            session = SessionData(steamId = 1, refreshToken = "rt"),
        )
        try {
            requireAccessToken(refreshOnly)
            fail("expected MissingAccessTokenException")
        } catch (_: MissingAccessTokenException) {
            // expected
        }
    }

    @Test
    fun `requireAccessToken returns the token when present`() {
        val account = SteamGuardAccount(
            session = SessionData(steamId = 1, accessToken = "tok"),
        )
        assertEquals("tok", requireAccessToken(account))
    }
}

class StoreKeyClientTest {

    @Test
    fun `normalize uppercases and strips whitespace but keeps punctuation`() {
        assertEquals("ABCDE-FGHIJ-KLMNO", StoreKeyClient.normalize(" abcde-fghij-klmno "))
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXY", StoreKeyClient.normalize("abcde fghij klmno pqrst uvwxy"))
        // Valve's dashless promo codes must not be regrouped.
        assertEquals("PROMO2026", StoreKeyClient.normalize("promo2026"))
    }

    @Test
    fun `maps known purchase_result_details to reasons`() {
        assertEquals(KeyRedeemError.ALREADY_OWNED, KeyRedeemResult.errorFor(9))
        assertEquals(KeyRedeemError.REGION_LOCKED, KeyRedeemResult.errorFor(13))
        assertEquals(KeyRedeemError.INVALID_KEY, KeyRedeemResult.errorFor(14))
        assertEquals(KeyRedeemError.ALREADY_ACTIVATED, KeyRedeemResult.errorFor(15))
        assertEquals(KeyRedeemError.NEEDS_BASE_PRODUCT, KeyRedeemResult.errorFor(24))
        assertEquals(KeyRedeemError.NEEDS_PS3_LOGIN, KeyRedeemResult.errorFor(36))
        assertEquals(KeyRedeemError.RATE_LIMITED, KeyRedeemResult.errorFor(53))
        // Anything unnamed stays UNKNOWN so the UI can show the raw number.
        assertEquals(KeyRedeemError.UNKNOWN, KeyRedeemResult.errorFor(999))
    }

    @Test
    fun `success is success==1 even when the detail is non-zero`() {
        // Valve's own registerkey.js branches on the flag alone; requiring detail==0
        // would report an activated key as failed and burn a second attempt.
        val json = JsonCoerce.parseObject(
            """
            {"success":1,"purchase_result_details":0,
             "purchase_receipt_info":{"line_items":[
               {"line_item_description":"Portal 2"},
               {"line_item_description":"  "}
             ]}}
            """.trimIndent(),
        )!!
        val result = KeyRedeemResult.parse(json)
        assertTrue(result.success)
        assertEquals(listOf("Portal 2"), result.products)
    }

    @Test
    fun `a failure carries the reason and no products`() {
        val json = JsonCoerce.parseObject(
            """{"success":2,"purchase_result_details":15}""",
        )!!
        val result = KeyRedeemResult.parse(json)
        assertFalse(result.success)
        assertEquals(KeyRedeemError.ALREADY_ACTIVATED, result.error)
        assertTrue(result.products.isEmpty())
    }

    @Test
    fun `an empty body is a failure rather than a phantom activation`() {
        val result = KeyRedeemResult.parse(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertFalse(result.success)
        assertNull(result.error?.takeIf { it != KeyRedeemError.UNKNOWN })
        assertEquals(-1, result.detail)
    }

    @Test
    fun `reads resultdetail spelling from the web reply as well`() {
        val json = JsonCoerce.parseObject(
            """{"success":2,"purchase_receipt_info":{"resultdetail":14}}""",
        )!!
        assertEquals(KeyRedeemError.INVALID_KEY, KeyRedeemResult.parse(json).error)
    }
}

class AuthenticatorWindowTest {

    @Test
    fun `no wait once the clock is already in a later window`() {
        // Submitted at 1_700_000_000 (window 56_666_666), now 1_700_000_030.
        assertEquals(
            0,
            AuthenticatorLinker.windowWaitSeconds(1_700_000_000, 1_700_000_030),
        )
    }

    @Test
    fun `waits out the remainder of the current window plus a buffer`() {
        // Window ends at 1_700_000_010; from 1_700_000_000 that is 10s + 1s buffer.
        assertEquals(
            11,
            AuthenticatorLinker.windowWaitSeconds(1_700_000_000, 1_700_000_000),
        )
        // At 1_700_000_009 we are still inside the old window: 1s left + 1s buffer.
        assertEquals(
            2,
            AuthenticatorLinker.windowWaitSeconds(1_700_000_000, 1_700_000_009),
        )
        // 1_700_000_010 is exactly the next window, so no wait is needed.
        assertEquals(
            0,
            AuthenticatorLinker.windowWaitSeconds(1_700_000_000, 1_700_000_010),
        )
    }

    @Test
    fun `never returns a non-positive wait`() {
        val wait = AuthenticatorLinker.windowWaitSeconds(1_700_000_009, 1_700_000_009)
        assertTrue(wait >= 1)
    }
}

class GuardTypeTest {

    @Test
    fun `maps wire values`() {
        assertEquals(GuardType.NONE, GuardType.fromWire(1))
        assertEquals(GuardType.EMAIL_CODE, GuardType.fromWire(2))
        assertEquals(GuardType.DEVICE_CODE, GuardType.fromWire(3))
        assertEquals(GuardType.DEVICE_CONFIRMATION, GuardType.fromWire(4))
        assertEquals(GuardType.EMAIL_CONFIRMATION, GuardType.fromWire(5))
        assertEquals(GuardType.UNKNOWN, GuardType.fromWire(99))
    }

    @Test
    fun `steamid is read from a jwt sub claim`() {
        // {"sub":"76561197960265728","exp":1700000000}
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"76561197960265728","exp":1700000000}""".toByteArray(),
        )
        val jwt = "header.$payload.signature"
        assertEquals(76561197960265728L, SteamAuthSession.steamIdFromJwt(jwt))
        assertNull(SteamAuthSession.steamIdFromJwt("garbage"))
        assertNull(SteamAuthSession.steamIdFromJwt(null))
    }
}
