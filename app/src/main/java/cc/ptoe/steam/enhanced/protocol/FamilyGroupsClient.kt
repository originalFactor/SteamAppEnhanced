package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.model.TradeOffer
import cc.ptoe.steam.enhanced.core.model.FamilyGroupInfo
import cc.ptoe.steam.enhanced.core.model.FamilyInvite
import cc.ptoe.steam.enhanced.core.model.FamilyMember
import cc.ptoe.steam.enhanced.core.model.FamilyPersona
import cc.ptoe.steam.enhanced.core.model.FamilyUserState
import cc.ptoe.steam.enhanced.core.model.InviteChecks
import cc.ptoe.steam.enhanced.core.model.JoinResult
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import cc.ptoe.steam.enhanced.net.requireAccessToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Steam Family Groups (`IFamilyGroupsService`, webui protobuf surface).
 *
 * Ported from AVA's `core/protocol/family_groups_client.dart`. Field numbers follow
 * SteamDatabase/Protobufs `webui/service_familygroups.proto`.
 *
 * Every read method here is a bConstMethod: POST is rejected by
 * `api.steampowered.com` with **HTTP 405**, so they all go out as GET (found on a
 * real device, not guessable from the response).
 */
class FamilyGroupsClient(private val api: SteamApiClient) {

