package cc.ptoe.steam.enhanced.core.model

import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.SteamTotp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A single Steam Guard account — the JSON payload of a `*.maFile`.
 *
 * Ported from AVA's `core/models/steam_guard_account.dart`. Field names mirror
 * the C# `SteamGuardAccount` JsonProperty names exactly so existing maFiles load
 * and re-save losslessly. Unknown top-level keys are preserved in [extra].
 */
data class SteamGuardAccount(
    val sharedSecret: String? = null, // shared_secret
    val serialNumber: String? = null, // serial_number
    val revocationCode: String? = null, // revocation_code
    val uri: String? = null, // uri
    val serverTime: Long = 0L, // server_time
    val accountName: String? = null, // account_name
    val tokenGid: String? = null, // token_gid
    val identitySecret: String? = null, // identity_secret
    val secret1: String? = null, // secret_1
    val status: Int = 0, // status
    val deviceId: String? = null, // device_id
    val fullyEnrolled: Boolean = false, // fully_enrolled
    val avatarUrl: String? = null, // avatar_url — cached Steam profile avatar (full)
    val avatarFrameUrl: String? = null, // avatar_frame_url — equipped profile avatar frame
    val animatedAvatarUrl: String? = null, // animated_avatar_url — equipped animated avatar
    val personaName: String? = null, // persona_name — Steam display name / nickname
    val password: String? = null, // password — for automatic headless re-login
    val session: SessionData = SessionData(),
    /** Unknown top-level keys, preserved so a re-save never drops data. */
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /** Steam ID convenience accessor. */
    val steamId: Long
        get() = session.steamId

    /**
     * Generates the current login code at the given Steam server [time].
     *
     * Throws when the account has no shared_secret — an account imported for its
     * codes only always has one, so this is a corrupt-file condition.
     */
    fun generateCode(time: Long): String {
        val secret = sharedSecret
        check(!secret.isNullOrEmpty()) { "Account has no shared_secret" }
        return SteamTotp.generateAuthCode(secret, time)
    }

    /** A copy with a replaced session (models are immutable). */
    fun withSession(session: SessionData): SteamGuardAccount = copy(session = session)

    fun toJson(): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> put(k, v) }
        put("shared_secret", sharedSecret)
        put("serial_number", serialNumber)
        put("revocation_code", revocationCode)
        put("uri", uri)
        put("server_time", serverTime)
        put("account_name", accountName)
        put("token_gid", tokenGid)
        put("identity_secret", identitySecret)
        put("secret_1", secret1)
        put("status", status)
        put("device_id", deviceId)
        put("fully_enrolled", fullyEnrolled)
        put("avatar_url", avatarUrl)
        put("avatar_frame_url", avatarFrameUrl)
        put("animated_avatar_url", animatedAvatarUrl)
        put("persona_name", personaName)
        put("password", password)
        putJsonObject("Session") { session.toJson().forEach { (k, v) -> put(k, v) } }
    }

    /**
     * JSON for a plaintext maFile export.
     *
     * Unlike [toJson] (internal persistence), the saved Steam password stays out
     * of the file unless the caller explicitly opts in — an exported maFile
     * already grants authenticator takeover, it should not hand over the account
     * password too.
     */
    fun toExportJson(includePassword: Boolean = false): JsonObject {
        val json = toJson()
        if (includePassword) return json
        return JsonObject(json.filterKeys { it != "password" })
    }

    companion object {
        private val KNOWN = setOf(
            "shared_secret",
            "serial_number",
            "revocation_code",
            "uri",
            "server_time",
            "account_name",
            "token_gid",
            "identity_secret",
            "secret_1",
            "status",
            "device_id",
            "fully_enrolled",
            "avatar_url",
            "avatar_frame_url",
            "animated_avatar_url",
            "persona_name",
            "password",
            "Session",
        )

        fun fromJson(json: JsonObject): SteamGuardAccount {
            val extra = JsonObject(json.filterKeys { it !in KNOWN })
            val sessionJson = json["Session"] as? JsonObject
            return SteamGuardAccount(
                sharedSecret = JsonCoerce.asStringOrNull(json["shared_secret"]),
                serialNumber = JsonCoerce.asStringOrNull(json["serial_number"]),
                revocationCode = JsonCoerce.asStringOrNull(json["revocation_code"]),
                uri = JsonCoerce.asStringOrNull(json["uri"]),
                serverTime = JsonCoerce.asLongOrNull(json["server_time"]) ?: 0L,
                accountName = JsonCoerce.asStringOrNull(json["account_name"]),
                tokenGid = JsonCoerce.asStringOrNull(json["token_gid"]),
                identitySecret = JsonCoerce.asStringOrNull(json["identity_secret"]),
                secret1 = JsonCoerce.asStringOrNull(json["secret_1"]),
                status = JsonCoerce.asInt(json["status"]),
                deviceId = JsonCoerce.asStringOrNull(json["device_id"]),
                fullyEnrolled = JsonCoerce.asBool(json["fully_enrolled"]),
                avatarUrl = JsonCoerce.asStringOrNull(json["avatar_url"]),
                avatarFrameUrl = JsonCoerce.asStringOrNull(json["avatar_frame_url"]),
                animatedAvatarUrl = JsonCoerce.asStringOrNull(json["animated_avatar_url"]),
                personaName = JsonCoerce.asStringOrNull(json["persona_name"]),
                password = JsonCoerce.asStringOrNull(json["password"]),
                session = sessionJson?.let { SessionData.fromJson(it) } ?: SessionData(),
                extra = extra,
            )
        }
    }
}
