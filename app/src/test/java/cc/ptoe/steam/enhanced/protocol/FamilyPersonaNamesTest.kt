package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.model.TradeOffer
import cc.ptoe.steam.enhanced.core.model.FamilyPersona
import cc.ptoe.steam.enhanced.net.SteamApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FamilyPersonaNamesTest {
    private lateinit var server: MockWebServer
    private lateinit var family: FamilyGroupsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val destination = server.url("/")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(request.url.newBuilder()
                .scheme(destination.scheme).host(destination.host).port(destination.port).build()).build())
        }.build()
        family = FamilyGroupsClient(SteamApiClient(client = http))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resolve unique SteamIDs through public account IDs without credentials`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/miniprofile/123/json" -> MockResponse().setBody("""{"persona_name":"  小明  ","avatar_url":"https://avatars.steamstatic.com/test.jpg"}""")
                "/miniprofile/456/json" -> MockResponse().setBody("""{"persona_name":"Alice"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val member = TradeOffer.STEAM_ID64_BASE + 123
        val inviter = TradeOffer.STEAM_ID64_BASE + 456
        assertEquals(mapOf(member to FamilyPersona("小明", "https://avatars.steamstatic.com/test.jpg"), inviter to FamilyPersona("Alice")), family.memberProfiles(listOf(member, inviter, member, 0, -1)))
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.requestUrl?.queryParameter("access_token"))
        }
    }

    @Test
    fun `missing malformed and failed profiles do not hide other names`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/miniprofile/1/json" -> MockResponse().setBody("""{"persona_name":"Good"}""")
                "/miniprofile/2/json" -> MockResponse().setBody("""{"persona_name":"  "}""")
                "/miniprofile/3/json" -> MockResponse().setBody("invalid JSON")
                else -> MockResponse().setResponseCode(503)
            }
        }
        val base = TradeOffer.STEAM_ID64_BASE
        assertEquals(mapOf(base + 1 to FamilyPersona("Good")), family.memberProfiles((1L..4L).map { base + it }))
    }

    @Test
    fun `avatar remains available when nickname is missing`() = runTest {
        val id = TradeOffer.STEAM_ID64_BASE + 123
        server.enqueue(MockResponse().setBody("""{"avatar_url":"https://avatars.steamstatic.com/test.jpg"}"""))
        assertEquals(FamilyPersona(avatarUrl = "https://avatars.steamstatic.com/test.jpg"), family.memberProfiles(listOf(id))[id])
    }

    @Test
    fun `refresh resolves changed nickname`() = runTest {
        val id = TradeOffer.STEAM_ID64_BASE + 123
        server.enqueue(MockResponse().setBody("""{"persona_name":"Before"}"""))
        server.enqueue(MockResponse().setBody("""{"persona_name":"After"}"""))
        assertEquals("Before", family.memberProfiles(listOf(id))[id]?.nickname)
        assertEquals("After", family.memberProfiles(listOf(id))[id]?.nickname)
    }
}
