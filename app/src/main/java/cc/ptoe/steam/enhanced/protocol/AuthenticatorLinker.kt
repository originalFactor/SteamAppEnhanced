package cc.ptoe.steam.enhanced.protocol

import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.SteamTotp
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.proto.ProtoField
import cc.ptoe.steam.enhanced.core.proto.ProtoReader
import cc.ptoe.steam.enhanced.core.proto.ProtoWriter
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.SteamApiClient
import cc.ptoe.steam.enhanced.net.SteamApiException
import cc.ptoe.steam.enhanced.net.SteamTime
import kotlin.io.encoding.Base64

/** Outcome of `AddAuthenticator` / the move-in flow. */
enum class LinkResult {
    MUST_PROVIDE_PHONE_NUMBER,
    MUST_CONFIRM_EMAIL,
    AWAITING_FINALIZATION,
    GENERAL_FAILURE,
    AUTHENTICATOR_PRESENT,

    /** EResult 73 — account is locked/restricted by Steam. */
    ACCOUNT_LOCKED,

    /** EResult 84 — too many attempts, try later. */
    RATE_LIMITED,

    // Move-in ("move the authenticator to this device") outcomes.
    CHALLENGE_EMAIL_SENT,
    MOVED_IN,
    BAD_CHALLENGE_CODE,
}

/** Outcome of `FinalizeAddAuthenticator`. */
enum class FinalizeResult {
    BAD_SMS_CODE,
    UNABLE_TO_GENERATE_CORRECT_CODES,
    SUCCESS,
    GENERAL_FAILURE,
}

/**
 * Links a new Steam Guard mobile authenticator to an account (requires a
 * logged-in [SessionData]).
 *
 * Ported from AVA's `core/protocol/authenticator_linker.dart` — the modern
 * `ITwoFactorService` / `IPhoneService` flow.
 *
 * Field numbers/types follow the SteamKit/SteamDatabase protobufs: `steamid` is
 * **fixed64** in both AddAuthenticator and FinalizeAddAuthenticator.
 */
