package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import kotlinx.serialization.json.JsonObject

/**
 * Trade offer models for `IEconService/GetTradeOffers`.
 *
 * Ported from AVA's `core/models/trade_offer.dart`.
 */

/** `trade_offer_state` values from IEconService/GetTradeOffers. */
enum class TradeOfferState {
    INVALID, // 1
    ACTIVE, // 2
    ACCEPTED, // 3
    COUNTERED, // 4
    EXPIRED, // 5
    CANCELED, // 6
    DECLINED, // 7
    INVALID_ITEMS, // 8
    NEEDS_CONFIRMATION, // 9
    CANCELED_BY_SECOND_FACTOR, // 10
    IN_ESCROW, // 11
    UNKNOWN,
}

private fun stateFrom(raw: Int?): TradeOfferState {
    val v = raw ?: return TradeOfferState.UNKNOWN
    return if (v in 1..11) TradeOfferState.entries[v - 1] else TradeOfferState.UNKNOWN
}

/** One asset in a trade offer, joined with its description (when present). */
data class TradeAsset(
    val appid: Int,
    val contextId: String,
    val assetId: String,
    val classId: String,
    val instanceId: String,
    val amount: Int,
    val name: String = "",
    val marketHashName: String = "",
    val iconUrl: String = "",
    /** Hex without '#', "" when absent. */
    val nameColor: String = "",
    val type: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject, descByKey: Map<String, JsonObject>): TradeAsset {
            // classid is only unique within one app — a cross-game offer (CS2 +
            // TF2 mixed is common) must join on appid, or it renders the wrong
            // name / icon / rarity (same key node-steam-tradeoffer-manager uses).
            val key = "${JsonCoerce.asString(json["appid"])}_" +
                "${JsonCoerce.asString(json["classid"])}_" +
                JsonCoerce.asString(json["instanceid"])
            val d = descByKey[key]
            return TradeAsset(
                appid = JsonCoerce.asInt(json["appid"]),
                contextId = JsonCoerce.asString(json["contextid"]),
                assetId = JsonCoerce.asString(json["assetid"]),
                classId = JsonCoerce.asString(json["classid"]),
                instanceId = JsonCoerce.asString(json["instanceid"]),
                amount = JsonCoerce.asInt(json["amount"]),
                name = d?.let { JsonCoerce.asString(it["name"]) } ?: "",
                marketHashName = d?.let { JsonCoerce.asString(it["market_hash_name"]) } ?: "",
                iconUrl = d?.let { SteamItems.imageUrl(JsonCoerce.asStringOrNull(it["icon_url"])) } ?: "",
                nameColor = d?.let { JsonCoerce.asString(it["name_color"]) } ?: "",
                type = d?.let { JsonCoerce.asString(it["type"]) } ?: "",
            )
        }
    }
}

/** A trade offer as returned by IEconService/GetTradeOffers. */
data class TradeOffer(
    val id: String,
    val partnerAccountId: Long,
    val message: String,
    val state: TradeOfferState,
    val isOurOffer: Boolean,
    val itemsToGive: List<TradeAsset>,
    val itemsToReceive: List<TradeAsset>,
    val timeCreated: Long,
    val timeUpdated: Long,
    val expirationTime: Long,
    val escrowEndDate: Long,
) {
    val partnerSteamId: Long
        get() = STEAM_ID64_BASE + partnerAccountId

    /**
     * The partner gives without asking anything of us.
     *
     * Phishing offers often disguise themselves as gifts — the UI must not weaken
     * the accept gate because of this.
     */
    val isGift: Boolean
        get() = itemsToGive.isEmpty() && itemsToReceive.isNotEmpty()

    /** We only give, receive nothing — red warning. */
    val isOneSidedGive: Boolean
        get() = itemsToReceive.isEmpty() && itemsToGive.isNotEmpty()

    companion object {
        /** SteamID64 = accountid + this constant (individual/public/desktop). */
        const val STEAM_ID64_BASE: Long = 76561197960265728L

        fun fromJson(json: JsonObject, descByKey: Map<String, JsonObject>): TradeOffer {
            fun assets(element: kotlinx.serialization.json.JsonElement?): List<TradeAsset> =
                JsonCoerce.asObjectArray(element).map { TradeAsset.fromJson(it, descByKey) }

            return TradeOffer(
                id = JsonCoerce.asString(json["tradeofferid"]),
                partnerAccountId = JsonCoerce.asLongOrNull(json["accountid_other"]) ?: 0L,
                message = JsonCoerce.asString(json["message"]),
                state = stateFrom(JsonCoerce.asLongOrNull(json["trade_offer_state"])?.toInt()),
                isOurOffer = JsonCoerce.asBool(json["is_our_offer"]),
                itemsToGive = assets(json["items_to_give"]),
                itemsToReceive = assets(json["items_to_receive"]),
                timeCreated = JsonCoerce.asLongOrNull(json["time_created"]) ?: 0L,
                timeUpdated = JsonCoerce.asLongOrNull(json["time_updated"]) ?: 0L,
                expirationTime = JsonCoerce.asLongOrNull(json["expiration_time"]) ?: 0L,
                escrowEndDate = JsonCoerce.asLongOrNull(json["escrow_end_date"]) ?: 0L,
            )
        }
    }
}

/** The parsed `response` object of GetTradeOffers. */
data class TradeOffersPage(
    val received: List<TradeOffer>,
    val sent: List<TradeOffer>,
) {
    companion object {
        fun fromResponse(response: JsonObject): TradeOffersPage {
            val descByKey = mutableMapOf<String, JsonObject>()
            for (d in JsonCoerce.asObjectArray(response["descriptions"])) {
                val key = "${JsonCoerce.asString(d["appid"])}_" +
                    "${JsonCoerce.asString(d["classid"])}_" +
                    JsonCoerce.asString(d["instanceid"])
                descByKey[key] = d
            }
            fun offers(key: String): List<TradeOffer> =
                JsonCoerce.asObjectArray(response[key]).map { TradeOffer.fromJson(it, descByKey) }
            return TradeOffersPage(
                received = offers("trade_offers_received"),
                sent = offers("trade_offers_sent"),
            )
        }
    }
}
