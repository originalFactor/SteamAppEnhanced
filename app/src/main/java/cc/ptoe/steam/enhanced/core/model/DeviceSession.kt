package cc.ptoe.steam.enhanced.core.model

/**
 * A single logged-in device / session for a Steam account, decoded from one
 * `CAuthentication_RefreshToken_Enumerate_Response.RefreshTokenDescription`.
 *
 * Ported from AVA's `core/models/device_session.dart`. Field numbers follow
 * SteamDatabase/Protobufs `steam/steammessages_auth.steamclient.proto`.
 */
data class DeviceSession(
    /**
     * `token_id` (fixed64) — stable id of this refresh token. Needed later to
     * revoke a specific device; kept as a **string** because it is a 64-bit
     * handle, not an arithmetic value (its top bit is often set).
     */
    val tokenId: String,
    /** `token_description` — Steam's human label ("Firefox on Windows", …). May be empty. */
    val description: String,
    /** `time_updated` (unix seconds) — last time this token was refreshed. */
    val timeUpdated: Long,
    /** `platform_type` — 0 unknown, 1 SteamClient, 2 WebBrowser, 3 MobileApp. */
    val platformType: Int,
    /** `logged_in` — whether the session is currently active. */
    val loggedIn: Boolean,
    /** `last_seen` usage event, best-effort (may be absent). */
    val lastSeen: DeviceUsage? = null,
)

/**
 * One `TokenUsageEvent` (first_seen / last_seen): when + roughly where a token
 * was used.
 *
 * IP is deliberately dropped — the coarse geo (city/country) is what a user
 * recognises, and holding the IP buys nothing here.
 */
data class DeviceUsage(
    val time: Long = 0L,
    val country: String = "",
    val state: String = "",
    val city: String = "",
) {
    /** "City, State, Country" with the empty parts dropped. */
    val locationLabel: String
        get() = listOf(city, state, country).filter { it.isNotEmpty() }.joinToString(", ")
}

/**
 * The full device list plus which entry is *this* device (the token the request
 * authenticated with), so the UI can badge "current" and refuse to offer
 * self-revocation.
 */
data class DeviceSessionList(
    val devices: List<DeviceSession>,
    /**
     * `requesting_token` (fixed64) — the token_id of the calling device, or empty
     * when Steam didn't echo it.
     */
    val requestingTokenId: String = "",
) {
    fun isCurrent(device: DeviceSession): Boolean =
        requestingTokenId.isNotEmpty() && device.tokenId == requestingTokenId
}
