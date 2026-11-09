package cc.ptoe.steam.enhanced.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.core.model.DeviceSessionList
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.store.AccountStore
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun DeviceManagementPage(
    account: SteamGuardAccount,
    services: SteamServices,
    work: AppWork,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
) {
    val id = AccountStore.storageId(account)
    var list by remember { mutableStateOf<DeviceSessionList?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun load() { list = services.read(id, services.devices::enumerate) }

    fun refresh() {
        work.run {
            isRefreshing = true
            try { load() } finally { isRefreshing = false }
        }
    }

    LaunchedEffect(id) { refresh() }
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = ::refresh,
        modifier = modifier.fillMaxSize(),
        topAppBarScrollBehavior = scrollBehavior,
        refreshTexts = listOf(
            stringResource(R.string.ui_pull_refresh), stringResource(R.string.ui_release_refresh),
            stringResource(R.string.ui_refreshing), stringResource(R.string.ui_refresh_finished),
        ),
    ) {
        Column(
            Modifier.overScrollVertical().fillMaxSize().verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            work.feedback?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            val current = list
            if (current != null) {
                if (current.devices.isEmpty()) Text(stringResource(R.string.ui_no_devices))
                for (device in current.devices) Panel {
                    val name = device.description.ifBlank { work.text(R.string.msg_device_name) }
                    Heading(name)
                    Text(
                        if (current.isCurrent(device)) work.text(R.string.msg_device_current)
                        else if (device.loggedIn) work.text(R.string.msg_device_signed_in) else work.text(R.string.msg_device_signed_out),
                    )
                    device.lastSeen?.locationLabel?.takeIf(String::isNotBlank)?.let { Text(it) }
                    val timestamp = device.lastSeen?.time?.takeIf { it > 0 } ?: device.timeUpdated
                    if (timestamp > 0) Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp * 1000)))
                    if (!current.isCurrent(device) && current.requestingTokenId.isNotBlank()) {
                        Entry(stringResource(R.string.ava_device_revoke_action), !work.busy && !account.sharedSecret.isNullOrBlank()) {
                            work.ask(work.text(R.string.msg_device_revoke_question, name), work.text(R.string.msg_device_revoke_warning), work.text(R.string.msg_revoke)) {
                                val authenticated = services.session(id)
                                val fresh = services.devices.enumerate(authenticated)
                                val selected = fresh.devices.firstOrNull { it.tokenId == device.tokenId }
                                    ?: throw UiFailure(work.text(R.string.msg_device_missing))
                                checkUi(fresh.requestingTokenId.isNotBlank() && !fresh.isCurrent(selected)) { work.text(R.string.msg_device_current_protected) }
                                services.devices.revoke(authenticated, selected)
                                load()
                                work.feedback = work.text(R.string.msg_device_revoked)
                            }
                        }
                    }
                }
            }
        }
    }
}
