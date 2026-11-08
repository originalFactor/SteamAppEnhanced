package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.SelectedMaFiles
import cc.ptoe.steam.enhanced.app.SteamAppState
import cc.ptoe.steam.enhanced.app.SteamProfile
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal enum class Page(val title: Int) {
    HOME(R.string.ava_steam_guard),
    ADD(R.string.ui_add_account),
    ACCOUNT(R.string.ava_nav_accounts),
    LOGIN(R.string.ava_login_title),
    ENROLL(R.string.ava_add_auth_title),
    PENDING(R.string.ava_pending_title),
    APPROVE(R.string.ava_approve_title),
    DEVICES(R.string.ava_device_sessions_title),
    FAMILY(R.string.ava_fam_account_action),
    MARKET(R.string.ava_action_market),
    REDEEM(R.string.ava_key_redeem_title),
    SETTINGS(R.string.ava_nav_settings),
    SYNC(R.string.ava_sync_title),
}

@Composable
internal fun SteamEnhancedApp(app: SteamAppState) {
    val preferences = app.preferences
    SteamEnhancedTheme(preferences.appearance, preferences.skin) {
        AppContent(app)
    }
}

@Composable
private fun AppContent(app: SteamAppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = app.vault
    val services = app.services
    val sync = app.sync
    val preferences = app.preferences
    val work = remember(scope) { AppWork(scope) }
    val state by vault.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var bundle by remember { mutableStateOf<SelectedMaFiles?>(null) }
    var exportId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportRecovery by rememberSaveable { mutableStateOf(false) }
    var pendingLogins by remember { mutableStateOf(setOf<Long>()) }
    var foreground by remember { mutableStateOf(true) }
    var initializing by remember(vault) { mutableStateOf(true) }
    val pageStates = rememberSaveableStateHolder()
    val scrollBehavior = key(page) { MiuixScrollBehavior() }
    val isRootPage = page == Page.HOME || page == Page.ADD || page == Page.SETTINGS
    val account = state.accounts.firstOrNull { AccountStore.storageId(it) == selected }

    LaunchedEffect(vault) {
        work.run {
            try {
                vault.initializeIfNeeded()
            } finally {
                initializing = false
            }
        }
    }

    fun navigate(destination: Page) {
        work.feedback = null
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
                        vault.state.value.unlocked && !work.busy && exportId == null && !exportRecovery
                    ) {
                        work.run {
                            vault.lock()
                            selected = null
                            bundle = null
                            navigate(Page.HOME)
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
                work.feedback = "Imported ${report.imported}, skipped ${report.skipped}, unreadable ${report.unreadable}."
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
            checkNotNull(value) { "The account is no longer available for export." }
            writeDocument(context.contentResolver, uri, value.toExportJson().toString())
            work.feedback = "maFile exported."
        }
    }

    fun goBack() {
        when {
            bundle != null -> bundle = null
            page == Page.SYNC -> navigate(Page.SETTINGS)
            page == Page.LOGIN && account == null -> navigate(Page.ADD)
            account != null && page != Page.ACCOUNT -> navigate(Page.ACCOUNT)
            else -> {
                selected = null
                navigate(Page.HOME)
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
        }
        if (state.unlocked && foreground) {
            while (true) {
                services.time.align()
                delay(15 * 60 * 1000)
            }
        }
    }
    LaunchedEffect(state.unlocked, foreground, page, preferences.pollLogins) {
        if (!state.unlocked || !preferences.pollLogins) pendingLogins = emptySet()
        if (!state.unlocked || !foreground || page != Page.HOME || !preferences.pollLogins) return@LaunchedEffect
        while (true) {
            val ids = mutableSetOf<Long>()
            for (row in vault.state.value.accounts) {
                if (row.steamId <= 0 || row.sharedSecret.isNullOrBlank() || !row.session.hasAccessToken) continue
                try {
                    if (services.approval.pendingLoginClientIds(row).isNotEmpty()) ids += AccountStore.storageId(row)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Sign-in notices are optional. Account actions handle reauthentication.
                }
            }
            pendingLogins = ids
            delay(15_000)
        }
    }
    LaunchedEffect(state.unlocked, foreground, page == Page.SYNC, preferences.autoSync) {
        if (!state.unlocked || !foreground || page == Page.SYNC || !preferences.autoSync) return@LaunchedEffect
        while (true) {
            delay(5 * 60_000)
            if (page == Page.HOME && !work.busy) work.run {
                val config = sync.config() ?: return@run
                val result = sync.sync(config)
                if (result.conflicts.isNotEmpty()) work.feedback = "Open encrypted sync to resolve account conflicts."
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = when {
                    !state.unlocked -> "Steam Enhanced"
                    bundle != null -> stringResource(R.string.ava_sda_import_action)
                    else -> stringResource(page.title)
                },
                navigationIcon = {
                    if (state.unlocked && (!isRootPage || bundle != null)) {
                        IconButton(onClick = ::goBack, enabled = !work.busy) {
                            Icon(MiuixIcons.Back, stringResource(R.string.ava_back))
                        }
                    }
                },
                actions = {
                    if (state.unlocked && page == Page.HOME && bundle == null) {
                        IconButton(onClick = { navigate(Page.ADD) }, enabled = !work.busy) {
                            Icon(MiuixIcons.Add, stringResource(R.string.ui_add_account))
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
                        Page.HOME to MiuixIcons.Home,
                        Page.ADD to MiuixIcons.AddCircle,
                        Page.SETTINGS to MiuixIcons.Settings,
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
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
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
                        !state.unlocked -> UnlockPanel(vault, state.initialized, work)
                        incoming != null -> {
                            var password by remember { mutableStateOf("") }
                            Text("Enter the encryption password used by Steam Desktop Authenticator.")
                            Field("SDA password", password, secret = true, enabled = !work.busy) { password = it }
                            Primary("Decrypt and import", password.isNotEmpty() && !work.busy) {
                                work.run {
                                    val report = vault.import(incoming.files, incoming.manifest, password)
                                    if (report.imported == 0 && report.skipped == 0) {
                                        work.feedback = "No files could be decrypted. Check the password and file selection."
                                    } else {
                                        bundle = null
                                        work.feedback = "Imported ${report.imported}, skipped ${report.skipped}, unreadable ${report.unreadable}."
                                    }
                                }
                            }
                        }
                        page == Page.HOME -> {
                            if (state.unreadable > 0) Text("${state.unreadable} vault entries could not be read.")
                            if (state.hasRecovery) Panel {
                                Heading("Authenticator recovery")
                                Text("Steam issued a new authenticator that needs to be saved to the vault.")
                                Primary("Restore authenticator", !work.busy) {
                                    work.run {
                                        val recovered = vault.recoveryAccount() ?: error("The recovery entry is unreadable.")
                                        vault.saveAuthenticator(recovered)
                                        work.feedback = "Authenticator recovered."
                                    }
                                }
                                Entry("Export recovery maFile", !work.busy) {
                                    work.ask("Export recovery maFile?", "This file contains your Steam Guard secrets.", "Choose file", hold = false) {
                                        checkNotNull(vault.recoveryAccount()) { "The recovery entry is unreadable." }
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
                            state.accounts.filter { AccountStore.storageId(it) in pendingLogins }.forEach {
                                Entry("Review Steam sign-in for ${it.displayName}", !work.busy) {
                                    selected = AccountStore.storageId(it)
                                    navigate(Page.APPROVE)
                                }
                            }
                            HomeAccounts(
                                state.accounts, services.time, work.busy,
                                onImport = { import.launch(arrayOf("*/*")) },
                                onSignIn = { selected = null; navigate(Page.LOGIN) },
                                onAccount = {
                                    selected = AccountStore.storageId(it)
                                    navigate(Page.ACCOUNT)
                                },
                            )
                        }
                        page == Page.ADD -> AddAccountPage(
                            busy = work.busy,
                            onImport = { import.launch(arrayOf("*/*")) },
                            onSignIn = { selected = null; navigate(Page.LOGIN) },
                        )
                        page == Page.LOGIN -> LoginPage(vault, services, work, account) {
                            selected = AccountStore.storageId(it)
                            navigate(Page.ACCOUNT)
                        }
                        page == Page.SETTINGS -> SettingsPage(vault, preferences, work, onSync = {
                            navigate(Page.SYNC)
                        }, onLock = {
                            work.run { vault.lock(); selected = null; navigate(Page.HOME) }
                        })
                        page == Page.SYNC -> SyncPage(vault, preferences, work)
                        account != null -> when (page) {
                            Page.ACCOUNT -> AccountMenu(account, work, ::navigate, onExport = {
                                exportId = AccountStore.storageId(account)
                                val filename = (account.accountName ?: account.steamId.toString())
                                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                                export.launch("$filename.maFile")
                            }, onRemove = {
                                vault.remove(account)
                                selected = null
                                navigate(Page.HOME)
                            }, onRefreshProfile = {
                                val profile = if (account.session.hasTokens) {
                                    services.read(AccountStore.storageId(account)) { SteamProfile.refresh(services.api, it) }
                                } else SteamProfile.refresh(services.api, account)
                                vault.save(profile)
                                work.feedback = "Profile refreshed."
                            })
                            Page.ENROLL -> EnrollmentPage(account, vault, services, work)
                            Page.PENDING -> PendingPage(account, services, work)
                            Page.APPROVE -> ApprovalPage(account, services, work)
                            Page.DEVICES -> DevicesPage(account, services, work)
                            Page.FAMILY -> FamilyPage(account, services, work)
                            Page.MARKET -> MarketPage(account, services, work)
                            Page.REDEEM -> RedeemPage(account, services, work)
                            else -> Unit
                        }
                        else -> Entry("Return to accounts") { navigate(Page.HOME) }
                    }
                }
            }
        }
        WorkPrompt(work)
    }
}

@Composable
private fun UnlockPanel(vault: AccountVault, initialized: Boolean, work: AppWork) {
    val migration = vault.needsPinMigration()
    var pin by remember { mutableStateOf("") }
    Panel {
        Heading(if (migration) "Migrate existing vault" else stringResource(R.string.ava_unlock_title))
        if (migration) {
            Text("Enter your previous PIN once to migrate your accounts. A vault PIN will no longer be required.")
            Field("Previous vault PIN", pin, secret = true, number = true, enabled = !work.busy) {
                pin = it.filter { char -> char in '0'..'9' }.take(6)
            }
        }
        Primary(
            if (migration) "Migrate vault" else if (initialized) "Open vault" else "Create vault",
            !work.busy && (!migration || pin.length == 6),
        ) {
            work.run {
                if (migration) vault.migratePin(pin) else vault.open()
                pin = ""
            }
        }
        if (initialized) DeviceUnlockButton(vault, work)
    }
}

@Composable
private fun HomeAccounts(
    accounts: List<SteamGuardAccount>,
    steamTime: SteamTime,
    busy: Boolean,
    onImport: () -> Unit,
    onSignIn: () -> Unit,
    onAccount: (SteamGuardAccount) -> Unit,
) {
    if (accounts.isEmpty()) {
        Panel {
            Heading(stringResource(R.string.ava_first_account))
            Text(stringResource(R.string.ava_first_account_text), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Primary(stringResource(R.string.ava_login_button), !busy, onSignIn)
            Entry(stringResource(R.string.ava_welcome_import_cta), !busy, onImport)
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
        AccountCode(account, now, busy, onAccount)
    }
}

@Composable
private fun AddAccountPage(busy: Boolean, onImport: () -> Unit, onSignIn: () -> Unit) {
    Panel {
        ArrowPreference(
            title = stringResource(R.string.ava_login_button),
            summary = stringResource(R.string.ui_sign_in_summary),
            enabled = !busy,
            onClick = onSignIn,
        )
        ArrowPreference(
            title = stringResource(R.string.ava_welcome_import_cta),
            summary = stringResource(R.string.ava_sda_selection),
            enabled = !busy,
            onClick = onImport,
        )
    }
}

@Composable
private fun AccountCode(
    account: SteamGuardAccount,
    time: Long,
    busy: Boolean,
    onAccount: (SteamGuardAccount) -> Unit,
) {
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
                Modifier.fillMaxWidth().clickable(enabled = !busy, onClickLabel = "Copy Steam Guard code") {
                    context.copySensitive("Steam Guard", code)
                }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(code, color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.title1.copy(
                    fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 34.sp,
                ))
                Text("${seconds}s")
            }
            LinearProgressIndicator(Modifier.fillMaxWidth(), progress = seconds / 30f)
        } else {
            Text("Set up Steam Guard to show a code.")
        }
    }
}

internal val SteamGuardAccount.displayName: String
    get() = personaName?.takeIf(String::isNotBlank) ?: accountName?.takeIf(String::isNotBlank)
        ?: steamId.takeIf { it > 0L }?.toString() ?: "Code-only account"

@Composable
private fun AccountMenu(
    account: SteamGuardAccount,
    work: AppWork,
    navigate: (Page) -> Unit,
    onExport: () -> Unit,
    onRemove: suspend () -> Unit,
    onRefreshProfile: suspend () -> Unit,
) {
    Panel {
        SteamAvatar(account, animated = true)
        Heading(account.displayName)
        Text(if (account.steamId > 0) "SteamID ${account.steamId}" else "Offline codes")
        Entry(stringResource(R.string.ava_action_login), !work.busy) { navigate(Page.LOGIN) }
        if (account.steamId > 0) {
            Entry("Refresh Steam profile", !work.busy) { work.run(onRefreshProfile) }
            Entry(stringResource(R.string.ava_add_auth_title), !work.busy) { navigate(Page.ENROLL) }
            Entry(stringResource(R.string.ava_pending_title), !work.busy) { navigate(Page.PENDING) }
            Entry(stringResource(R.string.ava_action_login_requests), !work.busy) { navigate(Page.APPROVE) }
            Entry(stringResource(R.string.ava_device_sessions_title), !work.busy) { navigate(Page.DEVICES) }
            Entry(stringResource(R.string.ava_fam_account_action), !work.busy) { navigate(Page.FAMILY) }
            Entry(stringResource(R.string.ava_action_market), !work.busy) { navigate(Page.MARKET) }
            Entry(stringResource(R.string.ava_key_redeem_action), !work.busy) { navigate(Page.REDEEM) }
        }
    }
    Panel {
        Entry(stringResource(R.string.ava_action_export), account.sharedSecret != null && !work.busy) {
            work.ask("Export authenticator?", "The exported file contains secrets. Save it somewhere private.", "Choose file", hold = false) {
                onExport()
            }
        }
        Entry(stringResource(R.string.ava_action_remove), !work.busy) {
            work.ask("Remove ${account.displayName}?", "This removes the local copy. Steam Guard remains active on Steam.", "Remove", block = onRemove)
        }
    }
}
