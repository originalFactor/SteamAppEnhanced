package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import android.content.res.Resources
import androidx.annotation.StringRes
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.protocol.LinkResult
import cc.ptoe.steam.enhanced.protocol.ConfirmationAuthException
import cc.ptoe.steam.enhanced.protocol.InventoryLoadException
import cc.ptoe.steam.enhanced.protocol.InventoryFailure
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.net.CommunityAuthException
import cc.ptoe.steam.enhanced.net.MissingAccessTokenException
import cc.ptoe.steam.enhanced.net.SteamApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

internal class AppWork(private val scope: CoroutineScope, private val resources: () -> Resources) {
    fun text(@StringRes id: Int, vararg args: Any): String = resources().getString(id, *args)

    fun errorMessage(failure: Exception): String {
        // Exception messages may contain request URLs/tokens. Log only the type
        // and source location so generic UI failures can be diagnosed safely.
        android.util.Log.w("SteamEnhanced", "Operation failed: ${failure.javaClass.name} at ${failure.stackTrace.firstOrNull()}")
        if (failure is UiFailure) return failure.message?.takeIf(String::isNotBlank) ?: text(R.string.msg_error_generic)
        if (failure is InventoryLoadException) return text(when (failure.reason) {
            InventoryFailure.PAGE_UNAVAILABLE -> R.string.ui_inventory_page_unavailable
            InventoryFailure.PRIVATE -> R.string.ui_inventory_private
            InventoryFailure.ITEMS_UNAVAILABLE -> R.string.ui_inventory_items_unavailable
        })
        if (failure is CommunityAuthException || failure is MissingAccessTokenException || failure is ConfirmationAuthException) return text(R.string.msg_error_sign_in)
        if (failure is SteamApiException) return when (failure.eresult) {
            EResult.INVALID_PASSWORD -> text(R.string.ava_login_err_invalid_password)
            EResult.RATE_LIMIT_EXCEEDED, 87 -> text(R.string.ava_login_err_rate_limited)
            EResult.TWO_FACTOR_CODE_MISMATCH, EResult.TWO_FACTOR_ACTIVATION_CODE_MISMATCH, EResult.SMS_CODE_FAILED, 65 -> text(R.string.ava_login_err_code_mismatch)
            EResult.ACCOUNT_LOCKED_DOWN -> text(R.string.msg_error_account_locked)
            else -> text(R.string.msg_error_steam_code, failure.eresult)
        }
        if (failure is IOException) return text(R.string.msg_error_io)
        val message = failure.message.orEmpty()
        LinkResult.entries.firstOrNull { it.name == message }?.let { return text(linkResultLabel(it)) }
        Regex("Try again in (\\d+) seconds\\.").matchEntire(message)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return text(R.string.msg_error_retry_seconds, it)
        }
        return when {
            message == "Incorrect PIN." -> text(R.string.msg_error_pin)
            message.contains("session has expired") || message == "Connect the Steam account first." -> text(R.string.msg_error_sign_in)
            message.contains("Device unlock is unavailable") -> text(R.string.ui_biometric_unavailable)
            message.contains("vault key is unavailable") -> text(R.string.msg_error_vault)
            message.contains("sign-in") || message.contains("auth session") || message.contains("rsa ") -> text(R.string.ui_login_restart_needed)
            else -> text(R.string.msg_error_generic)
        }
    }

    var busy by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<String?>(null)
    var confirmation by mutableStateOf<ConfirmationPrompt?>(null)
        private set

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                feedback = errorMessage(failure)
            } finally {
                busy = false
            }
        }
    }

    fun ask(
        title: String,
        summary: String,
        action: String,
        hold: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (!busy) confirmation = ConfirmationPrompt(title, summary, action, hold, block)
    }

    fun dismiss() {
        confirmation = null
    }
}

internal data class ConfirmationPrompt(
    val title: String,
    val summary: String,
    val action: String,
    val hold: Boolean,
    val block: suspend () -> Unit,
)

@Composable
internal fun WorkPrompt(work: AppWork) {
    val prompt = work.confirmation ?: return
    val haptics = LocalHapticFeedback.current
    val confirm = {
        work.dismiss()
        work.run(prompt.block)
    }
    WindowDialog(show = true, title = prompt.title, summary = prompt.summary, onDismissRequest = work::dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.ava_common_cancel), work::dismiss, Modifier.weight(1f))
            if (prompt.hold) {
                key(prompt) {
                    HoldToConfirmButton(stringResource(R.string.ava_hold_to, prompt.action), Modifier.weight(1f)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                }
            } else {
                TextButton(prompt.action, confirm, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        label, onClick, Modifier.fillMaxWidth(), enabled = enabled,
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

@Composable
internal fun Entry(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(title = label, enabled = enabled, onClick = onClick)
}

@Composable
internal fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    number: Boolean = false,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    val keyboard = when {
        secret && number -> KeyboardType.NumberPassword
        secret -> KeyboardType.Password
        number -> KeyboardType.Number
        else -> KeyboardType.Text
    }
    TextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = label, singleLine = true,
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
internal fun Heading(title: String) {
    Text(title, style = MiuixTheme.textStyles.title2)
}

internal fun Context.copySensitive(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(
            if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
            else "android.content.extra.IS_SENSITIVE",
            true,
        )
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
