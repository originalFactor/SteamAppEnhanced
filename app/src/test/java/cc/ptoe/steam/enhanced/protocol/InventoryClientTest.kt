package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.model.InventoryGame
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.CommunityAuthException
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryClientTest {
    private lateinit var server: MockWebServer
    private lateinit var inventory: InventoryClient
    private val account = SteamGuardAccount(session = SessionData(steamId = 76561198000000001L, accessToken = "synthetic-token"))
    private val game = InventoryGame(753, "6", "Steam", "", 1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val destination = server.url("/")
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(request.url.newBuilder()
                .scheme(destination.scheme).host(destination.host).port(destination.port).build()).build())
        }.build()
        inventory = InventoryClient(SteamApiClient(steamLanguage = "schinese", client = http))
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `overview reads contexts and requests the selected language`() = runTest {
        server.enqueue(MockResponse().setBody("""<script>
            var g_steamID = '76561198000000001';
            var g_rgAppContextData = {"753":{"name":"Steam","rgContexts":{"6":{"asset_count":3}}}};
            var g_rgWalletInfo = {"wallet_currency":"23"};
            </script>"""))
        val result = inventory.overview(account)
        assertEquals(listOf(game.copy(itemCount = 3)), result.games)
        assertEquals(23, result.wallet?.currency)
        assertEquals("schinese", server.takeRequest().requestUrl?.queryParameter("l"))
    }

    @Test
    fun `empty array contexts represent an empty inventory`() = runTest {
        server.enqueue(MockResponse().setBody("<script>var g_rgAppContextData = []; var g_rgWalletInfo = [];</script>"))
        val result = inventory.overview(account)
        assertTrue(result.games.isEmpty())
        assertNull(result.wallet)
    }

    @Test
    fun `anonymous HTTP 200 page triggers session refresh instead of parser error`() = runTest {
        server.enqueue(MockResponse().setBody("<script>g_steamID = false;</script><div class='profile_private_info'></div>"))
        assertFailsWith<CommunityAuthException> { inventory.overview(account) }
    }

    @Test
    fun `private and missing inventory pages have explicit failure reasons`() = runTest {
        server.enqueue(MockResponse().setBody("<div class='profile_private_info'></div>"))
        assertEquals(InventoryFailure.PRIVATE, assertFailsWith<InventoryLoadException> { inventory.overview(account) }.reason)
        server.enqueue(MockResponse().setBody("<html>temporarily unavailable</html>"))
        assertEquals(InventoryFailure.PAGE_UNAVAILABLE, assertFailsWith<InventoryLoadException> { inventory.overview(account) }.reason)
    }

    @Test
    fun `HTTP error pages are not parsed as inventory HTML`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        assertEquals(EResult.RATE_LIMIT_EXCEEDED, assertFailsWith<SteamApiException> { inventory.overview(account) }.eresult)
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        assertFailsWith<SteamApiException> { inventory.overview(account) }
    }

    @Test
    fun `inventory item failures are not mistaken for empty success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":8,"Error":"This profile is private."}"""))
        assertEquals(InventoryFailure.PRIVATE, assertFailsWith<InventoryLoadException> { inventory.items(account, game) }.reason)
        server.enqueue(MockResponse().setBody("""{"success":false,"needauth":true}"""))
        assertFailsWith<CommunityAuthException> { inventory.items(account, game) }
        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        assertEquals(InventoryFailure.ITEMS_UNAVAILABLE, assertFailsWith<InventoryLoadException> { inventory.items(account, game) }.reason)
    }

    @Test
    fun `empty successful inventory remains valid`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1,"assets":[],"descriptions":[],"more_items":0}"""))
        assertEquals(InventoryPage(emptyList(), null), inventory.items(account, game))
    }
}
