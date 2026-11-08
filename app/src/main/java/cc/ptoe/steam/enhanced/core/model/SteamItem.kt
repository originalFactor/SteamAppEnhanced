package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import kotlinx.serialization.json.JsonObject

/**
 * Steam economy models for the inventory + market feature.
 *
 * Ported from AVA's `core/models/steam_item.dart`.
 */
object SteamItems {
    private const val IMAGE_BASE = "https://community.fastly.steamstatic.com/economy/image/"

    /** Turns an economy icon hash into a full CDN URL; "" when absent. */
    fun imageUrl(iconUrl: String?): String =
        if (iconUrl.isNullOrEmpty()) "" else "$IMAGE_BASE$iconUrl"
}

/** Live wallet / fee constants parsed from `g_rgWalletInfo` on a community page. */
data class WalletInfo(
    /** `wallet_currency` (23 = CNY, 1 = USD, …). */
    val currency: Int,
    /** `wallet_fee_percent` (e.g. 0.05). */
    val steamFeePct: Double,
    /** `wallet_publisher_fee_percent_default` (e.g. 0.10). */
    val publisherFeePct: Double,
    /** `wallet_market_minimum`, in minor units. */
    val marketMinimum: Int,
    /** `wallet_currency_increment`. */
    val currencyIncrement: Int,
) {
    companion object {
        /** Safe default (USD-like) used until the real wallet is fetched. */
        val FALLBACK = WalletInfo(
            currency = 1,
            steamFeePct = 0.05,
            publisherFeePct = 0.10,
            marketMinimum = 1,
            currencyIncrement = 1,
        )

        /** Parses the `g_rgWalletInfo` object (values arrive as strings). */
        fun fromJson(json: JsonObject): WalletInfo {
            val increment = JsonCoerce.asInt(json["wallet_currency_increment"])
            return WalletInfo(
                currency = JsonCoerce.asInt(json["wallet_currency"]),
                steamFeePct = JsonCoerce.asDoubleOrNull(json["wallet_fee_percent"]) ?: 0.0,
                publisherFeePct =
                    JsonCoerce.asDoubleOrNull(json["wallet_publisher_fee_percent_default"]) ?: 0.0,
                marketMinimum = JsonCoerce.asInt(json["wallet_market_minimum"]),
                // A zero/negative increment would divide-by-zero the fee math.
                currencyIncrement = if (increment < 1) 1 else increment,
            )
        }
    }
}

/** A game + inventory context the account has items in (the game picker). */
data class InventoryGame(
    val appid: Int,
    val contextId: String,
    val name: String,
    /** Full URL. */
    val iconUrl: String,
    val itemCount: Int,
)

/** One inventory item (asset merged with its description). */
data class InventoryItem(
    val appid: Int,
    val contextId: String,
    val assetId: String,
    val classId: String,
    val instanceId: String,
    val amount: Int,
    val marketHashName: String,
    val name: String,
    val type: String,
    /** Full URL. */
    val iconUrl: String,
    val marketable: Boolean,
    val tradable: Boolean,
    /** Per-item fee override, if the description carries one. */
    val publisherFeePct: Double? = null,
)

/** The account's own active market listing. */
data class MarketListing(
    val listingId: String,
    val appid: Int,
    val marketHashName: String,
    val name: String,
    /** Full URL. */
    val iconUrl: String,
    /** What buyers pay, in minor units. */
    val buyerPrice: Int,
    /** Total fee, in minor units. */
    val fee: Int,
    val createdAt: Long,
    val active: Boolean,
) {
    /** Amount the seller receives = buyer price − fee. */
    val sellerReceives: Int
        get() = buyerPrice - fee

    companion object {
        fun fromJson(json: JsonObject): MarketListing {
            val asset = JsonCoerce.asObject(json["asset"])
            val name = JsonCoerce.asString(asset["name"]).ifEmpty {
                JsonCoerce.asString(asset["market_name"])
            }
            return MarketListing(
                listingId = JsonCoerce.asString(json["listingid"]),
                appid = JsonCoerce.asInt(asset["appid"]),
                marketHashName = JsonCoerce.asString(asset["market_hash_name"]),
                name = name,
                iconUrl = SteamItems.imageUrl(JsonCoerce.asStringOrNull(asset["icon_url"])),
                buyerPrice = JsonCoerce.asInt(json["price"]),
                fee = JsonCoerce.asInt(json["fee"]),
                createdAt = JsonCoerce.asLongOrNull(json["time_created"]) ?: 0L,
                active = JsonCoerce.asInt(json["active"]) == 1,
            )
        }
    }
}

/** Market price reference from `priceoverview` (localized strings). */
data class MarketPrice(
    val lowest: String? = null,
    val median: String? = null,
    val volume: Long? = null,
) {
    companion object {
        fun fromJson(json: JsonObject): MarketPrice = MarketPrice(
            lowest = JsonCoerce.asStringOrNull(json["lowest_price"]),
            median = JsonCoerce.asStringOrNull(json["median_price"]),
            volume = JsonCoerce.asLongOrNull(json["volume"]),
        )
    }
}
