package cc.ptoe.steam.enhanced.sync

import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSyncTest {
    private fun account(id: Long, name: String): JsonObject =
        SteamGuardAccount(
            accountName = name, sharedSecret = "c2VjcmV0", session = SessionData(steamId = id),
        ).toExportJson()

    @Test
    fun `independent edits merge while unchanged rows follow tombstones`() {
        val first = account(1L, "first")
        val second = account(2L, "second")
        val third = account(3L, "third")
        val base = mapOf(1L to first, 2L to second, 3L to third).mapValues { VaultSync.hash(it.value) }
        val localEdit = account(1L, "edited locally")
        val remoteEdit = account(2L, "edited remotely")
        val result = VaultSync.merge(
            mapOf(1L to localEdit, 2L to second, 3L to third),
            mapOf(1L to first, 2L to remoteEdit, 3L to JsonNull),
            base, emptyMap(),
        )
        assertTrue(result.conflicts.isEmpty())
        assertEquals(mapOf(1L to localEdit, 2L to remoteEdit, 3L to JsonNull), result.entries)
    }

    @Test
    fun `local deletion and remote edit require a choice bound to both versions`() {
        val base = mapOf(1L to VaultSync.hash(account(1L, "original")))
        val remote = mapOf(1L to account(1L, "remote edit"))
        val conflict = VaultSync.merge(emptyMap(), remote, base, emptyMap()).conflicts.single()
        val choice = SyncChoice(conflict.localHash, conflict.remoteHash, keepLocal = true)
        assertEquals(mapOf(1L to JsonNull), VaultSync.merge(emptyMap(), remote, base, mapOf(1L to choice)).entries)
        val changedAgain = mapOf(1L to account(1L, "remote changed again"))
        assertEquals(1, VaultSync.merge(emptyMap(), changedAgain, base, mapOf(1L to choice)).conflicts.size)
    }

    @Test
    fun `an account without a baseline cannot silently override a tombstone`() {
        val result = VaultSync.merge(mapOf(1L to account(1L, "first")), mapOf(1L to JsonNull), emptyMap(), emptyMap())
        assertEquals(1, result.conflicts.size)
        assertTrue(result.entries.isEmpty())
    }
}
