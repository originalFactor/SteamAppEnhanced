package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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

internal class AppWork(private val scope: CoroutineScope) {
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
                feedback = when (failure) {
                    is CommunityAuthException, is MissingAccessTokenException ->
                        "Steam needs a new sign-in. Return to the account page and sign in again."
                    is SteamApiException -> "Steam request failed (${failure.eresult})."
                    is IOException -> "Could not complete the request. Check the connection or storage and try again."
                    is IllegalStateException, is IllegalArgumentException -> failure.message?.take(220)
                    else -> null
                } ?: "The operation could not be completed."
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
    val holdLabel = stringResource(R.string.ava_hold_action)
    val confirm = {
        work.dismiss()
        work.run(prompt.block)
    }
    WindowDialog(show = true, title = prompt.title, summary = prompt.summary, onDismissRequest = work::dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.ava_common_cancel), work::dismiss, Modifier.weight(1f))
            if (prompt.hold) {
                Text(
                    text = stringResource(R.string.ava_hold_to, prompt.action),
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).combinedClickable(
                        role = Role.Button,
                        onClick = {},
                        onLongClickLabel = holdLabel,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirm()
                        },
                    ).padding(horizontal = 10.dp, vertical = 14.dp),
                )
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
