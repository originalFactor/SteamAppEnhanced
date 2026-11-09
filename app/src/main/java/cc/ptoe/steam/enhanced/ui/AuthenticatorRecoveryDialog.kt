package cc.ptoe.steam.enhanced.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AuthenticatorRecoveryDialog(store: AuthenticatorRecoveryStore, work: AppWork) {
    val pending by store.pending.collectAsState()
    val account = pending ?: return
    val context = LocalContext.current
    WindowDialog(
        show = true,
        title = stringResource(R.string.ui_recovery_save_title),
        onDismissRequest = {},
    ) {
        Text(stringResource(R.string.ui_recovery_save_failed))
        work.feedback?.let { Text(it) }
        Entry(stringResource(R.string.ui_recovery_copy_file), !work.busy) {
            context.copySensitive(work.text(R.string.ui_recovery_copy_file), account.toExportJson().toString())
        }
        Primary(stringResource(R.string.ui_recovery_retry_save), !work.busy) {
            work.run { store.retry() }
        }
    }
}
