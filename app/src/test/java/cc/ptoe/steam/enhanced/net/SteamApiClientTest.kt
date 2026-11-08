package cc.ptoe.steam.enhanced.net

import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Exercises the response-gating logic, which is where an authenticator app fails
 * most dangerously: a failure read as success tells the user a trade was
 * confirmed when it was not.
 */
class SteamApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = SteamApiClient(
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
    )

    @Test
    fun `bare 401 with no eresult counts as a dead session`() {
        val e = SteamApiException(2, "HTTP 401", "Method", httpStatus = 401)
        assertTrue(SteamApiClient.isSessionDeadError(e))
        val forbidden = SteamApiException(2, "HTTP 403", "Method", httpStatus = 403)
        assertTrue(SteamApiClient.isSessionDeadError(forbidden))
        // A 500 is not an auth problem — it must not trigger a silent re-login.
        val serverError = SteamApiException(2, "HTTP 500", "Method", httpStatus = 500)
        assertFalse(SteamApiClient.isSessionDeadError(serverError))
    }

    @Test
    fun `missing access token and community auth errors are dead-session errors`() {
        assertTrue(SteamApiClient.isSessionDeadError(MissingAccessTokenException()))
        assertTrue(SteamApiClient.isSessionDeadError(CommunityAuthException(401, "/p")))
        assertFalse(SteamApiClient.isSessionDeadError(IllegalStateException("other")))
    }

    @Test
    fun `steam origin gate accepts steam hosts and rejects everything else`() {
        assertTrue(SteamApiClient.isSteamOrigin("https://steamcommunity.com/x"))
        assertTrue(SteamApiClient.isSteamOrigin("https://store.steampowered.com/x"))
        assertTrue(SteamApiClient.isSteamOrigin("https://api.steampowered.com/x"))
        assertTrue(SteamApiClient.isSteamOrigin("https://sub.steamcommunity.com/x"))

        // The cookie-bearing redirect chain must never leave Steam.
        assertFalse(SteamApiClient.isSteamOrigin("https://evil.example/x"))
        assertFalse(SteamApiClient.isSteamOrigin("https://steamcommunity.com.evil.example/x"))
        assertFalse(SteamApiClient.isSteamOrigin("http://steamcommunity.com/x")) // not https
        assertFalse(SteamApiClient.isSteamOrigin("not a url"))
    }

    @Test
    fun `login redirect detection ignores a login string in the query`() {
        assertTrue(SteamApiClient.isLoginRedirect("https://steamcommunity.com/login/home"))
        assertTrue(SteamApiClient.isLoginRedirect("steammobile://lostauth"))
        // A goto= parameter containing /login is not a redirect to the login page.
        assertFalse(
            SteamApiClient.isLoginRedirect("https://steamcommunity.com/tradeoffer/1/?goto=/login"),
        )
        assertFalse(SteamApiClient.isLoginRedirect(""))
    }

    @Test
    fun `community 401 raises CommunityAuthException rather than an empty success`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.communityGetJson("/mobileconf/getlist", emptyMap())
            fail("expected CommunityAuthException")
        } catch (_: CommunityAuthException) {
            // expected: the caller must route to re-auth
        }
    }

    @Test
    fun `community 403 raises CommunityAuthException`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            api.communityGetJson("/mobileconf/getlist", emptyMap())
            fail("expected CommunityAuthException")
        } catch (_: CommunityAuthException) {
        }
    }

    @Test
    fun `a redirect to the login page is an auth failure`() = runTest {
        val api = pointingAt(server)
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://steamcommunity.com/login/home/"),
        )
        try {
            api.communityGetJson("/miniprofile/1/json", emptyMap())
            fail("expected CommunityAuthException")
        } catch (_: CommunityAuthException) {
        }
    }

    @Test
    fun `a non-login redirect is a plain failure not an auth failure`() = runTest {
        val api = pointingAt(server)
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://steamcommunity.com/market/eligibilitycheck"),
        )
        try {
            api.communityGetJson("/market/mylistings", emptyMap())
            fail("expected SteamApiException")
        } catch (e: CommunityAuthException) {
            fail("a market bounce is not an auth failure: $e")
        } catch (_: SteamApiException) {
            // expected
        }
    }

    @Test
    fun `a 4xx with a JSON diagnostic body is returned so its message reaches the UI`() = runTest {
        val api = pointingAt(server)
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"success":false,"message":"You have too many listings"}"""),
        )
        val json = api.communityPostJson("/market/sellitem", mapOf("a" to "b"))
        assertFalse(JsonCoerce.asBool(json["success"]))
        assertEquals("You have too many listings", JsonCoerce.asString(json["message"]))
    }

    @Test
    fun `an empty 200 body decodes to an empty object`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val json = api.communityPostJson("/market/removelisting/1", emptyMap())
        assertTrue(json.isEmpty())
    }

    @Test
    fun `a bare json array body is treated as a plain success`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(api.communityPostJson("/market/removelisting/1", emptyMap()).isEmpty())
    }

    @Test
    fun `community text scraping carries cookies across a redirect chain`() = runTest {
        val api = pointingAt(server)
        // First hop: set a cookie and bounce (the eligibility-check pattern).
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://steamcommunity.com/profiles/1/inventory/")
                .setHeader("Set-Cookie", "market_passed=1; Path=/"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>ok</html>"))

        val body = api.communityGetText(
            "/profiles/1/inventory/",
            cookies = mapOf("steamLoginSecure" to "1||tok"),
        )
        assertEquals("<html>ok</html>", body)

        val first = server.takeRequest()
        assertEquals("steamLoginSecure=1||tok", first.getHeader("Cookie"))
        val second = server.takeRequest()
        // The redirect target must receive the cookie the first hop set — without it
        // the two pages ping-pong forever.
        assertTrue(second.getHeader("Cookie")!!.contains("market_passed=1"))
        assertTrue(second.getHeader("Cookie")!!.contains("steamLoginSecure=1||tok"))
    }

    @Test
    fun `community text aborts when a redirect leaves the Steam origin`() = runTest {
        val api = pointingAt(server)
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example/steal"),
        )
        // The loop refuses to follow off-origin, so it returns the redirect body
        // (empty) rather than shipping steamLoginSecure to a rogue host.
        val body = api.communityGetText(
            "/profiles/1/inventory/",
            cookies = mapOf("steamLoginSecure" to "1||tok"),
        )
        assertEquals("", body)
        assertEquals(1, server.requestCount) // no second request was made
    }

    @Test
    fun `a stale session on the text path raises CommunityAuthException`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.communityGetText("/profiles/1/inventory/")
            fail("expected CommunityAuthException")
        } catch (_: CommunityAuthException) {
        }
    }

    @Test
    fun `protobuf request puts the encoded payload in the form body for POST`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("x-eresult", "1"))
        api.callProtobuf(
            "Iface", "Method",
            ProtoWriter().writeString(1, "abc"),
            accessToken = "tok",
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.contains("access_token=tok"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("input_protobuf_encoded="))
    }

    @Test
    fun `protobuf request uses GET with a query payload for const methods`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("x-eresult", "1"))
        api.callProtobuf(
            "Iface", "Method",
            ProtoWriter().writeString(1, "abc"),
            useGet = true,
        )
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.contains("input_protobuf_encoded="))
    }

    @Test
    fun `protobuf non-OK eresult throws with the error message header`() = runTest {
        val api = pointingAt(server)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("x-eresult", "84")
                .setHeader("x-error_message", "RateLimitExceeded"),
        )
        try {
            api.callProtobuf("Iface", "Method", ProtoWriter())
            fail("expected SteamApiException")
        } catch (e: SteamApiException) {
            assertEquals(84, e.eresult)
            assertEquals("RateLimitExceeded", e.message)
        }
    }

    @Test
    fun `protobuf bare 401 with no eresult is a failure not an empty success`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.callProtobuf("Iface", "Method", ProtoWriter())
            fail("expected SteamApiException")
        } catch (e: SteamApiException) {
            assertEquals(401, e.httpStatus)
            assertTrue(SteamApiClient.isSessionDeadError(e))
        }
    }

    @Test
    fun `apiGetJson surfaces a non-2xx as a session-dead failure when 401`() = runTest {
        val api = pointingAt(server)
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.apiGetJson("IEconService", "GetTradeOffers", emptyMap(), accessToken = "t")
            fail("expected SteamApiException")
        } catch (e: SteamApiException) {
            assertEquals(401, e.httpStatus)
        }
    }

    /**
     * A client whose base URLs are rewritten to point at the mock server.
     *
     * The production client hard-codes Steam's hosts, which is the right design for
     * an authenticator (a configurable base URL is a phishing vector), so tests
     * intercept at the OkHttp layer instead.
     */
    private fun pointingAt(server: MockWebServer): SteamApiClient {
        val base = server.url("/").toString().trimEnd('/')
        val redirecting = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor { chain ->
                val original = chain.request()
                val rewritten = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()
        return SteamApiClient(client = redirecting)
    }

    @Suppress("unused")
    private fun account() = SteamGuardAccount(
        session = SessionData(steamId = 1, accessToken = "tok"),
    )
}
