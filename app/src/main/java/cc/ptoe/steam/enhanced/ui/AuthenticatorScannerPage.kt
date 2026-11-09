package cc.ptoe.steam.enhanced.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.app.AuthenticatorAccess
import cc.ptoe.steam.enhanced.app.PendingLogin
import cc.ptoe.steam.enhanced.app.PendingLoginFeed
import cc.ptoe.steam.enhanced.app.SteamQrScanGate
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.app.LoginRequestPolling
import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.store.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AuthenticatorScannerPage(
    accounts: List<SteamGuardAccount>,
    account: SteamGuardAccount?,
    services: SteamServices,
    work: AppWork,
    active: Boolean,
    padding: PaddingValues,
    onMyPage: () -> Unit,
    onTransfer: (SteamGuardAccount) -> Unit,
) {
    val access = AuthenticatorAccess.forAccount(account)
    val canApprove = access == AuthenticatorAccess.READY
    val unavailablePrompt = when (access) {
        AuthenticatorAccess.SIGN_IN -> R.string.ui_authenticator_sign_in_prompt
        AuthenticatorAccess.TRANSFER -> R.string.ui_authenticator_transfer_prompt
        AuthenticatorAccess.READY -> null
    }
    fun openSetup() {
        if (access == AuthenticatorAccess.TRANSFER && account != null) onTransfer(account)
        else onMyPage()
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraRequested by rememberSaveable { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    val feed = remember { PendingLoginFeed() }
    val scanGate = remember { SteamQrScanGate() }
    var requests by remember { mutableStateOf(emptyList<PendingLogin>()) }
    var requestsLoaded by remember { mutableStateOf(false) }
    var requestsFailed by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<PendingLogin?>(null) }
    var showRequests by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    fun openRequests() {
        if (!canApprove) return
        showRequests = true
        refreshGeneration++
    }
    val latestAccounts by rememberUpdatedState(accounts)
    var now by remember { mutableStateOf(services.time.currentSteamTime()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraAllowed = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) {
        if (active && !cameraAllowed && !cameraRequested) {
            cameraRequested = true
            permission.launch(Manifest.permission.CAMERA)
        }
        if (active) while (true) {
            now = services.time.currentSteamTime()
            delay(100)
        }
    }
    LaunchedEffect(access, account?.let(AccountStore::storageId)) {
        showRequests = false
        scanned = null
    }
    LaunchedEffect(active, canApprove, accounts.filter { it.session.hasTokens && !it.sharedSecret.isNullOrBlank() }.map(AccountStore::storageId), refreshGeneration) {
        if (!active || !canApprove) return@LaunchedEffect
        LoginRequestPolling().run {
            val eligible = latestAccounts.filter { it.steamId > 0L && !it.sharedSecret.isNullOrBlank() && it.session.hasTokens }
            feed.retainAccounts(eligible.map(AccountStore::storageId).toSet())
            var failed = false
            for (row in eligible) {
                val id = AccountStore.storageId(row)
                try {
                    val sessions = services.read(id) { current ->
                        services.approval.pendingLoginClientIds(current).distinct().take(20).map { client ->
                            feed.items.firstOrNull { it.accountId == id && it.info.clientId == client }?.info
                                ?: services.approval.sessionInfo(current, client)
                        }
                    }
                    feed.update(id, sessions)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed = true
                    dlog("Pending sign-in refresh failed: ${failure.javaClass.simpleName}")
                }
            }
            requests = feed.items
            requestsLoaded = true
            requestsFailed = failed
            failed
        }
    }

    val code = remember(account?.sharedSecret, now / 30) {
        account?.takeIf { !it.sharedSecret.isNullOrBlank() }?.let { runCatching { it.generateCode(now) }.getOrNull() }
    }
    fun scan(raw: String) {
        val current = account ?: return
        if (!canApprove || work.busy || showRequests) return
        val challenge = scanGate.accept(raw, SystemClock.elapsedRealtime()) ?: return
        work.run {
            val id = AccountStore.storageId(current)
            val info = services.read(id) { services.approval.sessionInfo(it, challenge.clientId) }
            scanned = PendingLogin(id, info.copy(version = challenge.version))
            openRequests()
        }
    }

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        if (cameraAllowed && !cameraUnavailable) SteamCameraPreview(
            active = active,
            scanEnabled = active && canApprove && !work.busy && !showRequests && code != null,
            modifier = Modifier.fillMaxSize(),
            onCode = ::scan,
            onUnavailable = { cameraUnavailable = true },
        )
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Color.Black.copy(alpha = 0.4f)))
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Card(
                Modifier.widthIn(max = 660.dp).fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.clickable(enabled = !work.busy, onClick = onMyPage).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SteamAvatar(account, animated = false)
                        Text(account?.displayName ?: stringResource(R.string.ui_add_account), style = MiuixTheme.textStyles.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        code ?: stringResource(R.string.ui_code_placeholder),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = code != null && !work.busy) {
                            code?.let { context.copySensitive("Steam Guard", it) }
                        },
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.title1.copy(fontFamily = FontFamily.Monospace, fontSize = 40.sp, letterSpacing = 5.sp),
                    )
                    if (code != null) SteamCodeProgress(services.time, active)
                    if (work.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    work.feedback?.let { Text(it, style = MiuixTheme.textStyles.body2) }
                }
            }

            if (unavailablePrompt != null) Text(
                stringResource(unavailablePrompt), color = Color.White,
                modifier = Modifier.widthIn(max = 420.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !work.busy, onClick = ::openSetup).padding(16.dp),
                textAlign = TextAlign.Center,
            ) else if (!cameraAllowed || cameraUnavailable) Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (cameraUnavailable) R.string.ui_camera_unavailable else R.string.ui_camera_permission))
                    if (!cameraUnavailable) {
                        Primary(stringResource(R.string.ui_enable_camera), !work.busy) { permission.launch(Manifest.permission.CAMERA) }
                        Entry(stringResource(R.string.ui_open_app_settings), !work.busy) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
            } else Text(
                stringResource(R.string.ui_scan_steam_qr), color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(12.dp),
                textAlign = TextAlign.Center,
            )

            val recent = if (canApprove) requests.firstOrNull() else null
            Card(
                modifier = Modifier.widthIn(max = 660.dp).fillMaxWidth(),
                onClick = if (!work.busy) ({ if (canApprove) openRequests() else openSetup() }) else null,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_recent_login_requests), style = MiuixTheme.textStyles.title3)
                    Text(unavailablePrompt?.let { stringResource(it) } ?: recent?.info?.deviceName?.takeIf(String::isNotBlank) ?: stringResource(
                        when {
                            !requestsLoaded -> R.string.ui_loading_login_requests
                            requestsFailed -> R.string.ui_login_requests_unavailable
                            recent != null -> R.string.ava_approve_title
                            else -> R.string.ava_login_no_pending
                        },
                    ))
                    if (recent != null) {
                        val owner = accounts.firstOrNull { AccountStore.storageId(it) == recent.accountId }
                        Text(listOfNotNull(owner?.displayName, recent.info.location.takeIf(String::isNotBlank)).joinToString(" · "), style = MiuixTheme.textStyles.body2)
                        Text(stringResource(R.string.ui_expand_login_requests, requests.size), color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    OverlayBottomSheet(
        show = showRequests && canApprove,
        title = stringResource(R.string.ui_recent_login_requests),
        onDismissRequest = { showRequests = false; scanned = null },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            work.feedback?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            val visible = (listOfNotNull(scanned) + requests).distinctBy { it.key }
            if (visible.isEmpty()) Text(stringResource(R.string.ava_login_no_pending), Modifier.padding(16.dp))
            for (request in visible) {
                val owner = accounts.firstOrNull { AccountStore.storageId(it) == request.accountId } ?: continue
                key(request.key) {
                    var rememberDevice by remember { mutableStateOf(false) }
                    val approveText = stringResource(R.string.ui_approve_login)
                    val denyText = stringResource(R.string.ui_deny_login)
                    val failureText = stringResource(R.string.ui_login_response_failed)
                    fun respond(approve: Boolean) {
                        work.ask(
                            if (approve) approveText else denyText,
                            listOf(owner.displayName, request.info.deviceName, request.info.location, request.info.ip).filter(String::isNotBlank).joinToString("\n"),
                            if (approve) approveText else denyText,
                        ) {
                            val current = services.session(request.accountId)
                            checkUi(AuthenticatorAccess.forAccount(current) == AuthenticatorAccess.READY) { work.text(R.string.msg_transfer_before_approval) }
                            checkUi(services.approval.respondToSession(current, request.info.version, request.info.clientId, approve, rememberDevice)) {
                                failureText
                            }
                            feed.resolve(request.key)
                            requests = feed.items
                            if (scanned?.key == request.key) scanned = null
                            if (requests.isEmpty() && scanned == null) showRequests = false
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(owner.displayName, color = MiuixTheme.colorScheme.primary)
                            Text(request.info.deviceName.ifBlank { stringResource(R.string.ava_approve_title) }, style = MiuixTheme.textStyles.title3)
                            if (request.info.location.isNotBlank()) Text(request.info.location)
                            if (request.info.ip.isNotBlank()) Text(request.info.ip)
                            SwitchPreference(
                                title = stringResource(R.string.ui_remember_login_device), checked = rememberDevice,
                                onCheckedChange = { rememberDevice = it }, enabled = !work.busy,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(denyText, { respond(false) }, Modifier.weight(1f), enabled = !work.busy)
                                TextButton(approveText, { respond(true) }, Modifier.weight(1f), enabled = !work.busy, colors = ButtonDefaults.textButtonColorsPrimary())
                            }
                        }
                    }
                }
            }
        }
    }
}
