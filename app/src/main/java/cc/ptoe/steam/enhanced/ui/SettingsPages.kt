package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.AppPreferences
import cc.ptoe.steam.enhanced.app.Appearance
import cc.ptoe.steam.enhanced.app.Skin
import cc.ptoe.steam.enhanced.core.DebugLog
import cc.ptoe.steam.enhanced.sync.SyncChoice
import cc.ptoe.steam.enhanced.sync.SyncConfig
import cc.ptoe.steam.enhanced.sync.SyncConflict
import cc.ptoe.steam.enhanced.sync.SyncRecovery
import cc.ptoe.steam.enhanced.sync.VaultSync
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsPage(
    vault: AccountVault,
    preferences: AppPreferences,
    work: AppWork,
    onSync: () -> Unit,
    onLock: () -> Unit,
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf<List<String>?>(null) }

    Panel {
        Heading("Vault")
        Entry(stringResource(R.string.ava_lock_now), !work.busy, onLock)
        DeviceUnlockSettings(vault, work)
        Entry(stringResource(R.string.ava_sync_title_76), !work.busy, onSync)
    }

    Panel {
        Heading(stringResource(R.string.ava_settings_appearance))
        TabRow(listOf(stringResource(R.string.ava_theme_system), stringResource(R.string.ava_theme_light), stringResource(R.string.ava_theme_dark)), preferences.appearance.ordinal, { preferences.useAppearance(Appearance.entries[it]) })
        TabRow(listOf("Steam", stringResource(R.string.ava_theme_neon), stringResource(R.string.ava_theme_pixel)), preferences.skin.ordinal, { preferences.useSkin(Skin.entries[it]) })
    }

    Panel {
        Heading("Steam requests")
        SwitchPreference(
            checked = preferences.pollLogins, onCheckedChange = preferences::useLoginPolling,
            title = "Check for sign-ins on the home screen",
            summary = "While the vault is open, check accounts that have a Steam session.",
        )
    }

    Panel {
        Heading("Help and diagnostics")
        Entry("Show getting started on the home screen", !work.busy) { preferences.showGuide(true) }
        Entry(if (logs == null) "Show debug log" else "Hide debug log", !work.busy) {
            logs = if (logs == null) DebugLog.lines().takeLast(80) else null
        }
        logs?.let { lines ->
            Entry(stringResource(R.string.ava_debug_copy_all)) { context.copySensitive("Steam Enhanced debug log", DebugLog.dump()) }
            Entry("Clear debug log") { DebugLog.clear(); logs = emptyList() }
            if (lines.isEmpty()) Text(stringResource(R.string.ava_debug_empty))
            lines.forEach { Text(it) }
        }
    }
}

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
                work.feedback = "Sync complete. ${result.pushed} remote and ${result.pulled} local changes."
            } else {
                work.feedback = "Choose a version for each conflicting account."
            }
        }
    }

    Panel {
        Heading("WebDAV folder")
        Text("Use an existing HTTPS folder. Accounts are encrypted here before upload. Steam sessions and saved Steam passwords stay on this device.")
        Field(stringResource(R.string.ava_sync_server_url_label), url, enabled = !work.busy) { url = it; invalidateChoices() }
        Field(stringResource(R.string.ava_sync_server_user_label), user, enabled = !work.busy) { user = it; invalidateChoices() }
        Field(if (saved == null) "WebDAV password" else "WebDAV password (leave blank to keep)",
            password, secret = true, enabled = !work.busy) { password = it; invalidateChoices() }
        Field(if (saved == null) "Sync passphrase" else "Sync passphrase (leave blank to keep)",
            passphrase, secret = true, enabled = !work.busy) { passphrase = it; invalidateChoices() }
        if (conflicts.isEmpty()) Primary(
            stringResource(R.string.ava_sync_now_button),
            !work.busy && url.isNotBlank() && (passphrase.length >= 8 || saved?.url == url.trim()),
            ::runSync,
        )
        if (saved != null) {
            SwitchPreference(
                checked = preferences.autoSync, onCheckedChange = preferences::useAutoSync,
                title = "Sync while the vault is open", enabled = !work.busy,
            )
            Entry(stringResource(R.string.ava_sync_disconnect), !work.busy) {
                work.ask("Disconnect WebDAV?", "The encrypted copy stays on the server.", "Disconnect", hold = false) {
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
        Heading(conflict.local?.displayName ?: conflict.remote?.displayName ?: "Account ${conflict.id}")
        Text("This device: ${conflict.local?.displayName ?: "Deleted"}")
        Text("WebDAV copy: ${conflict.remote?.displayName ?: "Deleted"}")
        val chosen = choices[conflict.id]?.keepLocal
        Entry(if (chosen == true) "Keep this device ✓" else "Keep this device", !work.busy) {
            choices = choices + (conflict.id to SyncChoice(conflict.localHash, conflict.remoteHash, true))
        }
        Entry(if (chosen == false) "Keep WebDAV copy ✓" else "Keep WebDAV copy", !work.busy) {
            choices = choices + (conflict.id to SyncChoice(conflict.localHash, conflict.remoteHash, false))
        }
    }
    if (conflicts.isNotEmpty()) {
        Primary(stringResource(R.string.ava_sync_now_button_84), !work.busy && conflicts.size == choices.size, ::runSync)
    }
    if (recovery.isNotEmpty()) Panel {
        Heading("Sync recovery")
        Text("Recover recent account versions removed or replaced during sync.")
        for (entry in recovery) {
            val date = java.text.DateFormat.getDateInstance().format(java.util.Date(entry.savedAt))
            Entry("${entry.account.displayName} · $date", !work.busy) {
                work.ask("Restore account version?", "The saved version will replace the local account. Sync will pick up the change next time.", "Restore") {
                    sync.restore(entry)
                    work.feedback = "Account version restored."
                }
            }
        }
    }
}
