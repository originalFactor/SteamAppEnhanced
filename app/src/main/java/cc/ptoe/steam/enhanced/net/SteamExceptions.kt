package cc.ptoe.steam.enhanced.net

import java.io.IOException

/**
 * A Steam API call failed with a definite `EResult` (or a bare HTTP status when
 * no eresult header was present).
 *
 * Ported from AVA's `SteamApiException`. [httpStatus] lets callers detect a dead
 * session structurally instead of string-matching "HTTP 401" out of [message].
 */
class SteamApiException(
    val eresult: Int,
    override val message: String,
    val method: String,
    val httpStatus: Int? = null,
) : IOException("SteamApiException($method): $message (eresult=$eresult)")

/**
 * `steamcommunity.com` rejected the request at the HTTP level — a redirect to the
 * login page, or a bare 401/403.
 *
 * The session cookie is stale or missing; callers must route to re-auth instead
 * of parsing the (usually empty) body as a success-shaped `{}`.
 */
class CommunityAuthException(
    val status: Int,
    val path: String,
) : IOException("CommunityAuthException($path: HTTP $status)")

/**
 * An operation needs to call a Steam Web API endpoint directly but the account's
 * session has no access token (e.g. a refresh-only session that has not been
 * renewed yet).
 *
 * Without this check the call would silently go out with an empty access token,
 * which fails in a confusing, hard-to-diagnose way indistinguishable from a
 * genuine server error.
 */
class MissingAccessTokenException(
    override val message: String = "account session has no access token",
) : IOException("MissingAccessTokenException: $message")
