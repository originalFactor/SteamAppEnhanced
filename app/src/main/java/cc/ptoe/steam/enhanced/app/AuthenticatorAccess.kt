package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount

internal enum class AuthenticatorAccess {
    SIGN_IN, TRANSFER, READY;

    companion object {
        fun forAccount(account: SteamGuardAccount?): AuthenticatorAccess = when {
            account == null || account.steamId <= 0L -> SIGN_IN
            account.sharedSecret.isNullOrBlank() -> TRANSFER
            else -> READY
        }
    }
}
