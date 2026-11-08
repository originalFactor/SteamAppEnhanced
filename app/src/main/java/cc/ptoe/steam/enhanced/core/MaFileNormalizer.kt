package cc.ptoe.steam.enhanced.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Normalizes common maFile variants into the shape used by SteamAuth/SDA.
 *
 * Ported from AVA's `core/ma_file_normalizer.dart`. The model layer intentionally
 * preserves unknown keys for round-tripping, so import-time compatibility lives
 * here instead of inside [cc.ptoe.steam.enhanced.core.model.SteamGuardAccount].
 *
 * Three variant families are handled, all seen in the wild:
 *  - Steam++ / Watt Toolkit exports that store the SteamID base64-encoded rather
 *    than as a decimal string.
 *  - Exports with no SteamID at all (imported as code-only accounts) — the id is
 *    recovered from the filename when the file is named after it.
 *  - Exports that carry the shared secret only inside an `otpauth://` /
 *    `steam://` URI, base32-encoded.
 */
object MaFileNormalizer {
    private val STEAM_ID_KEYS = listOf(
        "steamid",
        "steam64",
        "steam_id64",
        "steamID64",
        "SteamID64",
        "sbeamid",
    )

    private val URI_KEYS = listOf("uri", "otp_uri", "otpauth_uri", "steam_uri", "url")

    /** Individual SteamID64s start at 0x0110000100000000 (76561197960265728). */
    private const val STEAM_ID_BASE = 76561197960265728L
    private const val STEAM_ID_CEILING = 76561202000000000L

    fun normalize(json: JsonObject, sourceName: String? = null): JsonObject {
        var out = normalizeSteamId(json, sourceName)
        out = normalizeSharedSecret(out)
        return out
    }

    private fun normalizeSteamId(json: JsonObject, sourceName: String?): JsonObject {
        val session = sessionMap(json)
        if ((JsonCoerce.asLongOrNull(session["SteamID"]) ?: 0L) != 0L) return json

        for (key in STEAM_ID_KEYS) {
            val value = json[key] ?: continue
            val steamId = JsonCoerce.asLongOrNull(value) ?: 0L
            if (steamId != 0L) {
                return json.withSessionSteamId(session, steamId)
            }
            // Some exporters store the 64-bit SteamID base64-encoded rather than as
            // a decimal string.
            val fromB64 = steamIdFromBase64(value)
            if (fromB64 != 0L) {
                return json.withSessionSteamId(session, fromB64)
            }
        }

        val fromName = steamIdFromSourceName(sourceName)
        if (fromName != 0L) return json.withSessionSteamId(session, fromName)
        return json
    }

    private fun JsonObject.withSessionSteamId(
        session: Map<String, JsonElement>,
        steamId: Long,
    ): JsonObject = buildJsonObject {
        this@withSessionSteamId.forEach { (k, v) -> if (k != "Session") put(k, v) }
        put(
            "Session",
            buildJsonObject {
                session.forEach { (k, v) -> if (k != "SteamID") put(k, v) }
                put("SteamID", steamId)
            },
        )
    }

    private fun sessionMap(json: JsonObject): Map<String, JsonElement> =
        (json["Session"] as? JsonObject) ?: emptyMap()

    /**
     * Decodes a base64-encoded 8-byte SteamID (either endianness) if it lands in
     * the individual-account SteamID64 range; else 0.
     */
    private fun steamIdFromBase64(value: JsonElement): Long {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (text.isNullOrEmpty()) return 0L
        val bytes = try {
            Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(text)
        } catch (_: IllegalArgumentException) {
            return 0L
        }
        if (bytes.size != 8) return 0L
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        for (id in listOf(
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(0),
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN).getLong(0),
        )) {
            if (id in STEAM_ID_BASE until STEAM_ID_CEILING) return id
        }
        return 0L
    }

    private fun steamIdFromSourceName(sourceName: String?): Long {
        if (sourceName == null) return 0L
        val match = Regex("""(^|[^0-9])(7656119[0-9]{10})([^0-9]|$)""").find(sourceName)
            ?: return 0L
        return match.groupValues[2].toLongOrNull() ?: 0L
    }

    private fun normalizeSharedSecret(json: JsonObject): JsonObject {
        val uriSecret = sharedSecretFromUri(json)
        if (uriSecret != null) {
            return JsonObject(json.toMutableMap().apply { put("shared_secret", JsonPrimitive(uriSecret)) })
        }

        val secret = (json["shared_secret"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()
        if (secret.isNullOrEmpty()) return json

        val normalized = normalizeSecretValue(secret) ?: return json
        return JsonObject(json.toMutableMap().apply { put("shared_secret", JsonPrimitive(normalized)) })
    }

    private fun sharedSecretFromUri(json: JsonObject): String? {
        for (key in URI_KEYS) {
            val value = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (value.isNullOrEmpty()) continue
            val secret = extractSecretFromUri(value) ?: continue
            if (secret.isEmpty()) continue
            return base32ToBase64(secret) ?: normalizeSecretValue(secret) ?: secret
        }
        return null
    }

    private fun extractSecretFromUri(value: String): String? {
        Regex("""^steam://(.+)$""", RegexOption.IGNORE_CASE).find(value)?.let { m ->
            return java.net.URLDecoder.decode(
                m.groupValues[1].replaceFirst(Regex("^/+"), ""),
                "UTF-8",
            )
        }

        val parsed = runCatching { java.net.URI(value) }.getOrNull()
        parsed?.let { uri ->
            val query = uri.rawQuery ?: return@let
            val secret = query.split('&')
                .firstOrNull { it.startsWith("secret=") }
                ?.substringAfter("secret=")
            if (!secret.isNullOrEmpty()) {
                return java.net.URLDecoder.decode(secret, "UTF-8")
            }
        }

        Regex("""(?:^|[?&])secret=([^&]+)""", RegexOption.IGNORE_CASE).find(value)?.let { m ->
            return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        }
        return null
    }

    private fun normalizeSecretValue(value: String): String? {
        val trimmed = value.trim()
        val base64Bytes = tryBase64Decode(trimmed)
        // A 20-byte decoded payload is already a Steam secret in base64 form.
        if (base64Bytes != null && base64Bytes.size == 20) return trimmed

        return base32ToBase64(trimmed)
            ?: base64Bytes?.let { Base64.encode(it) }
    }

    private fun tryBase64Decode(value: String): ByteArray? = try {
        Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Base32 (RFC 4648, case-insensitive, hyphens/spaces ignored) to base64.
     *
     * `otpauth://` URIs carry the secret in base32; maFiles store base64. Steam
     * only accepts the base64 form.
     */
    private fun base32ToBase64(value: String): String? {
        val cleaned = value.trim()
            .replace(Regex("[\\s-]"), "")
            .replace("=", "")
            .uppercase()
        if (cleaned.isEmpty() || !Regex("^[A-Z2-7]+$").matches(cleaned)) return null

        var buffer = 0
        var bitsLeft = 0
        val bytes = java.io.ByteArrayOutputStream()

        for (ch in cleaned) {
            val v = base32Value(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes.write((buffer ushr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        val out = bytes.toByteArray()
        return if (out.isEmpty()) null else Base64.encode(out)
    }

    private fun base32Value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in '2'..'7' -> ch - '2' + 26
        else -> -1
    }
}
