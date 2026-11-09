package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.AppPreferences
import cc.ptoe.steam.enhanced.sync.SyncChoice
import cc.ptoe.steam.enhanced.sync.SyncConfig
import cc.ptoe.steam.enhanced.sync.SyncConflict
import cc.ptoe.steam.enhanced.sync.SyncRecovery
import cc.ptoe.steam.enhanced.sync.VaultSync
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SyncPage(vault: AccountVault, preferences: AppPreferences, work: AppWork) {
    val sync = remember(vault) { VaultSync(vault) }
    var saved by remember { mutableStateOf<SyncConfig?>(null) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var conflicts by remember { mutableStateOf(emptyList<SyncConflict>()) }
    var choices by remember { mutableStateOf(emptyMap<Long, SyncChoice>()) }
    var recovery by remember { mutableStateOf(emptyList<SyncRecovery>()) }

    LaunchedEffect(sync) {
        work.run {
            saved = sync.config()
            recovery = sync.recoverable()
            saved?.let {
                url = it.url
                user = it.user
            }
        }
    }

    fun invalidateChoices() {
        conflicts = emptyList()
        choices = emptyMap()
    }

    fun runSync() {
        val previous = saved?.takeIf { it.url == url.trim() }
        val config = SyncConfig(
            url.trim(), user.trim(), password.ifEmpty { previous?.password.orEmpty() },
            passphrase.ifEmpty { previous?.passphrase.orEmpty() },
            previous?.synced == true, previous?.baseline.orEmpty(),
        )
        work.run {
            val result = sync.sync(config, choices)
            conflicts = result.conflicts
            choices = emptyMap()
            if (conflicts.isEmpty()) {
                saved = sync.config()
                recovery = sync.recoverable()
                password = ""
                passphrase = ""
                work.feedback = work.text(R.string.msg_sync_complete, result.pushed, result.pulled)
            } else {
                work.feedback = work.text(R.string.msg_sync_conflict)
            }
        }
    }

    Panel {
        Heading(work.text(R.string.msg_sync_folder))
        Text(work.text(R.string.msg_sync_description))
        Field(stringResource(R.string.ava_sync_server_url_label), url, enabled = !work.busy) { url = it; invalidateChoices() }
        Field(stringResource(R.string.ava_sync_server_user_label), user, enabled = !work.busy) { user = it; invalidateChoices() }
        Field(if (saved == null) work.text(R.string.msg_sync_password) else work.text(R.string.msg_sync_password_keep),
            password, secret = true, enabled = !work.busy) { password = it; invalidateChoices() }
        Field(if (saved == null) work.text(R.string.msg_sync_passphrase) else work.text(R.string.msg_sync_passphrase_keep),
            passphrase, secret = true, enabled = !work.busy) { passphrase = it; invalidateChoices() }
        if (conflicts.isEmpty()) Primary(
            stringResource(R.string.ava_sync_now_button),
            !work.busy && url.isNotBlank() && (passphrase.length >= 8 || saved?.url == url.trim()),
            ::runSync,
        )
        if (saved != null) {
            SwitchPreference(
                checked = preferences.autoSync, onCheckedChange = preferences::useAutoSync,
                title = work.text(R.string.msg_sync_while_open), enabled = !work.busy,
            )
            Entry(stringResource(R.string.ava_sync_disconnect), !work.busy) {
                work.ask(work.text(R.string.msg_sync_disconnect_question), work.text(R.string.msg_sync_disconnect_warning), work.text(R.string.msg_disconnect), hold = false) {
                    sync.disconnect()
                    saved = null
                    preferences.useAutoSync(false)
                    password = ""; passphrase = ""
                    invalidateChoices()
                }
            }
        }
    }

    for (conflict in conflicts) Panel {
        Heading(conflict.local?.displayName ?: conflict.remote?.displayName ?: work.text(R.string.msg_account_id, conflict.id.toString()))
        Text(work.text(R.string.msg_sync_local_version, conflict.local?.displayName ?: work.text(R.string.msg_sync_deleted)))
        Text(work.text(R.string.msg_sync_remote_version, conflict.remote?.displayName ?: work.text(R.string.msg_sync_deleted)))
        val chosen = choices[conflict.id]?.keepLocal
        Entry(if (chosen == true) work.text(R.string.msg_sync_keep_local_selected) else work.text(R.string.msg_sync_keep_local), !work.busy) {
            choices = choices + (conflict.id to SyncChoice(conflict.localHash, conflict.remoteHash, true))
        }
        Entry(if (chosen == false) work.text(R.string.msg_sync_keep_remote_selected) else work.text(R.string.msg_sync_keep_remote), !work.busy) {
            choices = choices + (conflict.id to SyncChoice(conflict.localHash, conflict.remoteHash, false))
        }
    }
    if (conflicts.isNotEmpty()) {
        Primary(stringResource(R.string.ava_sync_now_button_84), !work.busy && conflicts.size == choices.size, ::runSync)
    }
    if (recovery.isNotEmpty()) Panel {
        Heading(work.text(R.string.msg_sync_recovery))
        Text(work.text(R.string.msg_sync_recovery_description))
        for (entry in recovery) {
            val date = java.text.DateFormat.getDateInstance().format(java.util.Date(entry.savedAt))
            Entry("${entry.account.displayName} · $date", !work.busy) {
                work.ask(work.text(R.string.msg_sync_restore_question), work.text(R.string.msg_sync_restore_warning), work.text(R.string.msg_restore)) {
                    sync.restore(entry)
                    work.feedback = work.text(R.string.msg_sync_restored)
                }
            }
        }
    }
}
