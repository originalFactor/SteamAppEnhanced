package cc.ptoe.steam.enhanced.ui

import androidx.annotation.StringRes
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.core.model.TradeOfferState
import cc.ptoe.steam.enhanced.protocol.FinalizeResult
import cc.ptoe.steam.enhanced.protocol.KeyRedeemError
import cc.ptoe.steam.enhanced.protocol.LinkResult
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Marks text already localized at the UI boundary; protocol diagnostics stay separate. */
internal class UiFailure(message: String) : IllegalStateException(message)

@OptIn(ExperimentalContracts::class)
internal inline fun checkUi(value: Boolean, message: () -> String = { "" }) {
    contract { returns() implies value }
    if (!value) throw UiFailure(message())
}

@OptIn(ExperimentalContracts::class)
internal inline fun requireUi(value: Boolean, message: () -> String = { "" }) {
    contract { returns() implies value }
    if (!value) throw UiFailure(message())
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> checkNotNullUi(value: T?, message: () -> String = { "" }): T {
    contract { returns() implies (value != null) }
    return value ?: throw UiFailure(message())
}

internal enum class TradeAction(@param:StringRes val label: Int, @param:StringRes val question: Int) {
    ACCEPT(R.string.msg_accept, R.string.msg_trade_accept_question),
    DECLINE(R.string.msg_decline, R.string.msg_trade_decline_question),
    CANCEL(R.string.ava_common_cancel, R.string.msg_trade_cancel_question),
}

@StringRes
internal fun tradeStateLabel(state: TradeOfferState): Int = when (state) {
    TradeOfferState.INVALID -> R.string.msg_state_invalid
    TradeOfferState.ACTIVE -> R.string.msg_state_active
    TradeOfferState.ACCEPTED -> R.string.msg_state_accepted
    TradeOfferState.COUNTERED -> R.string.msg_state_countered
    TradeOfferState.EXPIRED -> R.string.msg_state_expired
    TradeOfferState.CANCELED -> R.string.msg_state_canceled
    TradeOfferState.DECLINED -> R.string.msg_state_declined
    TradeOfferState.INVALID_ITEMS -> R.string.msg_state_invalid_items
    TradeOfferState.NEEDS_CONFIRMATION -> R.string.msg_state_needs_confirmation
    TradeOfferState.CANCELED_BY_SECOND_FACTOR -> R.string.msg_state_canceled_by_guard
    TradeOfferState.IN_ESCROW -> R.string.msg_state_escrow
    TradeOfferState.UNKNOWN -> R.string.msg_state_unknown
}

@StringRes
internal fun keyErrorLabel(error: KeyRedeemError?): Int = when (error) {
    KeyRedeemError.INVALID_KEY -> R.string.msg_key_invalid
    KeyRedeemError.ALREADY_OWNED -> R.string.msg_key_owned
    KeyRedeemError.ALREADY_ACTIVATED -> R.string.msg_key_activated
    KeyRedeemError.REGION_LOCKED -> R.string.msg_key_region
    KeyRedeemError.NEEDS_BASE_PRODUCT -> R.string.msg_key_base
    KeyRedeemError.NEEDS_PS3_LOGIN -> R.string.msg_key_ps3
    KeyRedeemError.RATE_LIMITED -> R.string.ava_login_err_rate_limited
    KeyRedeemError.UNKNOWN, null -> R.string.msg_state_unknown
}

@StringRes
internal fun linkResultLabel(result: LinkResult): Int = when (result) {
    LinkResult.MUST_PROVIDE_PHONE_NUMBER -> R.string.msg_error_phone_required
    LinkResult.MUST_CONFIRM_EMAIL -> R.string.msg_error_email_confirmation
    LinkResult.AWAITING_FINALIZATION -> R.string.msg_enroll_activation_prompt
    LinkResult.GENERAL_FAILURE -> R.string.msg_error_generic
    LinkResult.AUTHENTICATOR_PRESENT -> R.string.msg_enroll_already_present
    LinkResult.ACCOUNT_LOCKED -> R.string.msg_error_account_locked
    LinkResult.RATE_LIMITED -> R.string.ava_login_err_rate_limited
    LinkResult.CHALLENGE_EMAIL_SENT -> R.string.msg_enroll_transfer_prompt
    LinkResult.MOVED_IN -> R.string.msg_enroll_ready
    LinkResult.BAD_CHALLENGE_CODE -> R.string.ui_recovery_bad_code
}

@StringRes
internal fun finalizeResultLabel(result: FinalizeResult): Int = when (result) {
    FinalizeResult.BAD_SMS_CODE -> R.string.ava_login_err_code_mismatch
    FinalizeResult.UNABLE_TO_GENERATE_CORRECT_CODES -> R.string.msg_error_code_generation
    FinalizeResult.SUCCESS -> R.string.msg_enroll_ready
    FinalizeResult.GENERAL_FAILURE -> R.string.msg_error_generic
}
