package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.store.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes profile requests and throttles each account, including failed attempts. */
internal class ProfileRefresher(
    private val accounts: () -> List<SteamGuardAccount>,
    private val load: suspend (SteamGuardAccount) -> SteamGuardAccount,
    private val save: suspend (SteamGuardAccount) -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private val attemptedAt = mutableMapOf<Long, Long>()

    suspend fun refresh(id: Long, force: Boolean = false): SteamGuardAccount? = mutex.withLock {
        val current = accounts().firstOrNull { AccountStore.storageId(it) == id } ?: return@withLock null
        if (current.steamId <= 0L) return@withLock current
        val now = nowMillis()
        val lastAttempt = attemptedAt[id]
        if (!force && lastAttempt != null && now - lastAttempt < INTERVAL_MILLIS) return@withLock current
        attemptedAt[id] = now
        try {
            val profile = load(current)
            check(AccountStore.storageId(profile) == id) { "Steam returned a different profile." }
            if (!save(profile)) return@withLock null
            accounts().firstOrNull { AccountStore.storageId(it) == id }
        } catch (cancelled: CancellationException) {
            attemptedAt.remove(id)
            throw cancelled
        }
    }

    suspend fun refreshDue() {
        val ids = accounts().filter { it.steamId > 0L }.map(AccountStore::storageId)
        mutex.withLock { attemptedAt.keys.retainAll(ids.toSet()) }
        for (id in ids) {
            try {
                refresh(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dlog("Scheduled profile refresh failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val INTERVAL_MILLIS = 10 * 60_000L
    }
}