    /** Public nicknames and avatars; account login names are only known locally. */
    suspend fun memberProfiles(steamIds: Collection<Long>): Map<Long, FamilyPersona> = coroutineScope {
        val requests = Semaphore(4)
        steamIds.distinct().filter {
            it > TradeOffer.STEAM_ID64_BASE && it - TradeOffer.STEAM_ID64_BASE <= 0xFFFF_FFFFL
        }.map { steamId ->
            async {
                requests.withPermit {
                    try {
                        val accountId = steamId - TradeOffer.STEAM_ID64_BASE
                        val json = api.communityGetJson("/miniprofile/$accountId/json", emptyMap())
                        val nickname = JsonCoerce.asString(json["persona_name"]).trim().takeIf(String::isNotEmpty)
                        val avatar = JsonCoerce.asString(json["avatar_url"]).trim().takeIf(String::isNotEmpty)
                        if (nickname != null || avatar != null) steamId to FamilyPersona(nickname, avatar) else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /** This account's family situation: current group + pending invites. */
    suspend fun forUser(account: SteamGuardAccount): FamilyUserState {
        val token = requireAccessToken(account)
        val req = ProtoWriter()
            // steamid is a plain uint64 VARINT here — not fixed64, unlike the
            // two-factor messages.
            .writeUint64(1, account.steamId)
            .writeBool(2, true) // include_family_group_response

        val reader = api.callProtobuf(
            IFACE,
            "GetFamilyGroupForUser",
            req,
            accessToken = token,
            useGet = true,
        )

        val invites = mutableListOf<FamilyInvite>()
        var familyGroupId = 0L
        var notMember = false
        var role = 0
        var cooldown = 0
        var group: FamilyGroupInfo? = null
        for (f in reader.parseAll()) {
            when (f.number) {
                1 -> familyGroupId = f.varint ?: 0L
                2 -> notMember = f.asBool
                5 -> f.bytes?.let { invites.add(invite(it)) }
                6 -> role = f.asInt
                7 -> cooldown = f.asInt
                8 -> f.bytes?.let { group = groupFromReader(ProtoReader(it)) }
            }
        }
        dlog(
            "family forUser: member=${!notMember} groupId=$familyGroupId " +
                "invites=${invites.size}",
        )
        return FamilyUserState(
            familyGroupId = familyGroupId,
            isNotMemberOfAnyGroup = notMember,
            pendingInvites = invites,
            role = role,
            cooldownSecondsRemaining = cooldown,
            group = group,
        )
    }

    /** Read-only group snapshot (name, members, slots, cooldown). */
    suspend fun groupInfo(account: SteamGuardAccount, familyGroupId: Long): FamilyGroupInfo {
        val token = requireAccessToken(account)
        val req = ProtoWriter().writeUint64(1, familyGroupId)
        val reader = api.callProtobuf(
            IFACE,
            "GetFamilyGroup",
            req,
            accessToken = token,
            useGet = true,
        )
        return groupFromReader(reader)
    }

    /**
     * Pre-join checks.
     *
     * Valve marks this endpoint `ePrivilege=5` (internal), so a plain user token
     * may be refused — any [SteamApiException] degrades to null and the UI simply
     * hides the dynamic check rows.
     */
    suspend fun inviteChecks(account: SteamGuardAccount, familyGroupId: Long): InviteChecks? {
        val token = requireAccessToken(account)
        val req = ProtoWriter()
            .writeUint64(1, familyGroupId)
            .writeFixed64(2, account.steamId) // steamid IS fixed64 here
        return try {
            val fields = api.callProtobuf(
                IFACE,
                "GetInviteCheckResults",
                req,
                accessToken = token,
                useGet = true,
            ).parse()
            InviteChecks(
                walletCountryMatches = fields[1]?.asBool ?: false,
                ipMatch = fields[2]?.asBool ?: false,
                joinRestriction = fields[3]?.asInt ?: 0,
            )
        } catch (e: SteamApiException) {
            dlog("inviteChecks unavailable (eresult ${e.eresult}) — degrading")
            null
        }
    }

    /**
     * Joins via a pending invite.
     *
     * The invite payload carries no separate nonce field, so
     * [FamilyInvite.inviteId] is sent as the nonce — the best available candidate.
     */
    suspend fun join(account: SteamGuardAccount, invite: FamilyInvite): JoinResult {
        val token = requireAccessToken(account)
        val req = ProtoWriter()
            .writeUint64(1, invite.familyGroupId)
            .writeUint64(2, invite.inviteId) // nonce
        val fields = api.callProtobuf(
            IFACE,
            "JoinFamilyGroup",
            req,
            accessToken = token,
        ).parse()
        // Response has NO field 1 — fields start at 2 (two_factor_method).
        val result = JoinResult(
            twoFactorMethod = fields[2]?.asInt ?: 0,
            cooldownSkipGranted = fields[3]?.asBool ?: false,
            inviteAlreadyAccepted = fields[4]?.asBool ?: false,
            cooldownSecondsRemaining = fields[5]?.asInt ?: 0,
        )
        dlog(
            "family join ${invite.familyGroupId}: 2fa=${result.twoFactorMethod} " +
                "already=${result.inviteAlreadyAccepted}",
        )
        return result
    }

    /**
     * Post-2FA confirmation.
     *
     * Whether Steam requires it after the type-11 mobileconf accept is a
     * real-device question; the current flow does not call it.
     */
    suspend fun confirmJoin(
        account: SteamGuardAccount,
        familyGroupId: Long,
        inviteId: Long,
    ) {
        val token = requireAccessToken(account)
        val req = ProtoWriter()
            .writeUint64(1, familyGroupId)
            .writeUint64(2, inviteId)
            .writeUint64(3, inviteId) // nonce = invite_id (same as join)
        api.callProtobuf(
            IFACE,
            "ConfirmJoinFamilyGroup",
            req,
            accessToken = token,
        )
        // Empty response body; success is the eresult header (callProtobuf throws on
        // any eresult != OK).
    }

    private fun invite(bytes: ByteArray): FamilyInvite {
        val f = ProtoReader(bytes).parse()
        return FamilyInvite(
            familyGroupId = f[1]?.varint ?: 0L,
            role = f[2]?.asInt ?: 0,
            inviterSteamId = f[3]?.asFixed64 ?: 0L,
            awaiting2fa = f[4]?.asBool ?: false,
            inviteId = f[5]?.varint ?: 0L,
        )
    }

    private fun groupFromReader(reader: ProtoReader): FamilyGroupInfo {
        var name = ""
        val members = mutableListOf<FamilyMember>()
        var freeSpots = 0
        var country = ""
        var cooldown = 0
        for (f in reader.parseAll()) {
            when (f.number) {
                1 -> name = f.asString
                2 -> f.bytes?.let {
                    val m = ProtoReader(it).parse()
                    members.add(
                        FamilyMember(
                            steamId = m[1]?.asFixed64 ?: 0L,
                            role = m[2]?.asInt ?: 0,
                            timeJoined = m[3]?.varint ?: 0L,
                        ),
                    )
                }

                4 -> freeSpots = f.asInt
                5 -> country = f.asString
                6 -> cooldown = f.asInt
            }
        }
        return FamilyGroupInfo(
            name = name,
            members = members,
            freeSpots = freeSpots,
            country = country,
            slotCooldownRemainingSeconds = cooldown,
        )
    }

    companion object {
        private const val IFACE = "IFamilyGroupsService"
    }
}
