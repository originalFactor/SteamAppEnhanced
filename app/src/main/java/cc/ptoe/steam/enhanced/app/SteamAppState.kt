package cc.ptoe.steam.enhanced.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cc.ptoe.steam.enhanced.sync.VaultSync

internal class SteamAppState(application: Application) : AndroidViewModel(application) {
    val vault = AccountVault(application)
    val unlockController = VaultUnlockController(vault)
    val preferences = AppPreferences(application)
    val services = SteamServices(vault)
    val authenticatorRecovery = AuthenticatorRecoveryStore(
        accountForId = { id -> vault.state.value.accounts.firstOrNull { it.steamId == id } },
        save = vault::saveAuthenticator,
    )
    val sync = VaultSync(vault)
}
