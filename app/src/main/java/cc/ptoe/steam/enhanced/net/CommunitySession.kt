package cc.ptoe.steam.enhanced.net

import cc.ptoe.steam.enhanced.core.crypto.SecureRandomBytes
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount

/**
 * Shared session plumbing for the steamcommunity.com / Web API clients: the
 * missing-access-token guard, the generated `sessionid`, and the cookie sets.
 *
 * Ported from AVA's `core/protocol/community_session.dart` — one definition
 * instead of the per-client copies that used to drift.
 */

/**
 * Guards endpoints that authenticate with the access token alone (a refresh
 * token — [SessionData.hasTokens] without `hasAccessToken` — is not enough):
 * returns the non-empty token, or fails clearly instead of letting the call go
 * out with an empty one.
 */
@Throws(MissingAccessTokenException::class)
fun requireAccessToken(account: SteamGuardAccount): String {
    val token = account.session.accessToken
    if (token.isNullOrEmpty()) throw MissingAccessTokenException()
    return token
}

/**
 * A fresh community `sessionid` value: 24 hex chars, cryptographically random.
 *
 * Steam only checks that the cookie and the form/query field match, so each write
 * operation can mint its own.
 */
fun newCommunitySessionId(): String = SecureRandomBytes.hex(24)

/**
 * The mobile-client cookie set for steamcommunity.com requests.
 *
 * Default shape (trade offers / market / inventory): `steamLoginSecure` +
 * `sessionid` (pass [sessionId] when the same value must also go into the form
 * body; omitted, a fresh one is minted) + `mobileClient`.
 *
 * [confirmationVariant] is the mobileconf shape: no `sessionid` (those requests
 * are signed with the identity secret instead) but `mobileClientVersion`, which
 * the endpoint requires.
 */
fun communityCookies(
    account: SteamGuardAccount,
    sessionId: String? = null,
    confirmationVariant: Boolean = false,
): Map<String, String> {
    val cookies = LinkedHashMap<String, String>()
    cookies["steamLoginSecure"] = "${account.steamId}||${account.session.accessToken.orEmpty()}"
    if (!confirmationVariant) {
        cookies["sessionid"] = sessionId ?: newCommunitySessionId()
    }
    cookies["mobileClient"] = "android"
    if (confirmationVariant) {
        cookies["mobileClientVersion"] = "777777 3.6.4"
    }
    return cookies
}

/**
 * The cookie set for `store.steampowered.com` requests.
 *
 * Same `steamLoginSecure` shape as the community host (Steam's login is unified
 * across both), plus the `sessionid` that store write endpoints cross-check
 * against the form field.
 *
 * Deliberately *without* `mobileClient`: the store serves a stripped mobile-app
 * layout to that marker, and its ajax endpoints are not part of it.
 */
fun storeCookies(
    account: SteamGuardAccount,
    sessionId: String? = null,
): Map<String, String> = mapOf(
    "steamLoginSecure" to "${account.steamId}||${account.session.accessToken.orEmpty()}",
    "sessionid" to (sessionId ?: newCommunitySessionId()),
)
