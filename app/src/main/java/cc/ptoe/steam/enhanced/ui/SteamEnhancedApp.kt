package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import cc.ptoe.steam.enhanced.app.steamLanguage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.SelectedMaFiles
import cc.ptoe.steam.enhanced.app.SteamAppState
import cc.ptoe.steam.enhanced.app.readMaFiles
import cc.ptoe.steam.enhanced.app.writeDocument
import cc.ptoe.steam.enhanced.core.SteamTotp
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.net.SteamTime
import cc.ptoe.steam.enhanced.store.AccountStore
import cc.ptoe.steam.enhanced.ui.theme.SteamEnhancedTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults

internal enum class Page(val title: Int) {
    HOME(R.string.ui_authenticator),
    LOGIN(R.string.ava_login_title),
    LOGIN_QR(R.string.ava_login_via_qr),
    LOGIN_VERIFY(R.string.ui_two_factor_title),
    LOGIN_RECOVER(R.string.ui_recovery_start_title),
    ENROLL(R.string.ui_transfer_to_app),
    PENDING(R.string.ava_pending_title),
    DEVICES(R.string.ui_device_management),
    FAMILY(R.string.ava_fam_account_action),
    MARKET(R.string.ava_action_market),
    REDEEM(R.string.ava_key_redeem_title),
    ME(R.string.ui_me),
    SYNC(R.string.ava_sync_title),
}

@Composable
internal fun SteamEnhancedApp(app: SteamAppState) {
    val preferences = app.preferences
    SteamEnhancedTheme(preferences.appearance) {
        AppContent(app)
    }
}

