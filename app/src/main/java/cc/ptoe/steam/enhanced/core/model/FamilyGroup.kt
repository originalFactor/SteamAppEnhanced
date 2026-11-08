package cc.ptoe.steam.enhanced.core.model

/**
 * Steam Family Groups models.
 *
 * Ported from AVA's `core/models/family_group.dart`. Field numbers follow
 * SteamDatabase/Protobufs `webui/service_familygroups.proto`.
 */

/** A pending family-group invite for this account (FamilyGroupPendingInviteForUser). */
data class FamilyInvite(
    val familyGroupId: Long,
    /** Opaque Steam role id; the UI maps known values (1/2) to labels. */
    val role: Int,
    val inviterSteamId: Long,
    val awaiting2fa: Boolean,
    val inviteId: Long,
)

/** One member of a family group (FamilyGroupMember). */
data class FamilyMember(
    val steamId: Long,
    val role: Int,
    val timeJoined: Long,
)

/** Read-only snapshot of a family group (CFamilyGroups_GetFamilyGroup_Response). */
data class FamilyGroupInfo(
    val name: String,
    val members: List<FamilyMember>,
    val freeSpots: Int,
    val country: String,
    val slotCooldownRemainingSeconds: Int,
) {
    val totalSlots: Int
        get() = members.size + freeSpots
}

/** This account's family situation (GetFamilyGroupForUser). */
data class FamilyUserState(
    val familyGroupId: Long,
    val isNotMemberOfAnyGroup: Boolean,
    val pendingInvites: List<FamilyInvite>,
    val role: Int,
    val cooldownSecondsRemaining: Int,
    /** Set when `include_family_group_response` was requested. */
    val group: FamilyGroupInfo? = null,
) {
    val isMember: Boolean
        get() = !isNotMemberOfAnyGroup && familyGroupId != 0L
}

/**
 * Pre-join checks (GetInviteCheckResults).
 *
 * The endpoint is marked `ePrivilege=5` in Valve's dump — it may be refused for
 * ordinary user tokens, so callers treat "unavailable" (null) as a first-class
 * state rather than an error.
 */
data class InviteChecks(
    val walletCountryMatches: Boolean,
    val ipMatch: Boolean,
    /** 0 = no restriction. */
    val joinRestriction: Int,
)

/**
 * Result of JoinFamilyGroup.
 *
 * `twoFactorMethod != 0` means Steam expects a 2FA confirmation (the type-11
 * mobileconf this app can accept).
 */
data class JoinResult(
    val twoFactorMethod: Int,
    val cooldownSkipGranted: Boolean,
    val inviteAlreadyAccepted: Boolean,
    val cooldownSecondsRemaining: Int,
) {
    val needsTwoFactor: Boolean
        get() = twoFactorMethod != 0
}