class AuthenticatorLinker(
    private val api: SteamApiClient,
    private val session: SessionData,
    private val steamTime: SteamTime = SteamTime.shared,
    /** Seam for [finalize]'s window timing, overridable in tests. */
    private val now: () -> Long = { steamTime.currentSteamTime() },
    /** Seam for [finalize]'s waits, overridable in tests. */
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    var phoneNumber: String? = null
    var phoneCountryCode: String = ""
    var linkedAccount: SteamGuardAccount? = null
        private set

    /**
     * How Steam will deliver the activation code (from AddAuthenticator's
     * `confirm_type`): 3 = email (no phone), otherwise SMS to the account phone.
     */
    private var confirmType: Int = 0

    val activatesByEmail: Boolean
        get() = confirmType == 3

    /** Restores a staged authenticator after the app or activity was restarted. */
    fun resumeActivation(account: SteamGuardAccount, byEmail: Boolean) {
        require(account.steamId == session.steamId && !account.fullyEnrolled)
        require(!account.sharedSecret.isNullOrBlank())
        linkedAccount = account.withSession(session)
        confirmType = if (byEmail) 3 else 0
    }

    private val accessToken: String
        get() = session.accessToken.orEmpty()

    /**
     * Drives AddAuthenticator.
     *
     * The response `status` decides the next step, matching Steam's flow:
     *   1  -> secrets returned (AWAITING_FINALIZATION)
     *   2  -> the account needs a phone first
     *   29 -> an authenticator is already present
     */
    suspend fun addAuthenticator(): LinkResult {
        val deviceId = linkedAccount?.deviceId ?: SteamTotp.generateDeviceId(session.steamId)

        val req = ProtoWriter()
            .writeFixed64(1, session.steamId) // STREAMID IS FIXED64 HERE
            .writeUint64(2, steamTime.currentSteamTime())
            .writeVarint(4, 1) // authenticator_type
            .writeString(5, deviceId) // device_identifier
            .writeString(6, "1") // sms_phone_id
            .writeVarint(8, 2) // version

        val parsed = try {
            api.callProtobuf(
                "ITwoFactorService",
                "AddAuthenticator",
                req,
                accessToken = accessToken,
            ).parse()
        } catch (e: SteamApiException) {
            dlog("AddAuthenticator failed: eresult ${e.eresult}")
            return when (e.eresult) {
                EResult.DUPLICATE_REQUEST -> LinkResult.AUTHENTICATOR_PRESENT
                EResult.ACCOUNT_LOCKED_DOWN -> LinkResult.ACCOUNT_LOCKED
                EResult.RATE_LIMIT_EXCEEDED -> LinkResult.RATE_LIMITED
                else -> LinkResult.GENERAL_FAILURE
            }
        }

        val status = parsed[10]?.asInt ?: 0
        val sharedSecret = parsed[1]?.bytes
        // confirm_type tells us how Steam will deliver the activation code: SMS for
        // phone accounts, email otherwise.
        confirmType = parsed[12]?.asInt ?: 0
        val phoneHint = parsed[11]?.asString.orEmpty()
        dlog(
            "AddAuthenticator: status=$status sharedSecret=${sharedSecret?.size ?: 0}B " +
                "confirmType=$confirmType phoneHint=\"$phoneHint\"",
        )

        if (status == 29) return LinkResult.AUTHENTICATOR_PRESENT

        if (status == 2 || sharedSecret == null || sharedSecret.isEmpty()) {
            // Account needs a phone number first.
            val phone = phoneNumber
            if (phone.isNullOrEmpty()) return LinkResult.MUST_PROVIDE_PHONE_NUMBER
            val added = addPhoneNumber(phone, phoneCountryCode)
            // Steam emails the user to confirm the new phone; they must click it,
            // then we retry AddAuthenticator.
            return if (added) LinkResult.MUST_CONFIRM_EMAIL else LinkResult.GENERAL_FAILURE
        }

        linkedAccount = accountFromAddResponse(
            parsed,
            deviceId = deviceId,
            fullyEnrolled = false, // finalize() still has to run
        )
        return LinkResult.AWAITING_FINALIZATION
    }

    /**
     * Builds an account from a `CTwoFactor_AddAuthenticator_Response` field map.
     *
     * Shared by [addAuthenticator] and [moveInContinue]: the latter's
     * `replacement_token` is that exact message nested one level down.
     */
    private fun accountFromAddResponse(
        fields: Map<Int, ProtoField>,
        deviceId: String,
        fullyEnrolled: Boolean,
    ): SteamGuardAccount {
        val secretBytes = fields[1]?.bytes ?: ByteArray(0)
        return SteamGuardAccount(
            sharedSecret = Base64.encode(secretBytes),
            serialNumber = (fields[2]?.asFixed64 ?: 0L).toString(),
            revocationCode = fields[3]?.asString,
            uri = fields[4]?.asString,
            serverTime = fields[5]?.varint ?: 0L,
            accountName = fields[6]?.asString,
            tokenGid = fields[7]?.asString,
            identitySecret = fields[8]?.bytes?.let { Base64.encode(it) },
            secret1 = fields[9]?.bytes?.let { Base64.encode(it) },
            status = fields[10]?.asInt ?: 0,
            deviceId = deviceId,
            fullyEnrolled = fullyEnrolled,
            session = session,
        )
    }

    /**
     * Starts the "move the authenticator to this device" challenge: Steam mails the
     * account a one-time code.
     *
     * Read-only account-side — nothing is revoked until [moveInContinue] runs, so
     * this is safe to retry.
     *
     * The endpoint is named Remove*, but with `generate_new_token` set (see
     * [moveInContinue]) the pair is a *move*, not a removal: Steam swaps the token
     * instead of detaching Steam Guard, which avoids the 15-day trade hold that
     * removing an authenticator via the website incurs.
     */
    suspend fun moveInStart(): LinkResult {
        return try {
            val fields = api.callProtobuf(
                "ITwoFactorService",
                "RemoveAuthenticatorViaChallengeStart",
                ProtoWriter(), // empty body; the access token identifies the account
                accessToken = accessToken,
            ).parse()
            // Steam answers a successful Start with an EMPTY body (eresult=OK, 0B):
            // `success` is an optional proto field it simply never fills. Defaulting
            // a missing field to false would reject every successful call — the
            // eresult header is the real signal, and callProtobuf already threw if it
            // was anything but OK. Only an explicit success=false is a failure.
            val success = fields[1]?.asBool ?: true
            dlog(
                "ChallengeStart: success=$success " +
                    "(${if (fields.isEmpty()) "empty body" else "${fields.size} fields"})",
            )
            if (success) LinkResult.CHALLENGE_EMAIL_SENT else LinkResult.GENERAL_FAILURE
        } catch (e: SteamApiException) {
            dlog("ChallengeStart failed: eresult ${e.eresult}")
            when (e.eresult) {
                EResult.ACCOUNT_LOCKED_DOWN -> LinkResult.ACCOUNT_LOCKED
                EResult.RATE_LIMIT_EXCEEDED -> LinkResult.RATE_LIMITED
                else -> LinkResult.GENERAL_FAILURE
            }
        }
    }

    /**
     * Completes the move with the [emailCode] Steam mailed in [moveInStart].
     *
     * ⚠️ On success this is **atomic and irreversible**: the old device's
     * authenticator is dead, the returned token is already active (no [finalize]
     * step), and the revocation code is replaced — the user's old R-code is void.
     *
     * There is no rollback point, so a caller that gets [LinkResult.MOVED_IN] MUST
     * persist [linkedAccount] immediately and, if that write fails, surface the new
     * secret and R-code for the user to copy. Losing them here locks the account out
     * permanently.
     */
    suspend fun moveInContinue(emailCode: String): LinkResult {
        // A move lands the authenticator on *this* device, so it gets a fresh device
        // id rather than inheriting the old one.
        val deviceId = SteamTotp.generateDeviceId(session.steamId)

        val req = ProtoWriter()
            .writeString(1, emailCode) // sms_code — the mailed code, despite the name
            .writeBool(2, true) // generate_new_token — false here would be a real removal
            .writeVarint(3, 2) // version

        val fields = try {
            api.callProtobuf(
                "ITwoFactorService",
                "RemoveAuthenticatorViaChallengeContinue",
                req,
                accessToken = accessToken,
            ).parse()
        } catch (e: SteamApiException) {
            dlog("ChallengeContinue failed: eresult ${e.eresult}")
            return when (e.eresult) {
                EResult.ACCOUNT_LOCKED_DOWN -> LinkResult.ACCOUNT_LOCKED
                EResult.RATE_LIMIT_EXCEEDED -> LinkResult.RATE_LIMITED
                EResult.SMS_CODE_FAILED -> LinkResult.BAD_CHALLENGE_CODE
                else -> LinkResult.GENERAL_FAILURE
            }
        }

        // `replacement_token`, not `success`, is the authoritative signal.
        //
        // Both are optional proto fields, and Start proved Steam leaves `success`
        // unset even on a successful call. Reading a missing `success` as false here
        // would be catastrophic rather than merely wrong: once Steam hands back a
        // token the swap HAS happened, the old authenticator is already dead, and
        // telling the user "bad code" sends them to retry a spent challenge —
        // locking the account out for good. So: token present => moved, whatever
        // `success` says.
        val replacement = fields[2]?.bytes
        val success = fields[1]?.asBool ?: true
        dlog("ChallengeContinue: success=$success replacement=${replacement?.size ?: 0}B")

        // No token means nothing was swapped and the account is untouched.
        if (replacement == null || replacement.isEmpty()) {
            return LinkResult.BAD_CHALLENGE_CODE
        }

        val inner = ProtoReader(replacement).parse()
        val secretBytes = inner[1]?.bytes
        if (secretBytes == null || secretBytes.isEmpty()) {
            // Steam said OK but handed back no shared_secret — never treat this as a
            // move, or we would drop the account with nothing to generate codes from.
            dlog("ChallengeContinue: replacement_token carried no shared_secret")
            return LinkResult.GENERAL_FAILURE
        }
        linkedAccount = accountFromAddResponse(
            inner,
            deviceId = deviceId,
            // The replacement token comes back already activated — unlike the Add
            // flow there is no FinalizeAddAuthenticator step.
            fullyEnrolled = true,
        )
        return LinkResult.MOVED_IN
    }

    /**
     * Finalizes the link with the [activationCode] Steam delivered — via SMS for
     * phone accounts, or via email when the account has no phone.
     *
     * Steam wants a run of correct TOTP codes and asks for more via `want_more`
     * until aligned.
     */
    suspend fun finalize(activationCode: String): FinalizeResult {
        val account = linkedAccount ?: return FinalizeResult.GENERAL_FAILURE

        // Steam asks for a short run of consecutive codes; each must come from a
        // fresh window, so cap by distinct windows rather than a tight loop. Steam
        // Guard codes are stable for the whole window, so retrying inside the same
        // one just resubmits the identical code — the old tight loop could burn
        // every retry on one code and trip rate limits.
        var tries = 0
        var lastSubmit = -1L
        while (tries < 5) {
            if (lastSubmit >= 0) {
                val wait = windowWaitSeconds(lastSubmit, now())
                if (wait > 0) sleep(wait * 1000L)
            }
            val time = now()
            val code = account.generateCode(time)
            lastSubmit = time
            val req = ProtoWriter()
                .writeFixed64(1, session.steamId)
                .writeString(2, code)
                .writeUint64(3, time)
                .writeString(4, activationCode)
                .writeBool(6, !activatesByEmail)

            val fields = api.callProtobuf(
                "ITwoFactorService",
                "FinalizeAddAuthenticator",
                req,
                accessToken = accessToken,
            ).parse()

            // `success` is an optional proto field — like moveInStart/moveInContinue
            // above, Steam is entitled to answer OK and leave it (or the whole body)
            // out, and defaulting it to false would fail a link that took: Steam Guard
            // live, the user told otherwise. The eresult header is the real signal —
            // callProtobuf has already thrown on anything but OK — so only explicit
            // evidence in the payload may override it.
            val successField = fields[1]
            val wantMore = fields[2]?.asBool ?: false
            val status = fields[4]?.asInt ?: 0
            dlog(
                "Finalize: success=${successField?.asBool} wantMore=$wantMore " +
                    "status=$status try=$tries",
            )

            if (status == EResult.TWO_FACTOR_ACTIVATION_CODE_MISMATCH) {
                return FinalizeResult.BAD_SMS_CODE
            }
            val explicitFailure = (successField != null && !successField.asBool) ||
                wantMore ||
                status == EResult.TWO_FACTOR_CODE_MISMATCH
            if (!explicitFailure) {
                return FinalizeResult.SUCCESS
            }
            if (wantMore || status == EResult.TWO_FACTOR_CODE_MISMATCH) {
                tries++
                continue
            }
            return FinalizeResult.GENERAL_FAILURE
        }
        return FinalizeResult.UNABLE_TO_GENERATE_CORRECT_CODES
    }

    /**
     * Adds a phone number to the account (`IPhoneService/SetAccountPhoneNumber`).
     *
     * Steam then sends a confirmation email; the caller surfaces
     * [LinkResult.MUST_CONFIRM_EMAIL] so the user can click it before retrying.
     */
    private suspend fun addPhoneNumber(number: String, countryCode: String): Boolean = try {
        val req = ProtoWriter().writeString(1, number)
        if (countryCode.isNotEmpty()) req.writeString(2, countryCode)
        api.callProtobuf(
            "IPhoneService",
            "SetAccountPhoneNumber",
            req,
            accessToken = accessToken,
        )
        true
    } catch (e: Exception) {
        dlog("SetAccountPhoneNumber failed: $e")
        false
    }

    /** Whether the account is still waiting for the user to click the phone confirmation email. */
    suspend fun isAwaitingEmailConfirmation(): Boolean = try {
        val fields = api.callProtobuf(
            "IPhoneService",
            "IsAccountWaitingForEmailConfirmation",
            ProtoWriter(),
            accessToken = accessToken,
        ).parse()
        fields[1]?.asBool ?: false
    } catch (_: Exception) {
        false
    }

    companion object {
        /**
         * Seconds a caller must wait after submitting a code at [lastSubmitTime]
         * before a *new* TOTP window (hence a different code) is available at [now].
         *
         * Returns 0 once [now] is already in a later 30-second window.
         */
        fun windowWaitSeconds(lastSubmitTime: Long, now: Long): Long {
            if (now / 30 > lastSubmitTime / 30) return 0
            // Time left in the window that lastSubmitTime belongs to, measured from
            // now, plus a 1s buffer to be safely past the boundary.
            val windowEnd = (lastSubmitTime / 30 + 1) * 30
            val wait = windowEnd - now + 1
            return if (wait > 0) wait else 1
        }
    }
}
