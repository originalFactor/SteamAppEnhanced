package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.InventoryGame
import cc.ptoe.steam.enhanced.core.model.InventoryItem
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.model.SteamItems
import cc.ptoe.steam.enhanced.core.model.WalletInfo
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.communityCookies
import cc.ptoe.steam.enhanced.net.CommunityAuthException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class InventoryOverview(val games: List<InventoryGame>, val wallet: WalletInfo?)
data class InventoryPage(val items: List<InventoryItem>, val cursor: String?)
enum class InventoryFailure { PAGE_UNAVAILABLE, PRIVATE, ITEMS_UNAVAILABLE }
class InventoryLoadException(val reason: InventoryFailure) : Exception(reason.name)

class InventoryClient(private val api: SteamApiClient) {
    suspend fun overview(account: SteamGuardAccount): InventoryOverview {
        val path = "/profiles/${account.steamId}/inventory/"
        val html = api.communityGetText(path, query = mapOf("l" to api.steamLanguage), cookies = communityCookies(account))
        // Steam can serve an anonymous/private page with HTTP 200 instead of a
        // login redirect. Let SteamServices refresh the session once in that case.
        if (Regex("""\bg_steamID\s*=\s*(?:false|null)\s*;""").containsMatchIn(html)) {
            throw CommunityAuthException(200, path)
        }
        val apps = embeddedObject(html, "g_rgAppContextData")
            ?: throw InventoryLoadException(if (html.contains("profile_private_info")) InventoryFailure.PRIVATE else InventoryFailure.PAGE_UNAVAILABLE)
        val games = buildList {
            for ((id, value) in apps) {
                val appId = id.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val app = value as? JsonObject ?: continue
                for ((contextId, context) in JsonCoerce.asObject(app["rgContexts"])) {
                    if (contextId.toULongOrNull() == null) continue
                    val count = JsonCoerce.asInt(JsonCoerce.asObject(context)["asset_count"])
                    if (count <= 0) continue
                    add(InventoryGame(appId, contextId, JsonCoerce.asString(app["name"]),
                        JsonCoerce.asString(app["icon"]), count))
                }
            }
        }
        val wallet = embeddedObject(html, "g_rgWalletInfo")?.let(WalletInfo::fromJson)?.takeIf { it.currency > 0 }
        return InventoryOverview(games.sortedByDescending(InventoryGame::itemCount), wallet)
    }

    suspend fun items(account: SteamGuardAccount, game: InventoryGame, cursor: String? = null): InventoryPage {
        val json = api.communityGetJson("/inventory/${account.steamId}/${game.appid}/${game.contextId}", buildMap {
            put("l", api.steamLanguage)
            put("count", "75")
            cursor?.let { put("start_assetid", it) }
        }, communityCookies(account))
        if (JsonCoerce.asBool(json["needauth"])) throw CommunityAuthException(200, "/inventory")
        if (JsonCoerce.asString(json["success"]).lowercase() !in setOf("1", "true")) {
            val message = JsonCoerce.asString(json["Error"]).ifBlank { JsonCoerce.asString(json["error"]) }
            throw InventoryLoadException(if (message.contains("private", ignoreCase = true)) InventoryFailure.PRIVATE else InventoryFailure.ITEMS_UNAVAILABLE)
        }
        val descriptions = JsonCoerce.asObjectArray(json["descriptions"]).associateBy(::classKey)
        val items = JsonCoerce.asObjectArray(json["assets"]).mapNotNull { asset ->
            val description = descriptions[classKey(asset)] ?: return@mapNotNull null
            val assetId = JsonCoerce.asString(asset["assetid"]).takeIf { it.toULongOrNull() != null }
                ?: return@mapNotNull null
            InventoryItem(
                appid = game.appid,
                contextId = game.contextId,
                assetId = assetId,
                classId = JsonCoerce.asString(asset["classid"]),
                instanceId = JsonCoerce.asString(asset["instanceid"]),
                amount = JsonCoerce.asInt(asset["amount"]),
                marketHashName = JsonCoerce.asString(description["market_hash_name"]),
                name = JsonCoerce.asString(description["name"]),
                type = JsonCoerce.asString(description["type"]),
                iconUrl = SteamItems.imageUrl(JsonCoerce.asStringOrNull(description["icon_url"])),
                marketable = JsonCoerce.asBool(description["marketable"]),
                tradable = JsonCoerce.asBool(description["tradable"]),
                publisherFeePct = JsonCoerce.asDoubleOrNull(description["market_fee"]),
            )
        }
        val next = JsonCoerce.asString(json["last_assetid"]).takeIf {
            JsonCoerce.asBool(json["more_items"]) && it != cursor && it.toULongOrNull() != null
        }
        return InventoryPage(items, next)
    }

    companion object {
        private fun classKey(json: JsonObject) =
            JsonCoerce.asString(json["classid"]) to JsonCoerce.asString(json["instanceid"])

        internal fun embeddedObject(html: String, variable: String): JsonObject? {
            val start = Regex("""\b${Regex.escape(variable)}\s*=\s*""").find(html)?.range?.last?.plus(1) ?: return null
            val opening = html.getOrNull(start)
            if (opening != '{' && opening != '[') return null
            val closing = if (opening == '{') '}' else ']'
            var depth = 0
            var quote = false
            var escaped = false
            for (i in start until html.length) {
                val char = html[i]
                when {
                    escaped -> escaped = false
                    quote && char == '\\' -> escaped = true
                    char == '"' -> quote = !quote
                    !quote && char == opening -> depth++
                    !quote && char == closing -> if (--depth == 0) {
                        val value = runCatching { JsonCoerce.json.parseToJsonElement(html.substring(start, i + 1)) }.getOrNull()
                        // Steam serializes empty associative lists as [] on some pages.
                        return value as? JsonObject ?: if (value is JsonArray && value.isEmpty()) JsonObject(emptyMap()) else null
                    }
                }
            }
            return null
        }
    }
}
