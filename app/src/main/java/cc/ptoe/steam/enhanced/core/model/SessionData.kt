package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Steam web session stored inside a maFile under the `Session` key.
 *
 * Ported from AVA's `core/models/session_data.dart`. The modern SteamAuth/SteamKit
 * flow uses [steamId] + [accessToken] + [refreshToken]. Older maFiles may carry
 * extra cookie fields (SessionID, SteamLoginSecure, WebCookie, OAuthToken…);
 * those are preserved verbatim in [extra] so re-saving a file never loses data.
 */
data class SessionData(
    val steamId: Long = 0L,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Any other keys present in the original Session JSON (lossless round-trip). */
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * True when either token is present — legitimate for "can this be renewed?"
     * checks such as `AutoLogin.ensureSession`.
     */
    val hasTokens: Boolean
        get() = !accessToken.isNullOrEmpty() || !refreshToken.isNullOrEmpty()

    /**
     * True only when a non-empty access token is present.
     *
     * Callers about to call a Steam Web API endpoint directly need *this*: those
     * endpoints authenticate with the access token alone, and a refresh-only
     * session would otherwise send an empty access token instead of failing
     * clearly.
     */
    val hasAccessToken: Boolean
        get() = !accessToken.isNullOrEmpty()

    fun toJson(): JsonObject = buildJsonObject {
        // Unknown keys first so the known keys below always win on conflict.
        extra.forEach { (k, v) -> put(k, v) }
        put("SteamID", steamId)
        if (accessToken != null) put("AccessToken", accessToken)
        if (refreshToken != null) put("RefreshToken", refreshToken)
    }

    companion object {
        private val KNOWN = setOf("SteamID", "AccessToken", "RefreshToken")

        fun fromJson(json: JsonObject): SessionData = SessionData(
            steamId = JsonCoerce.asLongOrNull(json["SteamID"]) ?: 0L,
            accessToken = JsonCoerce.asStringOrNull(json["AccessToken"]),
            refreshToken = JsonCoerce.asStringOrNull(json["RefreshToken"]),
            extra = JsonObject(json.filterKeys { it !in KNOWN }),
        )
    }
}