@Composable
private fun AppContent(app: SteamAppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = app.vault
    val deviceUnlock = rememberVaultDeviceUnlock()
    val services = app.services
    val sync = app.sync
    val preferences = app.preferences
    val resources by rememberUpdatedState(LocalResources.current)
    val work = remember(scope) { AppWork(scope) { resources } }
    SideEffect { services.api.steamLanguage = steamLanguage(resources.configuration.locales[0]) }
    val state by vault.state.collectAsState()
    var loginQrAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var rootPage by rememberSaveable { mutableStateOf(Page.HOME) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var transferAfterLogin by rememberSaveable { mutableStateOf(false) }
    var bundle by remember { mutableStateOf<SelectedMaFiles?>(null) }
    var exportId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportRecovery by rememberSaveable { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    var initializing by remember(vault) { mutableStateOf(true) }
    val cameraHome = state.unlocked && page == Page.HOME && bundle == null && !initializing && !state.hasRecovery
    val pendingHome = state.unlocked && page == Page.PENDING && bundle == null && !initializing
    val devicesPage = state.unlocked && page == Page.DEVICES && bundle == null && !initializing
    DisposableEffect(context, cameraHome) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val wasLight = controller?.isAppearanceLightStatusBars
        if (cameraHome) controller?.isAppearanceLightStatusBars = false
        onDispose { if (wasLight != null) controller?.isAppearanceLightStatusBars = wasLight }
    }
    val pageStates = rememberSaveableStateHolder()
    val scrollBehavior = key(page) { MiuixScrollBehavior() }
    val isRootPage = page == Page.HOME || page == Page.PENDING || page == Page.ME
    val account = state.accounts.firstOrNull { AccountStore.storageId(it) == selected }
    val profileAccount = state.accounts.firstOrNull { AccountStore.storageId(it) == preferences.activeAccountId }
        ?: state.accounts.firstOrNull()

    LaunchedEffect(state.unlocked, profileAccount?.let(AccountStore::storageId)) {
        if (state.unlocked) preferences.useAccount(profileAccount?.let(AccountStore::storageId))
    }

    LaunchedEffect(vault) {
        work.run {
            try {
                app.unlockController.onOpen(deviceUnlock)
            } finally {
                initializing = false
            }
        }
    }

    fun navigate(destination: Page) {
        work.feedback = null
        if (destination !in listOf(Page.LOGIN, Page.LOGIN_QR, Page.LOGIN_VERIFY, Page.LOGIN_RECOVER)) transferAfterLogin = false
        if (destination == Page.HOME || destination == Page.PENDING || destination == Page.ME) rootPage = destination
        page = destination
    }

    DisposableEffect(context, vault) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        var stoppedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    if (stoppedAt > 0L && android.os.SystemClock.elapsedRealtime() - stoppedAt >= 120_000 &&
                        vault.state.value.unlocked && !work.busy && app.authenticatorRecovery.pending.value == null && exportId == null && !exportRecovery
                    ) {
                        work.run {
                            vault.lock()
                            selected = null
                            bundle = null
                            navigate(Page.HOME)
                            app.unlockController.unlock(deviceUnlock)
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    stoppedAt = android.os.SystemClock.elapsedRealtime()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        work.run {
            val incoming = readMaFiles(context.contentResolver, uris)
            if (incoming.manifest?.encrypted == true) bundle = incoming
            else {
                val report = vault.import(incoming.files, incoming.manifest, null)
                work.feedback = work.text(R.string.msg_import_result, report.imported, report.skipped, report.unreadable)
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId
        val recovery = exportRecovery
        exportId = null
        exportRecovery = false
        if (uri != null && state.unlocked) work.run {
            val value = if (recovery) vault.recoveryAccount()
            else vault.state.value.accounts.firstOrNull { AccountStore.storageId(it) == id }
            checkNotNullUi(value) { work.text(R.string.msg_export_unavailable) }
            writeDocument(context.contentResolver, uri, value.toExportJson().toString())
            work.feedback = work.text(R.string.msg_export_done)
        }
    }

    fun goBack() {
        when {
            bundle != null -> bundle = null
            page == Page.LOGIN_RECOVER -> navigate(Page.LOGIN_VERIFY)
            page == Page.LOGIN_QR || page == Page.LOGIN_VERIFY -> navigate(Page.LOGIN)
            page == Page.SYNC -> navigate(Page.ME)
            page == Page.DEVICES -> { selected = null; navigate(Page.ME) }
            page == Page.ENROLL -> { selected = null; navigate(Page.HOME) }
            page == Page.LOGIN && transferAfterLogin -> { selected = null; navigate(Page.HOME) }
            page == Page.FAMILY || page == Page.MARKET || page == Page.REDEEM -> { selected = null; navigate(Page.ME) }
            else -> {
                selected = null
                navigate(if (isRootPage) Page.HOME else rootPage)
            }
        }
    }

    BackHandler(enabled = work.busy || state.unlocked && (page != Page.HOME || bundle != null)) {
        if (!work.busy) goBack()
    }
    LaunchedEffect(state.unlocked, foreground) {
        if (!state.unlocked) {
            page = Page.HOME
            selected = null
            transferAfterLogin = false
        }
        if (state.unlocked && foreground) {
            while (true) {
                services.time.align()
                delay(15 * 60 * 1000)
            }
        }
    }
    LaunchedEffect(state.unlocked, foreground, state.accounts.map(AccountStore::storageId)) {
        if (!state.unlocked || !foreground) return@LaunchedEffect
        while (true) {
            services.profiles.refreshDue()
            delay(60_000)
        }
    }
    LaunchedEffect(state.unlocked, foreground, page == Page.SYNC, preferences.autoSync) {
        if (!state.unlocked || !foreground || page == Page.SYNC || !preferences.autoSync) return@LaunchedEffect
        while (true) {
            delay(5 * 60_000)
            if (page == Page.HOME && !work.busy) work.run {
                val config = sync.config() ?: return@run
                val result = sync.sync(config)
                if (result.conflicts.isNotEmpty()) work.feedback = work.text(R.string.msg_sync_conflict)
            }
        }
    }

    Scaffold(
        modifier = if (cameraHome || pendingHome) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = if (cameraHome) Color.Transparent else MiuixTheme.colorScheme.surface,
        topBar = {
            if (!cameraHome && !pendingHome) TopAppBar(
                scrollBehavior = scrollBehavior,
                title = when {
                    !state.unlocked -> stringResource(R.string.app_name)
                    bundle != null -> stringResource(R.string.ava_sda_import_action)
                    else -> stringResource(page.title)
                },
                actions = {
                    val scan = loginQrAction
                    if (state.unlocked && page == Page.LOGIN && bundle == null && scan != null) {
                        IconButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !work.busy) {
                            Icon(MiuixIcons.Import, stringResource(R.string.ava_welcome_import_cta))
                        }
                        IconButton(onClick = scan, enabled = !work.busy) {
                            Icon(MiuixIcons.Scan, stringResource(R.string.ava_login_via_qr))
                        }
                    }
                },
                navigationIcon = {
                    if (state.unlocked && (!isRootPage || bundle != null)) {
                        IconButton(onClick = ::goBack, enabled = !work.busy) {
                            Icon(MiuixIcons.Back, stringResource(R.string.ava_back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.unlocked && isRootPage && bundle == null) {
                NavigationBar(showDivider = false) {
                    val colors = NavigationBarDefaults.navigationBarItemColors(
                        selectedContentColor = MiuixTheme.colorScheme.primary,
                    )
                    listOf(
                        Page.HOME to MiuixIcons.Lock,
                        Page.PENDING to MiuixIcons.Tasks,
                        Page.ME to MiuixIcons.ContactsCircle,
                    ).forEach { (destination, icon) ->
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { selected = null; navigate(destination) },
                            icon = icon,
                            label = stringResource(destination.title),
                            enabled = !work.busy,
                            colors = colors,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (cameraHome) AuthenticatorScannerPage(
            accounts = state.accounts,
            account = profileAccount,
            services = services,
            work = work,
            active = foreground,
            padding = padding,
            onMyPage = { selected = null; navigate(Page.ME) },
            onTransfer = {
                selected = AccountStore.storageId(it)
                transferAfterLogin = !it.session.hasTokens
                navigate(if (transferAfterLogin) Page.LOGIN else Page.ENROLL)
            },
        ) else if (pendingHome) Box(
            Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter,
        ) {
            if (profileAccount != null && profileAccount.steamId > 0L) {
                key(AccountStore.storageId(profileAccount)) {
                    PendingPage(profileAccount, services, work, Modifier.widthIn(max = 660.dp).fillMaxSize())
                }
            } else Box(
                Modifier.fillMaxSize().background(MiuixTheme.colorScheme.windowDimming),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
                    onClick = if (!work.busy) ({ selected = null; navigate(Page.LOGIN) }) else null,
                    colors = CardDefaults.defaultColors(color = Color.Black.copy(alpha = 0.55f)),
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(MiuixIcons.Info, null, Modifier.size(32.dp), tint = Color.White)
                        Text(
                            stringResource(R.string.ui_authenticator_sign_in_prompt),
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }
        } else if (devicesPage) Box(
            Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter,
        ) {
            if (profileAccount != null && profileAccount.steamId > 0L) {
                key(AccountStore.storageId(profileAccount)) {
                    DeviceManagementPage(
                        profileAccount, services, work,
                        modifier = Modifier.widthIn(max = 660.dp).fillMaxSize(),
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        } else if (state.unlocked && bundle == null && !initializing && page in listOf(Page.ME, Page.FAMILY, Page.MARKET, Page.LOGIN, Page.LOGIN_QR, Page.LOGIN_VERIFY, Page.LOGIN_RECOVER)) Box(
            Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter,
        ) {
            // Keep the live password session through verification and recovery routes.
            val signInPage = if (page == Page.LOGIN_VERIFY || page == Page.LOGIN_RECOVER) Page.LOGIN else page
            key(signInPage, if (signInPage == Page.LOGIN || signInPage == Page.LOGIN_QR) selected else profileAccount?.let(AccountStore::storageId)) {
                when (page) {
                    Page.LOGIN, Page.LOGIN_QR, Page.LOGIN_VERIFY, Page.LOGIN_RECOVER -> pageStates.SaveableStateProvider("signin:$signInPage:$selected") {
                        LoginPage(
                            vault, services, work, account, app.authenticatorRecovery,
                            modifier = Modifier.widthIn(max = 660.dp).fillMaxSize(),
                            qrOnly = page == Page.LOGIN_QR,
                            verificationPage = page == Page.LOGIN_VERIFY,
                            recoveryPage = page == Page.LOGIN_RECOVER,
                            onVerificationRequired = { navigate(Page.LOGIN_VERIFY) },
                            onRecoveryStarted = { navigate(Page.LOGIN_RECOVER) },
                            onBackToPassword = { navigate(Page.LOGIN) },
                            onOpenQr = { navigate(Page.LOGIN_QR) },
                            onQrActionChanged = { loginQrAction = it },
                        ) {
                            preferences.useAccount(AccountStore.storageId(it))
                            selected = AccountStore.storageId(it)
                            val destination = if (transferAfterLogin && it.sharedSecret.isNullOrBlank()) Page.ENROLL else Page.ME
                            transferAfterLogin = false
                            navigate(destination)
                        }
                    }
                    Page.ME -> RefreshableColumn(work, onRefresh = {
                        profileAccount?.let { services.profiles.refresh(AccountStore.storageId(it), force = true) }
                    }, scrollBehavior = scrollBehavior) {
                        MyPage(
                            state.accounts, profileAccount, vault, preferences, work,
                            onNavigate = { destination -> selected = profileAccount?.let(AccountStore::storageId); navigate(destination) },
                            onExport = {
                                profileAccount?.let { current ->
                                    exportId = AccountStore.storageId(current)
                                    val filename = (current.accountName ?: current.steamId.toString()).replace(Regex("[^A-Za-z0-9_.-]"), "_")
                                    export.launch("$filename.maFile")
                                }
                            },
                            onRemove = { profileAccount?.let { vault.remove(it) }; selected = null },
                            onAddAccount = { selected = null; navigate(Page.LOGIN) },
                            onDevices = { selected = null; navigate(Page.DEVICES) },
                        )
                    }
                    Page.FAMILY -> profileAccount?.let { FamilyPage(it, services, work, scrollBehavior, state.accounts) }
                    Page.MARKET -> profileAccount?.let { MarketPage(it, services, work, scrollBehavior) }
                    else -> Unit
                }
            }
        } else Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            pageStates.SaveableStateProvider("${state.unlocked}:$page:$selected:${bundle != null}") {
                Column(
                    Modifier.widthIn(max = 660.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (work.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    work.feedback?.let { Text(it, style = MiuixTheme.textStyles.body2) }

                    val incoming = bundle
                    when {
                        initializing -> Panel {
                            Heading(stringResource(R.string.ui_preparing_vault))
                        }
                        !state.unlocked -> UnlockPanel(
                            vault, state.initialized, work,
                            requiresDeviceUnlock = deviceUnlock?.enabled == true,
                            onUnlock = { app.unlockController.unlock(deviceUnlock) },
                        )
                        incoming != null -> {
                            var password by remember { mutableStateOf("") }
                            Text(work.text(R.string.msg_import_sda_description))
                            Field(work.text(R.string.msg_import_sda_password), password, secret = true, enabled = !work.busy) { password = it }
                            Primary(work.text(R.string.msg_import_decrypt), password.isNotEmpty() && !work.busy) {
                                work.run {
                                    val report = vault.import(incoming.files, incoming.manifest, password)
                                    if (report.imported == 0 && report.skipped == 0) {
                                        work.feedback = work.text(R.string.msg_import_no_files)
                                    } else {
                                        bundle = null
                                        work.feedback = work.text(R.string.msg_import_result, report.imported, report.skipped, report.unreadable)
                                    }
                                }
                            }
                        }
                        page == Page.HOME -> {
                            if (state.unreadable > 0) Text(work.text(R.string.msg_vault_unreadable_count, state.unreadable))
                            if (state.hasRecovery) Panel {
                                Heading(work.text(R.string.msg_vault_recovery))
                                Text(work.text(R.string.msg_vault_recovery_description))
                                Primary(work.text(R.string.msg_vault_restore), !work.busy) {
                                    work.run {
                                        val recovered = vault.recoveryAccount() ?: throw UiFailure(work.text(R.string.msg_vault_recovery_unreadable))
                                        vault.saveAuthenticator(recovered)
                                        work.feedback = work.text(R.string.msg_vault_recovered)
                                    }
                                }
                                Entry(work.text(R.string.msg_export_recovery), !work.busy) {
                                    work.ask(work.text(R.string.msg_export_recovery_question), work.text(R.string.msg_export_secrets), work.text(R.string.msg_choose_file), hold = false) {
                                        checkNotNullUi(vault.recoveryAccount()) { work.text(R.string.msg_vault_recovery_unreadable) }
                                        exportRecovery = true
                                        export.launch("steam-guard-recovery.maFile")
                                    }
                                }
                            }
                            if (preferences.showGuide) Panel {
                                Heading(stringResource(R.string.ava_getting_started))
                                Text(stringResource(R.string.ava_getting_started_text))
                                Entry(stringResource(R.string.ava_sync_done)) { preferences.showGuide(false) }
                            }
                            HomeAccounts(
                                state.accounts, services.time, work,
                                onSignIn = { selected = null; navigate(Page.LOGIN) },
                                onAccount = {
                                    selected = AccountStore.storageId(it)
                                    preferences.useAccount(AccountStore.storageId(it))
                                    navigate(Page.ME)
                                },
                                onTransfer = {
                                    selected = AccountStore.storageId(it)
                                    transferAfterLogin = !it.session.hasTokens
                                    navigate(if (transferAfterLogin) Page.LOGIN else Page.ENROLL)
                                },
                            )
                        }
                        page == Page.SYNC -> SyncPage(vault, preferences, work)
                        account != null -> when (page) {
                            Page.ENROLL -> EnrollmentPage(account, vault, services, work, transferOnly = true)
                            Page.REDEEM -> RedeemPage(account, services, work)
                            else -> Unit
                        }
                        else -> Entry(stringResource(R.string.ui_me)) { navigate(Page.ME) }
                    }
                }
            }
        }
        WorkPrompt(work)
        AuthenticatorRecoveryDialog(app.authenticatorRecovery, work)
    }
}

@Composable
private fun UnlockPanel(
    vault: AccountVault,
    initialized: Boolean,
    work: AppWork,
    requiresDeviceUnlock: Boolean,
    onUnlock: suspend () -> Unit,
) {
    val migration = vault.needsPinMigration()
    var pin by remember { mutableStateOf("") }
    Panel {
        Heading(if (migration) work.text(R.string.msg_vault_migrate_title) else stringResource(R.string.ava_unlock_title))
        if (migration) {
            Text(work.text(R.string.msg_vault_migrate_description))
            Field(work.text(R.string.msg_vault_previous_pin), pin, secret = true, number = true, enabled = !work.busy) {
                pin = it.filter { char -> char in '0'..'9' }.take(6)
            }
        }
        Primary(
            if (migration) work.text(R.string.msg_vault_migrate)
            else if (requiresDeviceUnlock) stringResource(R.string.ava_unlock_with_biometric)
            else if (initialized) work.text(R.string.msg_vault_open) else work.text(R.string.msg_vault_create),
            !work.busy && (!migration || pin.length == 6),
        ) {
            work.run {
                if (migration) vault.migratePin(pin) else onUnlock()
                pin = ""
            }
        }
        if (migration && requiresDeviceUnlock) Entry(stringResource(R.string.ava_unlock_with_biometric), !work.busy) {
            work.run { onUnlock() }
        }
    }
}

@Composable
private fun HomeAccounts(
    accounts: List<SteamGuardAccount>,
    steamTime: SteamTime,
    work: AppWork,
    onSignIn: () -> Unit,
    onAccount: (SteamGuardAccount) -> Unit,
    onTransfer: (SteamGuardAccount) -> Unit,
) {
    val busy = work.busy
    if (accounts.isEmpty()) {
        Panel {
            Heading(stringResource(R.string.ava_first_account))
            Text(stringResource(R.string.ava_first_account_text), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Primary(stringResource(R.string.ava_login_button), !busy, onSignIn)
        }
    }
    var now by remember { mutableStateOf(steamTime.currentSteamTime()) }
    LaunchedEffect(steamTime) {
        while (true) {
            now = steamTime.currentSteamTime()
            delay(250)
        }
    }
    for (account in accounts) key(AccountStore.storageId(account)) {
        AccountCode(account, now, steamTime, work, onAccount, onTransfer)
    }
}

@Composable
private fun AccountCode(
    account: SteamGuardAccount,
    time: Long,
    steamTime: SteamTime,
    work: AppWork,
    onAccount: (SteamGuardAccount) -> Unit,
    onTransfer: (SteamGuardAccount) -> Unit,
) {
    val busy = work.busy
    val context = LocalContext.current
    val code = remember(account.sharedSecret, time / 30) {
        if (account.sharedSecret.isNullOrBlank()) null else runCatching { account.generateCode(time) }.getOrNull()
    }
    val seconds = SteamTotp.secondsRemaining(time)
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SteamAvatar(account, animated = false)
            Text(account.displayName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MiuixTheme.textStyles.title3)
            IconButton(onClick = { onAccount(account) }, enabled = !busy) {
                Icon(MiuixIcons.Settings, stringResource(R.string.ava_manage), Modifier.size(24.dp))
            }
        }
        if (code != null) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy, onClickLabel = work.text(R.string.msg_copy_guard_code)) {
                    context.copySensitive("Steam Guard", code)
                }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(code, color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.title1.copy(
                    fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 34.sp,
                ))
                Text(work.text(R.string.msg_guard_seconds, seconds))
            }
            SteamCodeProgress(steamTime)
        } else if (account.steamId > 0L) {
            Primary(stringResource(R.string.ui_transfer_to_app), !busy) { onTransfer(account) }
        } else {
            Text(work.text(R.string.msg_enroll_required))
        }
    }
}

internal val SteamGuardAccount.displayName: String
    get() = personaName?.takeIf(String::isNotBlank) ?: accountName?.takeIf(String::isNotBlank)
        ?: steamId.takeIf { it > 0L }?.toString() ?: "Steam Guard"

internal val SteamGuardAccount.username: String
    get() = accountName?.takeIf(String::isNotBlank) ?: "—"
