package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRefresherTest {
    private fun account(id: Long) = SteamGuardAccount(
        session = SessionData(steamId = id, accessToken = "original-session"),
        avatarUrl = "https://avatars.steamstatic.com/original.jpg",
    )

    private class Fixture(initial: List<SteamGuardAccount>) {
        var rows = initial
        var now = 0L
        val requests = mutableListOf<Long>()
        var load: suspend (SteamGuardAccount) -> SteamGuardAccount = {
            it.copy(personaName = "Updated", avatarUrl = "https://avatars.steamstatic.com/new.jpg")
        }
        val refresher = ProfileRefresher(
            accounts = { rows },
            load = { requests += it.steamId; load(it) },
            save = { profile ->
                val current = rows.firstOrNull { it.steamId == profile.steamId }
                if (current == null) false else {
                    rows = rows.map { if (it.steamId == profile.steamId) it.withProfileFrom(profile) else it }
                    true
                }
            },
            nowMillis = { now },
        )
    }

    @Test
    fun `profiles refresh immediately and again after ten minutes`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.refresher.refreshDue()
        f.now = ProfileRefresher.INTERVAL_MILLIS - 1
        f.refresher.refreshDue()
        assertEquals(listOf(1L), f.requests)
        f.now++
        f.refresher.refreshDue()
        assertEquals(listOf(1L, 1L), f.requests)
    }

    @Test
    fun `manual refresh bypasses the periodic cooldown`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.refresher.refreshDue()
        f.refresher.refresh(1, force = true)
        assertEquals(listOf(1L, 1L), f.requests)
    }

    @Test
    fun `one failing profile does not block other accounts or hammer the server`() = runTest {
        val f = Fixture(listOf(account(1), account(2)))
        f.load = { if (it.steamId == 1L) throw IOException("Offline") else it.copy(personaName = "Updated") }
        f.refresher.refreshDue()
        f.refresher.refreshDue()
        assertEquals(listOf(1L, 2L), f.requests)
        assertEquals("Updated", f.rows.last().personaName)
        assertEquals("https://avatars.steamstatic.com/original.jpg", f.rows.first().avatarUrl)
    }

    @Test
    fun `refresh never resurrects an account removed during a network request`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.load = { f.rows = emptyList(); it.copy(personaName = "Deleted") }
        assertNull(f.refresher.refresh(1))
        assertTrue(f.rows.isEmpty())
    }

    @Test
    fun `refresh updates display fields without overwriting a newer session or authenticator`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.load = { original ->
            f.rows = listOf(original.copy(
                session = original.session.copy(accessToken = "renewed-session"),
                sharedSecret = "replacement-secret", password = "new-password",
            ))
            original.copy(personaName = "Updated", avatarUrl = "https://avatars.steamstatic.com/new.jpg")
        }
        f.refresher.refresh(1)
        val saved = f.rows.single()
        assertEquals("renewed-session", saved.session.accessToken)
        assertEquals("replacement-secret", saved.sharedSecret)
        assertEquals("new-password", saved.password)
        assertEquals("Updated", saved.personaName)
        assertEquals("https://avatars.steamstatic.com/new.jpg", saved.avatarUrl)
    }

    @Test
    fun `cancellation propagates and permits an immediate retry on return to foreground`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.load = { throw CancellationException() }
        assertFailsWith<CancellationException> { f.refresher.refreshDue() }
        f.load = { it }
        f.refresher.refreshDue()
        assertEquals(listOf(1L, 1L), f.requests)
    }

    @Test
    fun `overlapping scheduled requests share one refresh per interval`() = runTest {
        val f = Fixture(listOf(account(1)))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.load = { started.complete(Unit); finish.await(); it }
        val first = async { f.refresher.refresh(1) }
        started.await()
        val second = async { f.refresher.refresh(1) }
        finish.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf(1L), f.requests)
    }

    @Test
    fun `offline authenticators and locked vaults do not make profile requests`() = runTest {
        val f = Fixture(listOf(account(0)))
        f.refresher.refreshDue()
        f.rows = emptyList()
        f.refresher.refreshDue()
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `ordinary account updates can preserve cached profile including cleared decorations`() {
        val cached = account(1).copy(personaName = "Nickname", animatedAvatarUrl = null, avatarFrameUrl = null)
        val updated = SteamGuardAccount(
            session = cached.session.copy(accessToken = "new-token"), sharedSecret = "new-secret",
            animatedAvatarUrl = "stale-animation", avatarFrameUrl = "stale-frame",
        ).withProfileFrom(cached)
        assertEquals(cached.avatarUrl, updated.avatarUrl)
        assertEquals("Nickname", updated.personaName)
        assertEquals("new-token", updated.session.accessToken)
        assertEquals("new-secret", updated.sharedSecret)
        assertNull(updated.avatarFrameUrl)
        assertNull(updated.animatedAvatarUrl)
    }

    @Test
    fun `a different account response is rejected without changing the vault`() = runTest {
        val f = Fixture(listOf(account(1)))
        f.load = { account(2) }
        assertFailsWith<IllegalStateException> { f.refresher.refresh(1) }
        assertEquals(1L, f.rows.single().steamId)
    }
}
