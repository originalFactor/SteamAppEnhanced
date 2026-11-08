package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import kotlinx.serialization.json.JsonObject

/**
 * A pending mobile confirmation as returned by `/mobileconf/getlist`.
 *
 * Ported from AVA's `core/models/confirmation.dart`. The [nonce] is the
 * confirmation key (`ck`) that must be echoed back with [id] to act on it.
 */
enum class ConfirmationType {
    UNKNOWN,
    TRADE,
    MARKET_LISTING,
    FEATURE_OPT_OUT,
    PHONE_CHANGE,
    ACCOUNT_RECOVERY,
    API_KEY,
    FAMILY_JOIN,
    OTHER,
}

data class Confirmation(
    val id: String,
    val nonce: String,
    val type: ConfirmationType,
    val typeName: String,
    val creatorId: String,
    val headline: String,
    val summary: List<String>,
    val creationTime: Long,
    val icon: String,
) {
    companion object {
        /**
         * Steam confirmation type ids (matching steamguard-cli's
         * `ConfirmationType`): 1 generic, 2 trade, 3 market listing, 4 feature
         * opt-out, 5 phone number change, 6 account recovery, 9 web API key
         * creation, 11 join Steam family.
         */
        private fun mapType(raw: Int): ConfirmationType = when (raw) {
            1 -> ConfirmationType.OTHER
            2 -> ConfirmationType.TRADE
            3 -> ConfirmationType.MARKET_LISTING
            4 -> ConfirmationType.FEATURE_OPT_OUT
            5 -> ConfirmationType.PHONE_CHANGE
            6 -> ConfirmationType.ACCOUNT_RECOVERY
            9 -> ConfirmationType.API_KEY
            11 -> ConfirmationType.FAMILY_JOIN
            else -> ConfirmationType.UNKNOWN
        }

        fun fromJson(json: JsonObject): Confirmation = Confirmation(
            id = JsonCoerce.asString(json["id"]),
            nonce = JsonCoerce.asString(json["nonce"]),
            type = mapType(JsonCoerce.asInt(json["type"])),
            typeName = JsonCoerce.asString(json["type_name"]),
            creatorId = JsonCoerce.asString(json["creator_id"]),
            headline = JsonCoerce.asString(json["headline"]),
            summary = JsonCoerce.asArray(json["summary"]).map { JsonCoerce.asString(it) },
            creationTime = JsonCoerce.asLongOrNull(json["creation_time"]) ?: 0L,
            icon = JsonCoerce.asString(json["icon"]),
        )
    }
}
