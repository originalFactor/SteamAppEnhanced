package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.net.SteamApiClient
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException

/**
 * Manages refreshing the short-lived access token from the refresh token.
 *
 * Ported from AVA's `services/session_manager.dart`.
 */
class SessionManager(private val api: SteamApiClient) {

    /**
     * True if the session carries a token worth trying.
     *
     * A JWT is opaque to us; real expiry is enforced server-side, so the app
     * refreshes on 401-style failures rather than trusting a local clock.
     */
    fun hasUsableToken(session: SessionData): Boolean = session.hasTokens

    /**
     * Exchanges the refresh token for a fresh access token.
     *
     * Returns the updated [SessionData] on success (the refresh token itself may
     * also be rotated), or null when there is no refresh token or the call fails.
     */
    suspend fun refresh(session: SessionData): SessionData? {
        val refreshToken = session.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            dlog("session refresh: no refresh_token (old maFile) — login required")
            return null
        }
        dlog("session refresh: exchanging refresh_token for new access_token…")
        return try {
            val req = ProtoWriter()
                .writeString(1, refreshToken)
                .writeFixed64(2, session.steamId)
            val fields = api.callProtobuf(
                IFACE,
                "GenerateAccessTokenForApp",
                request = req,
            ).parse()
            val newAccess = fields[1]?.asString
            if (newAccess.isNullOrEmpty()) {
                dlog("session refresh: failed (no access_token in response)")
                return null
            }
            val newRefresh = fields[2]?.asString
            dlog("session refresh: ok (new access_token)")
            session.copy(
                accessToken = newAccess,
                refreshToken = if (!newRefresh.isNullOrEmpty()) newRefresh else session.refreshToken,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            dlog("session refresh: error $e")
            null
        }
    }

    companion object {
        private const val IFACE = "IAuthenticationService"

        /** Base64 helper for debug dumps of token payloads (never the token). */
        internal fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    }
}
