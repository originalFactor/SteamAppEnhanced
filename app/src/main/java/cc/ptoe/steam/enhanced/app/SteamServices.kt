package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamTime
import cc.ptoe.steam.enhanced.protocol.AutoLogin
import cc.ptoe.steam.enhanced.protocol.AutoLoginOutcome
import cc.ptoe.steam.enhanced.protocol.ConfirmationsClient
import cc.ptoe.steam.enhanced.protocol.FamilyGroupsClient
import cc.ptoe.steam.enhanced.protocol.InventoryClient
import cc.ptoe.steam.enhanced.protocol.MarketClient
import cc.ptoe.steam.enhanced.protocol.ConfirmationAuthException
import cc.ptoe.steam.enhanced.protocol.QrApprovalClient
import cc.ptoe.steam.enhanced.protocol.SessionsClient
import cc.ptoe.steam.enhanced.protocol.StoreKeyClient
import cc.ptoe.steam.enhanced.protocol.TradeOffersClient
import cc.ptoe.steam.enhanced.store.AccountStore

internal class SteamServices(private val vault: AccountVault) {
    val api = SteamApiClient()
    val time = SteamTime.shared
    val confirmations = ConfirmationsClient(api)
    val trades = TradeOffersClient(api)
    val devices = SessionsClient(api)
    val family = FamilyGroupsClient(api)
    val approval = QrApprovalClient(api)
    val keys = StoreKeyClient(api)
    val inventory = InventoryClient(api)
    val market = MarketClient(api)
    private val login = AutoLogin(api)

    suspend fun session(id: Long, force: Boolean = false): SteamGuardAccount {
        val account = vault.state.value.accounts.firstOrNull { AccountStore.storageId(it) == id }
            ?: error("The account is no longer in the vault.")
        require(account.steamId > 0L) { "Connect the Steam account first." }
        if (!force && !AutoLogin.accessTokenStale(account.session.accessToken)) return account
        if (account.password != null && !time.isAligned) time.align()
        val (updated, outcome) = login.ensureSession(account)
        if (outcome != AutoLoginOutcome.OK) {
            if (!force && account.session.hasAccessToken &&
                !AutoLogin.accessTokenStale(account.session.accessToken, skewMillis = 0)
            ) return account
            error("This Steam session has expired. Sign in again from the account page.")
        }
        check(updated.steamId == account.steamId) { "Steam returned a different account." }
        vault.save(updated)
        return updated
    }

    suspend fun <T> read(id: Long, block: suspend (SteamGuardAccount) -> T): T {
        val account = session(id)
        return try {
            block(account)
        } catch (failure: Exception) {
            if (failure !is ConfirmationAuthException && !SteamApiClient.isSessionDeadError(failure)) throw failure
            block(session(id, force = true))
        }
    }
}
