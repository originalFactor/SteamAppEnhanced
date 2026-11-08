package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.model.TradeOffer
import cc.ptoe.steam.enhanced.core.model.TradeOffersPage
import cc.ptoe.steam.enhanced.net.CommunityAuthException
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.communityCookies
import cc.ptoe.steam.enhanced.net.newCommunitySessionId
import cc.ptoe.steam.enhanced.net.requireAccessToken

/** Result of accepting a trade offer. */
data class TradeAcceptResult(
    val success: Boolean,
    val needsMobileConfirmation: Boolean = false,
    val message: String? = null,
)

/**
 * Trade offers for one account: list/count via the documented `IEconService` JSON
 * API (access-token auth), plus accept/decline/cancel via the community
 * `tradeoffer` endpoints (sessionid cookie/form auth).
 *
 * Ported from AVA's `core/protocol/trade_offers_client.dart`.
 */
class TradeOffersClient(private val api: SteamApiClient) {

    /**
     * Active (default) or historical offers, both directions, with descriptions
     * joined.
     */
    suspend fun fetch(account: SteamGuardAccount, historical: Boolean = false): TradeOffersPage {
        val token = requireAccessToken(account)
        val json = api.apiGetJson(
            "IEconService",
            "GetTradeOffers",
            mapOf(
                "get_received_offers" to "1",
                "get_sent_offers" to "1",
                "get_descriptions" to "1",
                "language" to api.steamLanguage,
                "active_only" to if (historical) "0" else "1",
                "historical_only" to if (historical) "1" else "0",
            ),
            accessToken = token,
        )
        val page = TradeOffersPage.fromResponse(JsonCoerce.asObject(json["response"]))
        dlog(
            "trade offers: ${page.received.size} received, " +
                "${page.sent.size} sent (historical=$historical)",
        )
        return page
    }

    /** Count of pending received offers (tab badge). */
    suspend fun pendingReceivedCount(account: SteamGuardAccount): Int {
        val token = requireAccessToken(account)
        val json = api.apiGetJson(
            "IEconService",
            "GetTradeOffersSummary",
            emptyMap(),
            accessToken = token,
        )
        val resp = JsonCoerce.asObject(json["response"])
        return JsonCoerce.asInt(resp["pending_received_count"])
    }

    /**
     * Partner display info via the community miniprofile endpoint (no auth).
     * Returns (personaName, avatarUrl); empty strings on failure.
     */
    suspend fun miniProfile(accountId: Long): Pair<String, String> = try {
        val json = api.communityGetJson("/miniprofile/$accountId/json", emptyMap())
        JsonCoerce.asString(json["persona_name"]) to JsonCoerce.asString(json["avatar_url"])
    } catch (_: Exception) {
        "" to ""
    }

    /**
     * Accepts a received offer.
     *
     * Steam replies `{tradeid, needs_mobile_confirmation}` on success or
     * `{strError}` on failure. A mobileconf (type 2) usually follows — the caller
     * routes the user to the confirmations tab.
     */
    suspend fun accept(account: SteamGuardAccount, offer: TradeOffer): TradeAcceptResult {
        requireAccessToken(account)
        val sid = newCommunitySessionId()
        val json = try {
            api.communityPostJson(
                "/tradeoffer/${offer.id}/accept",
                mapOf(
                    "sessionid" to sid,
                    "serverid" to "1",
                    "tradeofferid" to offer.id,
                    "partner" to offer.partnerSteamId.toString(),
                    "captcha" to "",
                ),
                cookies = communityCookies(account, sessionId = sid),
                referer = "${SteamApiClient.COMMUNITY_BASE}/tradeoffer/${offer.id}/",
            )
        } catch (_: CommunityAuthException) {
            // A stale session can also surface at the HTTP layer (401 or a redirect
            // to the login page) — same routing as the needauth JSON reply below.
            dlog("tradeoffer accept ${offer.id} -> needauth (http)")
            return TradeAcceptResult(success = false, message = "needauth")
        }
        if (JsonCoerce.asBool(json["needauth"]) || JsonCoerce.asBool(json["needsauth"])) {
            dlog("tradeoffer accept ${offer.id} -> needauth")
            return TradeAcceptResult(success = false, message = "needauth")
        }
        val err = JsonCoerce.asStringOrNull(json["strError"])
        // Success requires a positive marker: tradeid echo or the mobileconf flag.
        val ok = err == null &&
            (json.containsKey("tradeid") || JsonCoerce.asBool(json["needs_mobile_confirmation"]))
        dlog("tradeoffer accept ${offer.id} -> ok=$ok err=${err ?: "-"}")
        return TradeAcceptResult(
            success = ok,
            needsMobileConfirmation = JsonCoerce.asBool(json["needs_mobile_confirmation"]),
            message = err,
        )
    }

    /**
     * Declines a received offer / cancels a sent one.
     *
     * Success must be **positively confirmed** by Steam echoing
     * `{"tradeofferid":"..."}` — an empty body (expired session decoded as `{}`)
     * must NOT read as "declined": telling the user a still-active phishing offer
     * was declined is the worst failure mode for an authenticator app.
     */
    suspend fun decline(account: SteamGuardAccount, offerId: String): Boolean =
        writeOp(account, offerId, "decline")

    suspend fun cancel(account: SteamGuardAccount, offerId: String): Boolean =
        writeOp(account, offerId, "cancel")

    private suspend fun writeOp(
        account: SteamGuardAccount,
        offerId: String,
        op: String,
    ): Boolean {
        requireAccessToken(account)
        val sid = newCommunitySessionId()
        return try {
            val json = api.communityPostJson(
                "/tradeoffer/$offerId/$op",
                mapOf("sessionid" to sid),
                cookies = communityCookies(account, sessionId = sid),
                referer = "${SteamApiClient.COMMUNITY_BASE}/tradeoffer/$offerId/",
            )
            if (JsonCoerce.asBool(json["needauth"]) || JsonCoerce.asBool(json["needsauth"])) {
                return false
            }
            if (json["strError"] != null) return false
            if (json.containsKey("success") && !JsonCoerce.asBool(json["success"])) return false
            // Success must be positively confirmed by the offer-id echo.
            val ok = json["tradeofferid"] != null
            dlog("tradeoffer $op $offerId -> ${if (ok) "ok" else "no confirmation in reply"}")
            ok
        } catch (e: CommunityAuthException) {
            // HTTP-level stale session: propagate so the UI can point the user at
            // re-auth — swallowed as `false` it would read as a generic failure with
            // no sign-in cue, unlike accept()'s needauth mapping.
            throw e
        } catch (e: Exception) {
            dlog("tradeoffer $op $offerId failed: $e")
            false
        }
    }
}
