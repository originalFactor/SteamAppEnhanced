package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.Jwt
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import cc.ptoe.steam.enhanced.net.SteamTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Why a headless session refresh could not complete on its own. */
enum class AutoLoginOutcome {
    /** Session is valid (refresh or full re-login succeeded). */
    OK,

    /** No stored password to re-login with. */
    NEEDS_PASSWORD,

    /** An email code (or other non-TOTP guard) is required. */
    NEEDS_INTERACTIVE,

    /** Steam said InvalidPassword — password changed elsewhere. */
    INVALID_CREDENTIALS,

    /** Network / other transient error. */
    FAILED,
}

/**
 * Headless (no-UI) Steam session maintenance: refresh the short-lived access token
 * from the refresh token, and — when the refresh token itself is dead — perform a
 * full re-login using the stored password plus the account's own TOTP secret
 * (which satisfies the device-code guard automatically).
 *
 * Ported from AVA's `services/auto_login.dart`.
 */
class AutoLogin(
    private val api: SteamApiClient,
    private val steamTime: SteamTime = SteamTime.shared,
    private val deviceName: String = "Android",
) {
    /**
     * Re-establishes [account]'s session from scratch using [password] and the
     * account's own Steam Guard (TOTP) secret.
     *
     * Returns the account with an updated session on success.
     */
    suspend fun reloginWithPassword(
        account: SteamGuardAccount,
        password: String,
    ): Pair<SteamGuardAccount, AutoLoginOutcome> {
        val username = account.accountName
        if (username.isNullOrEmpty()) return account to AutoLoginOutcome.FAILED
        return try {
            val session = SteamAuthSession(api, deviceName)
            session.beginWithCredentials(username, password)

            when {
                session.allowedConfirmations.contains(GuardType.DEVICE_CODE) -> {
                    val secret = account.sharedSecret
                    if (secret.isNullOrEmpty()) {
                        return account to AutoLoginOutcome.NEEDS_INTERACTIVE
                    }
                    val code = account.generateCode(steamTime.currentSteamTime())
                    session.submitSteamGuardCode(code, GuardType.DEVICE_CODE)
                }
                // An email code cannot be produced headlessly.
                session.allowedConfirmations.contains(GuardType.EMAIL_CODE) ->
                    return account to AutoLoginOutcome.NEEDS_INTERACTIVE
                // A device *confirmation* is what a phone app approves — this app is
                // the authenticator, so it can approve its own login only through the
                // interactive QR path, not headlessly.
                else -> Unit
            }

            // Poll for the resulting tokens. Steam usually answers on the first or
            // second attempt; ~7s of budget covers a slow round trip.
            repeat(POLL_ATTEMPTS) {
                val r = session.poll()
                if (r.complete) {
                    val data = session.toSessionData(r)
                    dlog("auto-login: ${account.accountName} re-logged in headlessly")
                    return account.withSession(
                        account.session.copy(
                            steamId = if (data.steamId != 0L) data.steamId else account.session.steamId,
                            accessToken = data.accessToken,
                            refreshToken = data.refreshToken,
                        ),
                    ) to AutoLoginOutcome.OK
                }
                delay(POLL_INTERVAL_MS)
            }
            dlog("auto-login: ${account.accountName} poll timed out")
            account to AutoLoginOutcome.FAILED
        } catch (e: SteamApiException) {
            dlog("auto-login failed (${account.accountName}): $e")
            // InvalidPassword is Steam's definitive verdict on the stored password —
            // the only failure worth surfacing as "account invalid". Everything else
            // (throttle, transport) stays FAILED.
            val outcome = if (e.eresult == EResult.INVALID_PASSWORD) {
                AutoLoginOutcome.INVALID_CREDENTIALS
            } else {
                AutoLoginOutcome.FAILED
            }
            account to outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            dlog("auto-login failed (${account.accountName}): $e")
            account to AutoLoginOutcome.FAILED
        }
    }

    /**
     * Ensures [account] has a usable access token.
     *
     * Tries the refresh token first; if that fails, falls back to a full headless
     * re-login using the password stored on the account (in the maFile).
     */
    suspend fun ensureSession(
        account: SteamGuardAccount,
    ): Pair<SteamGuardAccount, AutoLoginOutcome> {
        if (account.session.hasTokens) {
            val refreshed = SessionManager(api).refresh(account.session)
            if (refreshed != null) {
                return account.withSession(refreshed) to AutoLoginOutcome.OK
            }
        }
        val pwd = account.password
        if (pwd.isNullOrEmpty()) return account to AutoLoginOutcome.NEEDS_PASSWORD
        return reloginWithPassword(account, pwd)
    }

    companion object {
        private const val POLL_ATTEMPTS = 10
        private const val POLL_INTERVAL_MS = 700L

        /**
         * True when the access-token JWT is absent, unparseable, or expires within
         * [skewMillis] — i.e. worth refreshing proactively.
         */
        fun accessTokenStale(
            jwt: String?,
            nowMillis: Long = System.currentTimeMillis(),
            skewMillis: Long = 15 * 60 * 1000L,
        ): Boolean {
            val payload = Jwt.decodePayload(jwt) ?: return true
            val expirySeconds = Jwt.expiryEpochSeconds(payload) ?: return true
            val expiryMillis = expirySeconds * 1000
            return nowMillis + skewMillis > expiryMillis
        }
    }
}
