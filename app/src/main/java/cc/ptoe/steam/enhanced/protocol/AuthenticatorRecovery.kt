package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamApiClient

/** The weak credential stays inside this transient challenge, never in a maFile. */
class AuthenticatorRecovery internal constructor(api: SteamApiClient, val steamId: Long, weakToken: String) {
    private val linker = AuthenticatorLinker(api, SessionData(steamId = steamId, accessToken = weakToken))
    private var challengeStarted = false
    private var replacement: SteamGuardAccount? = null

    suspend fun start(): LinkResult {
        check(replacement == null) { "The authenticator has already moved." }
        return linker.moveInStart().also { if (it == LinkResult.CHALLENGE_EMAIL_SENT) challengeStarted = true }
    }

    /** Caller must protect this call AND the following local save from cancellation. */
    suspend fun complete(smsCode: String): SteamGuardAccount {
        replacement?.let { return it }
        check(challengeStarted) { "Request a transfer code first." }
        require(smsCode.isNotBlank()) { "Enter the transfer code." }
        val result = linker.moveInContinue(smsCode.trim())
        check(result == LinkResult.MOVED_IN) { result.name }
        // The linker carries the weak token to make the requests. Drop it before
        // handing the account to ANY storage/export/profile code.
        return checkNotNull(linker.linkedAccount).copy(session = SessionData(steamId = steamId)).also {
            replacement = it
        }
    }
}
