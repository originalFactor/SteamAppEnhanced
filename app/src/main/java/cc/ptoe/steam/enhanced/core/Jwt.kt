package cc.ptoe.steam.enhanced.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/**
 * Decodes the payload (second segment) of a JWT without any signature
 * verification.
 *
 * Ported from AVA's `core/jwt.dart`. Steam's tokens are only *read* client-side,
 * for claims like `exp` (staleness) and `sub` (steamid). Signature verification
 * is Valve's job and is deliberately not attempted here.
 *
 * Returns null on any malformation: absent/empty input, too few segments, bad
 * base64, non-JSON or non-object payload.
 */
object Jwt {
    fun decodePayload(jwt: String?): JsonObject? {
        if (jwt.isNullOrEmpty()) return null
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            JsonCoerce.json.parseToJsonElement(
                String(Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(p), Charsets.UTF_8),
            ) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** The `sub` claim parsed as a SteamID, or null. */
    fun steamId(payload: JsonObject?): Long? {
        if (payload == null) return null
        val sub = payload["sub"] as? JsonPrimitive ?: return null
        return sub.content.trim().toLongOrNull()
    }

    /**
     * The `exp` claim as a unix-seconds epoch, or null.
     *
     * Steam sends it as a JSON number; some proxies re-serialize it as a string,
     * so both spellings are accepted.
     */
    fun expiryEpochSeconds(payload: JsonObject?): Long? {
        if (payload == null) return null
        val exp = payload["exp"] ?: return null
        return JsonCoerce.asLongOrNull(exp)
    }
}
