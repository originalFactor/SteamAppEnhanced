package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.InventoryItem
import cc.ptoe.steam.enhanced.core.model.MarketListing
import cc.ptoe.steam.enhanced.core.model.MarketPrice
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.communityCookies
import cc.ptoe.steam.enhanced.net.newCommunitySessionId

data class MarketSale(val success: Boolean, val confirmation: Boolean, val listingId: String?, val message: String?)

class MarketClient(private val api: SteamApiClient) {
    suspend fun price(item: InventoryItem, currency: Int): MarketPrice? {
        val json = api.communityGetJson("/market/priceoverview/", mapOf(
            "appid" to item.appid.toString(), "currency" to currency.toString(), "market_hash_name" to item.marketHashName,
        ))
        return if (JsonCoerce.asBool(json["success"])) MarketPrice.fromJson(json) else null
    }

    suspend fun priceHistory(account: SteamGuardAccount, item: InventoryItem): List<Double> {
        val json = api.communityGetJson("/market/pricehistory/", mapOf(
            "appid" to item.appid.toString(), "market_hash_name" to item.marketHashName,
        ), communityCookies(account))
        return if (!JsonCoerce.asBool(json["success"])) emptyList()
        else JsonCoerce.asArray(json["prices"]).takeLast(60).mapNotNull {
            JsonCoerce.asDoubleOrNull(JsonCoerce.asArray(it).getOrNull(1))?.takeIf(Double::isFinite)
        }
    }

    suspend fun sell(account: SteamGuardAccount, item: InventoryItem, receive: Int): MarketSale {
        require(item.marketable && item.amount > 0 && receive > 0)
        val sessionId = newCommunitySessionId()
        val json = api.communityPostJson("/market/sellitem/", mapOf(
            "sessionid" to sessionId, "appid" to item.appid.toString(), "contextid" to item.contextId,
            "assetid" to item.assetId, "amount" to "1", "price" to receive.toString(),
        ), communityCookies(account, sessionId = sessionId), "${SteamApiClient.COMMUNITY_BASE}/profiles/${account.steamId}/inventory")
        return MarketSale(
            JsonCoerce.asBool(json["success"]),
            JsonCoerce.asBool(json["requires_confirmation"]) || JsonCoerce.asBool(json["needs_mobile_confirmation"]),
            JsonCoerce.asStringOrNull(json["listingid"])?.takeIf { it.toULongOrNull() != null },
            JsonCoerce.asStringOrNull(json["message"]),
        )
    }

    suspend fun listings(account: SteamGuardAccount, start: Int = 0): Pair<List<MarketListing>, Boolean> {
        val json = api.communityGetJson("/market/mylistings/", mapOf(
            "norender" to "1", "count" to "100", "start" to start.toString(), "l" to api.steamLanguage,
        ), communityCookies(account))
        check(JsonCoerce.asBool(json["success"]) || json.containsKey("listings")) { "Steam did not return active listings." }
        val raw = JsonCoerce.asObjectArray(json["listings"])
        return raw.map(MarketListing::fromJson).filter(MarketListing::active) to
            (raw.isNotEmpty() && start + 100 < JsonCoerce.asInt(json["total_count"]))
    }

    suspend fun cancel(account: SteamGuardAccount, id: String): Boolean {
        require(id.toULongOrNull() != null)
        val sessionId = newCommunitySessionId()
        val json = api.communityPostJson("/market/removelisting/$id", mapOf("sessionid" to sessionId),
            communityCookies(account, sessionId = sessionId), "${SteamApiClient.COMMUNITY_BASE}/market/")
        return !JsonCoerce.asBool(json["needauth"]) && !JsonCoerce.asBool(json["needsauth"]) &&
            (!json.containsKey("success") || JsonCoerce.asBool(json["success"]))
    }
}
