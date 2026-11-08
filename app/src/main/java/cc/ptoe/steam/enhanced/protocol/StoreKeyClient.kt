package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.newCommunitySessionId
import cc.ptoe.steam.enhanced.net.requireAccessToken
import cc.ptoe.steam.enhanced.net.storeCookies
import kotlinx.serialization.json.JsonObject

/**
 * Why Steam refused a product key, derived from `purchase_result_details`.
 *
 * The numbers are `EPurchaseResultDetail` (SteamKit's `SteamLanguage.cs`); the
 * subset named here is exactly the one Valve's own `registerkey.js` has distinct
 * copy for.
 *
 * Everything else stays [UNKNOWN] so the UI can show the raw number instead of
 * inventing a reason — guessing wrong is worse than saying "Steam rejected it
 * (code N)", because the user acts on the advice.
 */
enum class KeyRedeemError {
    /** `BadActivationCode` (14) — a typo, or not a Steam key at all. */
    INVALID_KEY,

    /** `AlreadyPurchased` (9) — this account already owns the product. */
    ALREADY_OWNED,

    /** `DuplicateActivationCode` (15) — already activated, here or elsewhere. */
    ALREADY_ACTIVATED,

    /** `RestrictedCountry` (13) — not activatable in the account's country. */
    REGION_LOCKED,

    /** `DoesNotOwnRequiredApp` (24) — DLC whose base product the account lacks. */
    NEEDS_BASE_PRODUCT,

    /** `MustLoginPS3AppForPurchase` (36) — must be played on a PS3 first. */
    NEEDS_PS3_LOGIN,

    /** `RateLimited` (53) — too many recent *failed* attempts; blocked ~1 hour. */
    RATE_LIMITED,

    /** Refused for a reason without specific copy; show the raw detail verbatim. */
    UNKNOWN,
}

/** Outcome of activating one product key. */
data class KeyRedeemResult(
    /** Steam accepted the key and the products are on the account now. */
    val success: Boolean,
    /** Raw `purchase_result_details` (0 on success); kept for the debug log. */
    val detail: Int,
    val error: KeyRedeemError? = null,
    /** Names of the packages the key granted; may be empty even on success. */
    val products: List<String> = emptyList(),
) {
    companion object {
        /** Maps `purchase_result_details` to the reason enum. Pure, so it is testable. */
        fun errorFor(detail: Int): KeyRedeemError = when (detail) {
            9 -> KeyRedeemError.ALREADY_OWNED
            13 -> KeyRedeemError.REGION_LOCKED
            14 -> KeyRedeemError.INVALID_KEY
            15 -> KeyRedeemError.ALREADY_ACTIVATED
            24 -> KeyRedeemError.NEEDS_BASE_PRODUCT
            36 -> KeyRedeemError.NEEDS_PS3_LOGIN
            53 -> KeyRedeemError.RATE_LIMITED
            else -> KeyRedeemError.UNKNOWN
        }

        /**
         * Parses an `/account/ajaxregisterkey/` reply.
         *
         * Success is `success == 1`, which is exactly what Valve's own
         * `registerkey.js` branches on: it renders the receipt when the flag is 1
         * and only reads `purchase_result_details` on the failure branch. Requiring
         * a zero detail *as well* would be stricter than Steam's own client and
         * could report a key that really was activated as failed — sending the user
         * to burn a second attempt on a key they now own.
         *
         * An empty/undecodable body has no `success` at all and so falls through to
         * a failure, which is right: there is no receipt to show, and claiming an
         * activation that may not have happened is the worse error.
         */
        fun parse(json: JsonObject): KeyRedeemResult {
            val receipt = json["purchase_receipt_info"] as? JsonObject
            // `result_detail` is the protobuf spelling; web captures show
            // `resultdetail`. The store's JSON layer and the protobuf don't agree on
            // it, so accept both rather than silently read -1.
            val detail = sequenceOf(
                JsonCoerce.asLongOrNull(json["purchase_result_details"]),
                JsonCoerce.asLongOrNull(receipt?.get("result_detail")),
                JsonCoerce.asLongOrNull(receipt?.get("resultdetail")),
            ).firstOrNull { it != null }?.toInt() ?: -1

            if (JsonCoerce.asLongOrNull(json["success"]) != 1L) {
                return KeyRedeemResult(
                    success = false,
                    detail = detail,
                    error = errorFor(detail),
                )
            }

            val products = mutableListOf<String>()
            for (item in JsonCoerce.asObjectArray(receipt?.get("line_items"))) {
                // `line_item_description`: what registerkey.js reads, what the
                // protobuf calls field 3, and what production actually sends.
                val name = JsonCoerce.asString(item["line_item_description"]).trim()
                if (name.isNotEmpty()) products.add(name)
            }
            return KeyRedeemResult(success = true, detail = detail, products = products)
        }
    }
}

