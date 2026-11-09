package cc.ptoe.steam.enhanced.app

import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import org.junit.Test
import kotlin.test.assertEquals

class AuthenticatorAccessTest {
    @Test
    fun `no account requires sign in`() {
        assertEquals(AuthenticatorAccess.SIGN_IN, AuthenticatorAccess.forAccount(null))
    }

    @Test
    fun `signed in account without transferred secret requires transfer`() {
        val account = SteamGuardAccount(session = SessionData(steamId = 123L, accessToken = "synthetic"))
        assertEquals(AuthenticatorAccess.TRANSFER, AuthenticatorAccess.forAccount(account))
        assertEquals(AuthenticatorAccess.TRANSFER, AuthenticatorAccess.forAccount(account.copy(sharedSecret = "  ")))
    }

    @Test
    fun `transferred account can scan and handle login requests`() {
        val account = SteamGuardAccount(sharedSecret = "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", session = SessionData(steamId = 123L))
        assertEquals(AuthenticatorAccess.READY, AuthenticatorAccess.forAccount(account))
    }

    @Test
    fun `offline code account cannot approve online login requests`() {
        val account = SteamGuardAccount(sharedSecret = "AAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        assertEquals(AuthenticatorAccess.SIGN_IN, AuthenticatorAccess.forAccount(account))
    }
}
