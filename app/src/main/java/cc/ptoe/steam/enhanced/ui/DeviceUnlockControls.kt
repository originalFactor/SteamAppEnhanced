package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.DeviceUnlock
import cc.ptoe.steam.enhanced.app.DeviceVaultUnlock
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun rememberDeviceUnlockAvailable(): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val unlock = rememberDeviceUnlock() ?: return false
    val lifecycleOwner = LocalLifecycleOwner.current
    var available by remember(unlock) { mutableStateOf(unlock.available) }
    DisposableEffect(lifecycleOwner, unlock) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) available = unlock.available
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return available
}

@Composable
internal fun DeviceUnlockSettings(vault: AccountVault, work: AppWork) {
    if (Build.VERSION.SDK_INT < 30) {
        SwitchPreference(
            title = stringResource(R.string.ui_biometric_unlock),
            summary = stringResource(R.string.ui_biometric_unavailable),
            checked = false, onCheckedChange = {}, enabled = false,
        )
        return
    }
    val unlock = rememberDeviceUnlock() ?: return
    var enabled by remember(unlock) { mutableStateOf(unlock.enabled) }
    SwitchPreference(
        title = stringResource(R.string.ui_biometric_unlock),
        summary = stringResource(
            if (enabled || unlock.available) R.string.ui_biometric_summary
            else R.string.ui_biometric_unavailable,
        ),
        checked = enabled,
        enabled = !work.busy && (enabled || unlock.available),
        onCheckedChange = { checked ->
            work.run {
                if (checked) {
                    val key = vault.copyVaultKey()
                    try { enabled = unlock.enable(key) } finally { key.fill(0) }
                } else {
                    unlock.disable()
                    enabled = false
                }
            }
        },
    )
}

@Composable
internal fun rememberVaultDeviceUnlock(): DeviceVaultUnlock? {
    if (Build.VERSION.SDK_INT < 30) return null
    return rememberDeviceUnlock()
}

@RequiresApi(30)
@Composable
private fun rememberDeviceUnlock(): DeviceUnlock? {
    val context = LocalContext.current
    return remember(context) { context.activity()?.let(::DeviceUnlock) }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
