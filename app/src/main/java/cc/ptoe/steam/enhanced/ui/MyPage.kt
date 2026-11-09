package cc.ptoe.steam.enhanced.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.AppPreferences
import cc.ptoe.steam.enhanced.app.Appearance
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.store.AccountStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MyPage(
    accounts: List<SteamGuardAccount>,
    account: SteamGuardAccount?,
    vault: AccountVault,
    preferences: AppPreferences,
    work: AppWork,
    onNavigate: (Page) -> Unit,
    onExport: () -> Unit,
    onRemove: suspend () -> Unit,
    onAddAccount: () -> Unit,
    onDevices: () -> Unit,
) {
    var showAccounts by remember { mutableStateOf(false) }
    val deviceUnlockAvailable = rememberDeviceUnlockAvailable()

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.weight(1f).clickable(enabled = account == null && !work.busy, onClick = onAddAccount)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (account != null) SteamAvatar(account, animated = true)
                else Icon(MiuixIcons.ContactsCircle, null, Modifier.size(84.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        account?.displayName ?: stringResource(R.string.ui_add_account),
                        style = MiuixTheme.textStyles.title2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        account?.username
                            ?: stringResource(R.string.ava_first_account_text),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (account != null) IconButton(
                onClick = { showAccounts = true }, enabled = !work.busy,
            ) {
                Icon(MiuixIcons.Replace, stringResource(R.string.ui_switch_account))
            }
        }
    }

    if (account != null && account.steamId > 0L) Card(Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.ui_device_management),
            enabled = !work.busy,
            onClick = onDevices,
        )
        ArrowPreference(title = stringResource(R.string.ava_fam_account_action), enabled = !work.busy, onClick = { onNavigate(Page.FAMILY) })
        ArrowPreference(title = stringResource(R.string.ava_action_market), enabled = !work.busy, onClick = { onNavigate(Page.MARKET) })
        ArrowPreference(title = stringResource(R.string.ava_key_redeem_action), enabled = !work.busy, onClick = { onNavigate(Page.REDEEM) })
    }
    if (account != null) Card(Modifier.fillMaxWidth()) {
        ArrowPreference(title = stringResource(R.string.ava_action_export), enabled = !work.busy && !account.sharedSecret.isNullOrBlank(), onClick = {
            work.ask(work.text(R.string.msg_export_question), work.text(R.string.msg_export_warning), work.text(R.string.msg_choose_file), hold = false) { onExport() }
        })
        ArrowPreference(title = stringResource(R.string.ava_action_remove), enabled = !work.busy, onClick = {
            work.ask(work.text(R.string.msg_remove_question, account.displayName), work.text(R.string.msg_remove_warning), work.text(R.string.msg_remove), block = onRemove)
        })
    }

    if (deviceUnlockAvailable) Column {
        SmallTitle(stringResource(R.string.ui_vault))
        Card(Modifier.fillMaxWidth()) { DeviceUnlockSettings(vault, work) }
    }
    Column {
        SmallTitle(stringResource(R.string.ui_theme_settings))
        Card(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.ui_theme_mode),
                items = listOf(
                    stringResource(R.string.ava_theme_system),
                    stringResource(R.string.ava_theme_light),
                    stringResource(R.string.ava_theme_dark),
                ),
                selectedIndex = preferences.appearance.ordinal,
                onSelectedIndexChange = { preferences.useAppearance(Appearance.entries[it]) },
                enabled = !work.busy,
            )
        }
    }

    OverlayBottomSheet(
        show = showAccounts,
        title = stringResource(R.string.ui_switch_account),
        endAction = {
            IconButton(
                onClick = { showAccounts = false; onAddAccount() },
                enabled = !work.busy,
            ) {
                Icon(MiuixIcons.Add, stringResource(R.string.ui_add_account))
            }
        },
        onDismissRequest = { showAccounts = false },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accounts.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
                accounts.forEach { row ->
                    RadioButtonPreference(
                        title = row.displayName,
                        summary = row.username,
                        selected = account?.let(AccountStore::storageId) == AccountStore.storageId(row),
                        radioButtonLocation = RadioButtonLocation.End,
                        startAction = { Row(Modifier.padding(end = 12.dp)) { SteamAvatar(row, animated = false) } },
                        enabled = !work.busy,
                        onClick = {
                            preferences.useAccount(AccountStore.storageId(row))
                            showAccounts = false
                        },
                    )
                }
            }
        }
    }
}
