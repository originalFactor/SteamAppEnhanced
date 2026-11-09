package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.protocol.AuthSessionInfo
import cc.ptoe.steam.enhanced.protocol.QrChallenge

internal data class LoginRequestKey(val accountId: Long, val clientId: Long)
internal data class PendingLogin(val accountId: Long, val info: AuthSessionInfo, val order: Long = 0L) {
    val key get() = LoginRequestKey(accountId, info.clientId)
}

/** Keeps newly observed requests first and prevents an in-flight poll restoring a handled request. */
internal class PendingLoginFeed {
    private val requests = linkedMapOf<LoginRequestKey, PendingLogin>()
    private val handled = linkedSetOf<LoginRequestKey>()
    private var order = 0L
    val items: List<PendingLogin> get() = requests.values.sortedByDescending { it.order }

    fun update(accountId: Long, sessions: List<AuthSessionInfo>) {
        val ids = sessions.map { it.clientId }.toSet()
        requests.keys.removeAll { it.accountId == accountId && it.clientId !in ids }
        sessions.distinctBy { it.clientId }.asReversed().forEach { info ->
            val key = LoginRequestKey(accountId, info.clientId)
            if (key !in handled) requests[key] = PendingLogin(accountId, info, requests[key]?.order ?: ++order)
        }
    }

    fun retainAccounts(ids: Set<Long>) {
        requests.keys.removeAll { it.accountId !in ids }
    }

    fun resolve(key: LoginRequestKey) {
        requests.remove(key)
        handled.add(key)
        if (handled.size > 256) handled.remove(handled.first())
    }
}

internal class SteamQrScanGate {
    private var last: QrChallenge? = null
    private var lastAt = Long.MIN_VALUE

    fun accept(raw: String, nowMillis: Long): QrChallenge? {
        val challenge = QrChallenge.tryParse(raw) ?: return null
        if (challenge.version <= 0 || challenge.clientId == 0L) return null
        if (challenge == last && nowMillis - lastAt < 30_000) return null
        last = challenge
        lastAt = nowMillis
        return challenge
    }
}

/** A successful poll returns to the short interval; repeated failures ease server load. */
internal class LoginRequestPolling {
    private var intervalMillis = BASE_INTERVAL_MILLIS

    suspend fun run(refresh: suspend () -> Boolean) {
        while (true) kotlinx.coroutines.delay(nextDelay(refresh()))
    }

    fun nextDelay(failed: Boolean): Long {
        intervalMillis = if (failed) (intervalMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS) else BASE_INTERVAL_MILLIS
        return intervalMillis
    }

    companion object {
        const val BASE_INTERVAL_MILLIS = 2_000L
        const val MAX_INTERVAL_MILLIS = 30_000L
    }
}
