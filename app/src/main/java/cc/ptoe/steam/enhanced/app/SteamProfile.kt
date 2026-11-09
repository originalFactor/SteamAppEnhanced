package cc.ptoe.steam.enhanced.app

import android.util.Xml
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamApiClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

internal object SteamProfile {
    private const val ITEMS = "https://cdn.fastly.steamstatic.com/steamcommunity/public/images/"

    suspend fun refresh(api: SteamApiClient, account: SteamGuardAccount): SteamGuardAccount {
        val xml = api.communityGetText("/profiles/${account.steamId}", mapOf("xml" to "1"))
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        var persona: String? = null
        var full: String? = null
        var medium: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "steamID" -> persona = parser.nextText().trim().take(128)
                "avatarFull" -> full = parser.nextText()
                "avatarMedium" -> medium = parser.nextText()
            }
            parser.next()
        }
        var animated = account.animatedAvatarUrl
        var frame = account.avatarFrameUrl
        if (account.session.hasAccessToken) try {
            val response = api.apiGetJson("IPlayerService", "GetProfileItemsEquipped", mapOf(
                "steamid" to account.steamId.toString(),
                "access_token" to account.session.accessToken.orEmpty(),
                "language" to api.steamLanguage,
            ))
            val items = response["response"] as? JsonObject
                ?: error("Steam returned an incomplete equipped-profile response.")
            fun item(key: String): String? {
                val value = JsonCoerce.asObject(items[key])
                val path = JsonCoerce.asStringOrNull(value["image_small"])
                    ?: JsonCoerce.asStringOrNull(value["image_large"]) ?: return null
                val url = if (path.startsWith("https://")) path
                else ITEMS + path.trimStart('/').removePrefix("images/")
                return imageUrl(url)?.toString()
            }
            animated = item("animated_avatar")
            frame = item("avatar_frame")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Equipped items are optional; keep the public nickname/avatar even
            // when Steam cannot return the animated avatar or frame.
            dlog("Equipped profile items unavailable: ${failure.javaClass.simpleName}")
        }
        return account.copy(
            personaName = persona?.takeIf(String::isNotBlank) ?: account.personaName,
            avatarUrl = (imageUrl(full) ?: imageUrl(medium))?.toString() ?: account.avatarUrl,
            animatedAvatarUrl = animated,
            avatarFrameUrl = frame,
        )
    }

    fun imageUrl(value: String?): HttpUrl? = value?.trim()?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 &&
            (it.host.endsWith(".steamstatic.com") || it.host == "steamcdn-a.akamaihd.net")
    }
}
