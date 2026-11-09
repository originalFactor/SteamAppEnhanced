package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.protocol.AuthSessionInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingLoginFeedTest {
    private fun info(id: Long) = AuthSessionInfo(id, 1, "192.0.2.1", "Test city", "Test country", "Test device")

    @Test
    fun `newly discovered requests appear before existing requests`() {
        val feed = PendingLoginFeed()
        feed.update(1, listOf(info(10)))
        feed.update(1, listOf(info(10), info(20)))
        assertEquals(listOf(20L, 10L), feed.items.map { it.info.clientId })
    }

    @Test
    fun `a handled request cannot be restored by an older in flight poll`() {
        val feed = PendingLoginFeed()
        feed.update(1, listOf(info(10), info(20)))
        feed.resolve(LoginRequestKey(1, 10))
        feed.update(1, listOf(info(10), info(20)))
        assertEquals(listOf(20L), feed.items.map { it.info.clientId })
    }

    @Test
    fun `handling a request for one account does not affect another account`() {
        val feed = PendingLoginFeed()
        feed.update(1, listOf(info(10)))
        feed.update(2, listOf(info(10)))
        feed.resolve(LoginRequestKey(1, 10))
        assertEquals(2L, feed.items.single().accountId)
    }

    @Test
    fun `expired requests and removed accounts disappear`() {
        val feed = PendingLoginFeed()
        feed.update(1, listOf(info(10)))
        feed.update(2, listOf(info(20)))
        feed.update(1, emptyList())
        assertEquals(2L, feed.items.single().accountId)
        feed.retainAccounts(emptySet())
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `repeated QR frames produce one review during the cooldown`() {
        val gate = SteamQrScanGate()
        assertNotNull(gate.accept("https://s.team/q/1/123", 0))
        assertNull(gate.accept("https://s.team/q/1/123", 1))
        assertNull(gate.accept("https://s.team/q/1/123", 29_999))
        assertNotNull(gate.accept("https://s.team/q/1/123", 30_000))
    }

    @Test
    fun `a different valid Steam code can be reviewed without waiting`() {
        val gate = SteamQrScanGate()
        assertNotNull(gate.accept("https://s.team/q/1/123", 0))
        assertNotNull(gate.accept("https://s.team/q/1/18446744073709551615", 1))
    }

    @Test
    fun `untrusted or invalid QR codes never enter the review flow`() {
        val gate = SteamQrScanGate()
        for (raw in listOf(
            "https://evil.example/q/1/123", "http://s.team/q/1/123", "not a URL",
            "https://s.team/q/-1/123", "https://s.team/q/1/0",
        )) assertNull(gate.accept(raw, 0))
        assertNotNull(gate.accept("https://s.team/q/1/123", 0))
    }
}
