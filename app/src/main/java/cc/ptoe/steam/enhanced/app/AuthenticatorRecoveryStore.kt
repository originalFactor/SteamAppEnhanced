package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.protocol.AuthenticatorRecovery
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ViewModel-owned rescue copy survives navigation and configuration changes. */
internal class AuthenticatorRecoveryStore(
    private val accountForId: (Long) -> SteamGuardAccount?,
    private val save: suspend (SteamGuardAccount) -> Unit,
) {
    private val mutex = Mutex()
    private val unsaved = MutableStateFlow<SteamGuardAccount?>(null)
    val pending = unsaved.asStateFlow()

    suspend fun finish(
        recovery: AuthenticatorRecovery,
        code: String,
        accountName: String,
        password: String?,
    ): SteamGuardAccount = withContext(NonCancellable) {
        mutex.withLock {
            check(unsaved.value == null) { "Save the recovered authenticator first." }
            val replacement = recovery.complete(code)
            unsaved.value = replacement
            val existing = accountForId(replacement.steamId)
            val updated = replacement.copy(
                accountName = replacement.accountName?.takeIf(String::isNotBlank) ?: accountName,
                password = password,
            ).let { if (existing != null) it.withProfileFrom(existing) else it }
            unsaved.value = updated
            save(updated)
            unsaved.value = null
            updated
        }
    }

    /** Retry only local storage; never repeat the irreversible Steam request. */
    suspend fun retry(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            val account = unsaved.value ?: return@withLock
            save(account)
            unsaved.value = null
        }
    }
}