/**
 * Activates Steam product keys (CD keys) on an account —
 * `store.steampowered.com/account/ajaxregisterkey/`.
 *
 * Ported from AVA's `core/protocol/store_key_client.dart`. This is the same
 * endpoint the store's own "Activate a Product on Steam" page posts to, and the
 * request shape is taken from Valve's own
 * `store.steampowered.com/public/javascript/registerkey.js`.
 *
 * The *nicer* path — `IStoreService/RegisterCDKey` — is deliberately not used: it
 * is a unified message reachable only over a CM connection, and `GetSupportedAPIList`
 * does not publish it on `api.steampowered.com`. This app is a Web-API-only client,
 * so the store's own ajax endpoint is the only path available.
 *
 * **Activation is irreversible** — a consumed key cannot be un-consumed, and Steam
 * blocks further attempts for about an hour after a handful of failed ones. The UI
 * must confirm before calling [redeem] and must not retry automatically on a
 * rejection.
 */
class StoreKeyClient(private val api: SteamApiClient) {

    /**
     * Activates [productKey] on [account].
     *
     * Throws [cc.ptoe.steam.enhanced.net.MissingAccessTokenException] when the
     * session cannot authenticate, and
     * [cc.ptoe.steam.enhanced.net.CommunityAuthException] when the store bounces to
     * the login page (a stale cookie) — both mean "re-auth and try again", and both
     * must be distinguished from a key Steam actually rejected.
     */
    suspend fun redeem(account: SteamGuardAccount, productKey: String): KeyRedeemResult {
        requireAccessToken(account)
        val key = normalize(productKey)
        // Load the activation page first. This is not politeness — the store
        // Set-Cookies `steamCountry` / `browserid` on first contact and answers with
        // a 302 back to the same URL, so a POST sent straight at the ajax endpoint
        // never runs. The GET is safe to repeat and consumes nothing.
        val jar = api.storeBootstrap(
            "/account/registerkey",
            storeCookies(account, sessionId = newCommunitySessionId()),
        )
        // Whatever `sessionid` the bootstrap ended up with — Steam's if it issued
        // one, ours otherwise — has to appear in the form body too.
        val sid = jar["sessionid"] ?: newCommunitySessionId()
        jar["sessionid"] = sid
        val json = api.storePostJson(
            "/account/ajaxregisterkey/",
            mapOf("product_key" to key, "sessionid" to sid),
            cookies = jar,
            referer = "${SteamApiClient.STORE_BASE}/account/registerkey",
        )
        val result = KeyRedeemResult.parse(json)
        // The key itself is a credential — log the outcome, never the value.
        dlog(
            "registerkey -> success=${result.success} detail=${result.detail} " +
                "products=${result.products.size}",
        )
        return result
    }

    companion object {
        /**
         * The form value Steam expects for [raw]: uppercased with all whitespace
         * removed.
         *
         * Punctuation is left exactly as typed — key layouts vary (15-char triplets,
         * 25-char quintuplets, and Valve's own dashless promo codes), so "helpfully"
         * re-grouping the dashes would corrupt shapes we don't know about. Steam is
         * the only authority on the format.
         */
        fun normalize(raw: String): String =
            raw.replace(Regex("\\s+"), "").uppercase()
    }
}
