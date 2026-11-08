package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.SteamTotp
import cc.ptoe.steam.enhanced.core.model.Confirmation
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.net.CommunityAuthException
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamTime
import cc.ptoe.steam.enhanced.net.communityCookies
import kotlinx.coroutines.CancellationException

/** Steam answered a mobileconf call and the session needs re-auth. */
class ConfirmationAuthException :
    Exception("ConfirmationAuthException: session needs re-auth")

/**
 * Steam answered a mobileconf call with `success=false` but no auth flag: the
 * request reached Steam and was rejected at the signature level (mismatched
 * identity_secret / device_id, or heavy clock drift).
 *
 * Retrying with the same secrets cannot succeed. [message] is Steam's own
 * (localized) error text, when present.
 */
class ConfirmationRejectedException(message: String) :
    Exception("ConfirmationRejectedException: $message")

/** Result of a batch operation: how many succeeded and how many did not. */
data class BatchResult(val ok: Int, val failed: Int)

/**
 * Fetches and acts on Steam mobile confirmations (`steamcommunity.com/mobileconf`).
 *
 * Ported from AVA's `core/protocol/confirmations_client.dart`. Pure JSON protocol
 * (no WebView): `getlist` returns the confirmation array, `ajaxop` /
 * `multiajaxop` accept or reject.
 *
 * All requests are signed with the account's `identity_secret` per the
 * time + tag scheme — see [SteamTotp.generateConfirmationHash].
 */
class ConfirmationsClient(
    private val api: SteamApiClient,
    private val steamTime: SteamTime = SteamTime.shared,
) {
    /**
     * Fetches the account's pending confirmations.
     *
     * A signature-level rejection throws [ConfirmationRejectedException] rather
     * than returning an empty list: rendering a hard rejection as "no pending
     * confirmations" would hide the fact that the stored secrets don't match the
     * account.
     */
    @Throws(
        ConfirmationAuthException::class,
        ConfirmationRejectedException::class,
    )
    suspend fun fetch(account: SteamGuardAccount): List<Confirmation> {
        val time = steamTime.currentSteamTime()
        val query = baseQuery(account, time, "list").toMutableMap()
        query["tag"] = "list"
        // Localizes headline/summary/type_name; not part of the signed hash.
        query["l"] = api.steamLanguage

        val json = try {
            api.communityGetJson(
                "/mobileconf/getlist",
                query,
                cookies = communityCookies(account, confirmationVariant = true),
            )
        } catch (_: CommunityAuthException) {
            // HTTP-level session rejection == needauth: same re-auth path.
            throw ConfirmationAuthException()
        }

        if (!JsonCoerce.asBool(json["success"])) {
            if (JsonCoerce.asBool(json["needauth"]) || JsonCoerce.asBool(json["needsauth"])) {
                throw ConfirmationAuthException()
            }
            // A signature-level rejection ("Oh no!") — the identity_secret /
            // device_id does not match the account's registered authenticator, or
            // the clock is far off.
            val message = JsonCoerce.asString(json["message"]).ifEmpty {
                JsonCoerce.asString(json["detail"])
            }
            throw ConfirmationRejectedException(message)
        }
        return JsonCoerce.asObjectArray(json["conf"]).map { Confirmation.fromJson(it) }
    }

    /**
     * Accepts or rejects a single confirmation. [accept] true = allow.
     *
     * Steam signs accept/deny with `tag == op` (`allow` / `cancel`), not
     * `accept` / `reject`.
     */
    suspend fun respond(
        account: SteamGuardAccount,
        confirmation: Confirmation,
        accept: Boolean,
    ): Boolean {
        val time = steamTime.currentSteamTime()
        val op = if (accept) "allow" else "cancel"
        val query = baseQuery(account, time, op).toMutableMap()
        query["tag"] = op
        query["op"] = op
        query["cid"] = confirmation.id
        query["ck"] = confirmation.nonce
        val json = api.communityPostJson(
            "/mobileconf/ajaxop",
            query,
            cookies = communityCookies(account, confirmationVariant = true),
        )
        return JsonCoerce.asBool(json["success"])
    }

    /**
     * Batch accept/reject.
     *
     * Tries `multiajaxop`; on an explicit unsuccessful response falls back to per-item [respond] and reports
     * aggregate success/failure.
     */
    suspend fun respondMultiple(
        account: SteamGuardAccount,
        confirmations: List<Confirmation>,
        accept: Boolean,
    ): BatchResult {
        if (confirmations.isEmpty()) return BatchResult(0, 0)
        val time = steamTime.currentSteamTime()
        val op = if (accept) "allow" else "cancel"
        val base = baseQuery(account, time, op).toMutableMap()
        base["tag"] = op
        base["op"] = op

        // multiajaxop expects `cid[]` / `ck[]` sent once per confirmation — repeated
        // form keys, which a Map cannot express, so the form is a pair list.
        val form = buildList {
            base.forEach { (k, v) -> add(k to v) }
            confirmations.forEach { add("cid[]" to it.id) }
            confirmations.forEach { add("ck[]" to it.nonce) }
        }

        val batchJson = try {
            api.communityPostForm(
                "/mobileconf/multiajaxop",
                form,
                cookies = communityCookies(account, confirmationVariant = true),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CommunityAuthException) {
            throw ConfirmationAuthException()
        }
        if (JsonCoerce.asBool(batchJson["success"])) {
            return BatchResult(confirmations.size, 0)
        }

        // Fall back to per-item, which is slower but always expressible.
        var ok = 0
        var failed = 0
        for (c in confirmations) {
            try {
                if (respond(account, c, accept)) ok++ else failed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed++
            }
        }
        return BatchResult(ok, failed)
    }

    /**
     * The signed base query every mobileconf call carries.
     *
     * `p` is the device id, `a` the steamid, `k` the confirmation hash, `t` the
     * server time and `m` the client marker.
     */
    private fun baseQuery(
        account: SteamGuardAccount,
        time: Long,
        tag: String,
    ): Map<String, String> {
        val hash = SteamTotp.generateConfirmationHash(
            time,
            tag,
            account.identitySecret.orEmpty(),
        )
        // An empty device id is always rejected; a maFile that lost its
        // `device_id` field falls back to the deterministic SteamID-derived one.
        val deviceId = account.deviceId
        val p = if (deviceId.isNullOrEmpty()) {
            SteamTotp.generateDeviceId(account.steamId)
        } else {
            deviceId
        }
        return mapOf(
            "p" to p,
            "a" to account.steamId.toString(),
            "k" to hash,
            "t" to time.toString(),
            "m" to "react",
        )
    }
}
