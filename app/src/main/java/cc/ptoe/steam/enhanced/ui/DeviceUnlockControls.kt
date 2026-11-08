package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.DeviceUnlock

@Composable
internal fun DeviceUnlockSettings(vault: AccountVault, work: AppWork) {
    if (Build.VERSION.SDK_INT < 30) return
    val unlock = rememberDeviceUnlock() ?: return
    var enabled by remember(unlock) { mutableStateOf(unlock.enabled) }
    if (enabled) {
        Entry("Disable device unlock", !work.busy) {
            work.run { unlock.disable(); enabled = false }
        }
    } else if (unlock.available) {
        Entry(stringResource(R.string.ava_settings_biometric), !work.busy) {
            work.run {
                val key = vault.copyVaultKey()
                try { enabled = unlock.enable(key) } finally { key.fill(0) }
            }
        }
    }
}

@Composable
internal fun DeviceUnlockButton(vault: AccountVault, work: AppWork) {
    if (Build.VERSION.SDK_INT < 30) return
    val unlock = rememberDeviceUnlock() ?: return
    if (unlock.enabled && unlock.available) Entry(stringResource(R.string.ava_unlock_with_biometric), !work.busy) {
        work.run {
            val key = unlock.unlock() ?: return@run
            try { vault.openWithDeviceKey(key) } finally { key.fill(0) }
        }
    }
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
