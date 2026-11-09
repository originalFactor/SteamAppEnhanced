package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.protocol.EResult
import cc.ptoe.steam.enhanced.net.SteamApiException
import java.io.IOException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.app.AuthenticatorRecoveryStore
import cc.ptoe.steam.enhanced.protocol.AuthenticatorRecovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import cc.ptoe.steam.enhanced.app.AccountVault
import cc.ptoe.steam.enhanced.app.SteamServices
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.protocol.AuthenticatorLinker
import cc.ptoe.steam.enhanced.protocol.FinalizeResult
import cc.ptoe.steam.enhanced.protocol.GuardType
import cc.ptoe.steam.enhanced.protocol.LinkResult
import cc.ptoe.steam.enhanced.protocol.SteamAuthSession
import cc.ptoe.steam.enhanced.store.AccountStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun LoginPage(
    vault: AccountVault,
    services: SteamServices,
    work: AppWork,
    previous: SteamGuardAccount?,
    recoveryStore: AuthenticatorRecoveryStore,
    modifier: Modifier = Modifier,
    qrOnly: Boolean = false,
    verificationPage: Boolean = false,
    recoveryPage: Boolean = false,
    onVerificationRequired: () -> Unit = {},
    onRecoveryStarted: () -> Unit = {},
    onBackToPassword: () -> Unit = {},
    onOpenQr: () -> Unit = {},
    onQrActionChanged: ((() -> Unit)?) -> Unit = {},
    onSignedIn: (SteamGuardAccount) -> Unit,
) {
    val resources by rememberUpdatedState(LocalResources.current)
    val pendingRecovery by recoveryStore.pending.collectAsState()
    val vaultState by vault.state.collectAsState()
    var username by rememberSaveable { mutableStateOf(previous?.accountName.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by rememberSaveable { mutableStateOf(previous?.password != null) }
    var session by remember { mutableStateOf<SteamAuthSession?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    var polling by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var method by remember { mutableStateOf<GuardType?>(null) }
    var loginPassword by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<AuthenticatorRecovery?>(null) }
    var recovered by remember { mutableStateOf<SteamGuardAccount?>(null) }
    var transferCode by remember { mutableStateOf("") }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current

    fun reportFailure(failure: Exception) {
        work.feedback = work.errorMessage(failure)
    }

    suspend fun attemptSignIn(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportFailure(failure)
        }
    }

    fun signIn(block: suspend () -> Unit) {
        work.run { attemptSignIn(block) }
    }

    fun startQrLogin() {
        signIn {
            val next = services.newAuthSession()
            qr = next.beginWithQr()
            session = next
            polling = true
        }
    }

    fun recoverAuthenticator(current: SteamAuthSession) {
        work.ask(
            resources.getString(R.string.ui_recovery_start_title),
            resources.getString(R.string.ui_recovery_start_summary),
            resources.getString(R.string.ui_recovery_send_code), hold = false,
        ) {
            attemptSignIn {
                polling = false
                pollJob?.cancelAndJoin()
                checkUi(previous == null || previous.steamId == current.steamId ||
                    previous.steamId == 0L && previous.accountName?.equals(username.trim(), ignoreCase = true) == true) {
                    work.text(R.string.msg_login_wrong_account)
                }
                val next = current.newAuthenticatorRecovery()
                checkUi(next.start() == LinkResult.CHALLENGE_EMAIL_SENT) { resources.getString(R.string.ava_login_failed, "Steam") }
                recovery = next
                transferCode = ""
                onRecoveryStarted()
            }
        }
    }

    LaunchedEffect(verificationPage, recoveryPage) {
        if (qrOnly) return@LaunchedEffect
        if (verificationPage || recoveryPage) {
            if (session == null) onBackToPassword()
            else if (verificationPage && recovery != null && recovered == null) {
                recovery = null
                transferCode = ""
                polling = session?.acceptsApproval == true
            }
        } else if (session != null) {
            polling = false
            pollJob?.cancelAndJoin()
            session = null
            method = null
            code = ""
            loginPassword = null
            recovery = null
            recovered = null
            transferCode = ""
        }
    }

    DisposableEffect(session, qrOnly, verificationPage, recoveryPage) {
        onQrActionChanged(if (!qrOnly && !verificationPage && !recoveryPage && session == null) onOpenQr else null)
        onDispose { onQrActionChanged(null) }
    }
    LaunchedEffect(qrOnly) {
        if (qrOnly) startQrLogin()
    }

    LaunchedEffect(session, polling) {
        val current = session ?: return@LaunchedEffect
        if (!polling) return@LaunchedEffect
        pollJob = currentCoroutineContext().job
        try {
            repeat(75) {
                val response = current.poll()
                qr = response.newChallengeUrl ?: qr
                if (response.complete) {
                    val tokens = current.toSessionData(response)
                    checkUi(tokens.steamId > 0L) { work.text(R.string.msg_login_no_id) }
                    val name = response.accountName?.takeIf { it.isNotBlank() } ?: username.trim()
                    if (previous != null) {
                        checkUi(previous.steamId == tokens.steamId ||
                            previous.steamId == 0L && !previous.accountName.isNullOrBlank() &&
                            previous.accountName.equals(name, ignoreCase = true)) {
                            work.text(R.string.msg_login_wrong_account)
                        }
                    }
                    val existing = vault.state.value.accounts.firstOrNull { it.steamId == tokens.steamId }
                    val base = if (!existing?.sharedSecret.isNullOrBlank()) checkNotNullUi(existing)
                        else previous ?: existing ?: SteamGuardAccount()
                    val updated = base.copy(
                        session = tokens.copy(extra = base.session.extra),
                        accountName = name.ifBlank { base.accountName },
                        password = if (qr != null) base.password else loginPassword,
                    )
                    withContext(NonCancellable) {
                        vault.save(updated)
                        if (previous?.steamId == 0L) vault.remove(previous)
                    }
                    password = ""
                    loginPassword = null
                    val signedIn = try {
                        services.profiles.refresh(AccountStore.storageId(updated), force = true) ?: updated
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // The authenticated account is already persisted. Profile
                        // metadata must not turn a successful login into a failure.
                        dlog("Profile refresh after sign-in failed: ${failure.javaClass.simpleName}")
                        updated
                    }
                    onSignedIn(signedIn)
                    return@LaunchedEffect
                }
                delay(2_400)
            }
            work.feedback = work.text(R.string.msg_login_timeout)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            reportFailure(failure)
        } finally {
            polling = false
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (work.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            work.feedback?.let { Text(it) }
            Panel {
                val current = session
                if (qrOnly && current == null) {
                    if (work.busy) Text(stringResource(R.string.ui_loading_login_qr))
                    else Primary(stringResource(R.string.ui_retry), onClick = ::startQrLogin)
                } else if (!qrOnly && !verificationPage && current == null) {
                    Field(stringResource(R.string.ava_login_username), username, enabled = !work.busy) { username = it }
                    Field(stringResource(R.string.ava_login_password), password, secret = true, enabled = !work.busy) { password = it }
                    SwitchPreference(
                        checked = rememberPassword,
                        onCheckedChange = { rememberPassword = it },
                        title = work.text(R.string.msg_login_save_password),
                        enabled = !work.busy,
                    )
                    Primary(stringResource(R.string.ava_login_button), username.isNotBlank() && password.isNotBlank() && !work.busy) {
                        signIn {
                            val next = services.newAuthSession()
                            next.beginWithCredentials(username.trim(), password)
                            loginPassword = password.takeIf { rememberPassword }
                            password = ""
                            method = next.allowedConfirmations.firstOrNull { it == GuardType.DEVICE_CODE || it == GuardType.EMAIL_CODE }
                            session = next
                            polling = method == null || next.acceptsApproval
                            if (method != null || next.acceptsApproval) onVerificationRequired()
                            val stored = vault.state.value.accounts.firstOrNull { it.steamId == next.steamId } ?: previous
                            if (method == GuardType.DEVICE_CODE && !stored?.sharedSecret.isNullOrBlank()) {
                                if (!services.time.isAligned) services.time.align()
                                next.submitSteamGuardCode(
                                    checkNotNullUi(stored).generateCode(services.time.currentSteamTime()), GuardType.DEVICE_CODE,
                                )
                                polling = true
                            }
                        }
                    }

                } else if (current != null) {
                    val challenge = qr
                    val guard = method
                    val replacement = recovered
                    val transfer = recovery
                    if (verificationPage && replacement == null && transfer == null && current.canRecoverAuthenticator) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            val linkBlue = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF7AB1FF) else Color(0xFF0062CC)
                            TextButton(
                                stringResource(R.string.ui_recovery_start_title),
                                onClick = { recoverAuthenticator(current) },
                                enabled = !work.busy,
                                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = linkBlue),
                            )
                        }
                    }
                    if (replacement != null) {
                        Text(stringResource(R.string.ui_recovery_ready))
                        replacement.revocationCode?.let { recoveryCode ->
                            Entry(stringResource(R.string.ui_recovery_copy_code), !work.busy) {
                                context.copySensitive(work.text(R.string.ui_recovery_copy_code), recoveryCode)
                            }
                        }
                        Primary(stringResource(R.string.ui_recovery_continue), !work.busy && pendingRecovery == null) {
                            signIn {
                                val saved = vault.state.value.accounts.firstOrNull { it.steamId == replacement.steamId }
                                checkUi(saved?.sharedSecret == replacement.sharedSecret) { resources.getString(R.string.ui_recovery_save_failed) }
                                if (!services.time.isAligned) services.time.align()
                                current.submitSteamGuardCode(replacement.generateCode(services.time.currentSteamTime()), GuardType.DEVICE_CODE)
                                polling = true
                            }
                        }
                    } else if (transfer != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            val linkBlue = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF7AB1FF) else Color(0xFF0062CC)
                            TextButton(
                                stringResource(R.string.ui_recovery_resend),
                                onClick = {
                                    signIn { checkUi(transfer.start() == LinkResult.CHALLENGE_EMAIL_SENT) { resources.getString(R.string.ava_login_failed, "Steam") } }
                                },
                                enabled = !work.busy,
                                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = linkBlue),
                            )
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SteamApprovalAnimation()
                        }
                        Text(
                            stringResource(R.string.ui_recovery_sms_prompt),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                        Field(stringResource(R.string.ui_recovery_code), transferCode, enabled = !work.busy) { transferCode = it.take(16) }
                        Primary(stringResource(R.string.ui_transfer_to_app), transferCode.isNotBlank() && !work.busy) {
                            work.ask(
                                resources.getString(R.string.ui_recovery_replace_title),
                                resources.getString(R.string.ui_recovery_replace_summary),
                                resources.getString(R.string.msg_transfer),
                            ) {
                                attemptSignIn {
                                    try {
                                        recovered = recoveryStore.finish(transfer, transferCode.trim(), username.trim(), loginPassword)
                                    } finally {
                                        // A failed disk write still retains the already-active
                                        // replacement in the ViewModel and global rescue dialog.
                                        recoveryStore.pending.value?.let { recovered = it }
                                        if (recovered != null) transferCode = ""
                                    }
                                }
                            }
                        }
                    } else if (challenge != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SteamScanAnimation()
                        }
                        Text(
                            stringResource(R.string.ava_login_scan_with_app),
                            modifier = Modifier.fillMaxWidth(),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                        val image = remember(challenge) { qrBitmap(challenge).asImageBitmap() }
                        Image(image, work.text(R.string.msg_login_qr_description), Modifier.fillMaxWidth().heightIn(max = 320.dp))
                    } else if (!verificationPage) {
                        Text(stringResource(R.string.ava_login_waiting), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SteamApprovalAnimation()
                        }
                        Text(
                            stringResource(if (guard == GuardType.EMAIL_CODE && !current.acceptsApproval) R.string.ui_two_factor_email_prompt else R.string.ui_two_factor_prompt),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                        if (guard != null) {
                            Field(work.text(R.string.msg_login_code), code, enabled = !work.busy) { code = it.take(12) }
                            Primary(stringResource(R.string.ava_login_submit_code), code.isNotBlank() && !work.busy) {
                                signIn {
                                    current.submitSteamGuardCode(code.trim().uppercase(), guard)
                                    code = ""
                                    polling = true
                                }
                            }
                            val localAuthenticator = vaultState.accounts.firstOrNull { it.steamId == current.steamId } ?: previous
                            if (guard == GuardType.DEVICE_CODE && !localAuthenticator?.sharedSecret.isNullOrBlank()) {
                                Entry(work.text(R.string.msg_login_use_current_code), !work.busy) {
                                    signIn {
                                        if (!services.time.isAligned) services.time.align()
                                        current.submitSteamGuardCode(checkNotNullUi(localAuthenticator).generateCode(services.time.currentSteamTime()), guard)
                                        polling = true
                                    }
                                }
                            }
                            for (other in current.allowedConfirmations.filter { it != guard }) {
                                when (other) {
                                    GuardType.EMAIL_CODE, GuardType.DEVICE_CODE -> Entry(
                                        if (other == GuardType.EMAIL_CODE) work.text(R.string.msg_login_use_email) else work.text(R.string.msg_login_use_guard), !work.busy,
                                    ) { code = ""; method = other }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    if (polling && (verificationPage || qrOnly)) Text(
                        stringResource(R.string.ava_login_waiting), Modifier.fillMaxWidth(),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
                    )
                    if (!polling && guard == null && recovery == null && recovered == null) Primary(work.text(R.string.msg_login_check_again), !work.busy) { polling = true }

                }
            }
        }

    }
}

@Composable
internal fun EnrollmentPage(
    account: SteamGuardAccount,
    vault: AccountVault,
    services: SteamServices,
    work: AppWork,
    transferOnly: Boolean = false,
) {
    val context = LocalContext.current
    val id = AccountStore.storageId(account)
    var linker by remember { mutableStateOf<AuthenticatorLinker?>(null) }
    var stage by remember { mutableStateOf(if (account.sharedSecret != null && !account.fullyEnrolled) "activate" else "choose") }
    var input by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<SteamGuardAccount?>(null) }

    suspend fun activeLinker(): AuthenticatorLinker {
        linker?.let { return it }
        val current = services.session(id)
        if (!services.time.isAligned) services.time.align()
        return AuthenticatorLinker(services.api, current.session).also { linker = it }
    }

    suspend fun persist(newAccount: SteamGuardAccount) {
        val updated = newAccount.copy(
            accountName = newAccount.accountName ?: account.accountName,
            personaName = account.personaName,
            password = account.password,
        )
        recovery = updated
        vault.saveAuthenticator(updated)
        recovery = null
    }

    suspend fun startAdd(phone: String? = null) {
        val link = activeLinker()
        link.phoneNumber = phone
        link.phoneCountryCode = country.trim().uppercase()
        when (val result = link.addAuthenticator()) {
            LinkResult.MUST_PROVIDE_PHONE_NUMBER -> stage = "phone"
            LinkResult.MUST_CONFIRM_EMAIL -> stage = "email"
            LinkResult.AUTHENTICATOR_PRESENT -> {
                stage = "choose"
                work.feedback = work.text(R.string.msg_enroll_already_present)
            }
            LinkResult.AWAITING_FINALIZATION -> {
                val pending = checkNotNullUi(link.linkedAccount)
                stage = "activate"
                input = ""
                persist(pending)
            }
            else -> throw UiFailure(work.text(linkResultLabel(result)))
        }
    }

    Panel {
        Heading(account.displayName)
        val unsaved = recovery
        if (unsaved != null) {
            Text(work.text(R.string.ui_recovery_save_failed))
            Entry(work.text(R.string.ui_recovery_copy_file), !work.busy) {
                context.copySensitive(work.text(R.string.ui_recovery_copy_file), unsaved.toExportJson().toString())
            }
            Primary(work.text(R.string.ui_recovery_retry_save), !work.busy) { work.run { persist(unsaved) } }
        } else when (stage) {
            "choose" -> {
                if (!transferOnly) Primary(stringResource(R.string.ava_welcome_login_sub), !work.busy) {
                    work.ask(work.text(R.string.msg_enroll_setup_question), work.text(R.string.msg_enroll_setup_description), work.text(R.string.msg_continue)) {
                        withContext(NonCancellable) { startAdd() }
                    }
                }
                Entry(work.text(R.string.msg_enroll_transfer_existing), !work.busy) {
                    work.ask(work.text(R.string.msg_enroll_transfer_question), work.text(R.string.msg_enroll_transfer_description), work.text(R.string.msg_enroll_send_email), hold = false) {
                        val result = activeLinker().moveInStart()
                        checkUi(result == LinkResult.CHALLENGE_EMAIL_SENT) { work.text(linkResultLabel(result)) }
                        stage = "move"
                        input = ""
                    }
                }
            }
            "phone" -> {
                Field(work.text(R.string.msg_enroll_phone), input, enabled = !work.busy) { input = it }
                Field(work.text(R.string.msg_enroll_country), country, enabled = !work.busy) { country = it.take(2) }
                Primary(work.text(R.string.msg_enroll_add_phone), input.isNotBlank() && !work.busy) {
                    work.run { withContext(NonCancellable) { startAdd(input.trim()) } }
                }
            }
            "email" -> {
                Text(work.text(R.string.msg_enroll_confirm_phone))
                Primary(work.text(R.string.msg_enroll_continue_confirmed), !work.busy) {
                    work.run { withContext(NonCancellable) { startAdd() } }
                }
            }
            "activate" -> {
                Text(work.text(R.string.msg_enroll_activation_prompt))
                if (linker == null) SwitchPreference(
                    checked = country == "EMAIL", onCheckedChange = { country = if (it) "EMAIL" else "" },
                    title = work.text(R.string.msg_enroll_email_sent),
                )
                Field(work.text(R.string.msg_enroll_activation_code), input, enabled = !work.busy) { input = it.take(16) }
                Primary(work.text(R.string.msg_enroll_activate), input.isNotBlank() && !work.busy) {
                    work.run {
                        withContext(NonCancellable) {
                            val link = activeLinker()
                            if (link.linkedAccount == null) link.resumeActivation(account, country == "EMAIL")
                            val result = link.finalize(input.trim())
                            checkUi(result == FinalizeResult.SUCCESS) { work.text(finalizeResultLabel(result)) }
                            stage = "complete"
                            persist(checkNotNullUi(link.linkedAccount).copy(fullyEnrolled = true))
                            input = ""
                        }
                    }
                }
            }
            "move" -> {
                Text(work.text(R.string.msg_enroll_transfer_prompt))
                Field(work.text(R.string.msg_enroll_transfer_code), input, enabled = !work.busy) { input = it.take(16) }
                Primary(work.text(R.string.msg_enroll_transfer), input.isNotBlank() && !work.busy) {
                    work.ask(work.text(R.string.ui_recovery_replace_title), work.text(R.string.msg_enroll_replace_warning), work.text(R.string.msg_transfer)) {
                        withContext(NonCancellable) {
                            val link = activeLinker()
                            val result = link.moveInContinue(input.trim())
                            checkUi(result == LinkResult.MOVED_IN) { work.text(linkResultLabel(result)) }
                            stage = "complete"
                            persist(checkNotNullUi(link.linkedAccount))
                            input = ""
                        }
                    }
                }
            }
            "complete" -> {
                Heading(work.text(R.string.msg_enroll_ready))
                Text(work.text(R.string.msg_enroll_save_code))
                account.revocationCode?.let { recoveryCode ->
                    Entry(work.text(R.string.ui_recovery_copy_code), !work.busy) { context.copySensitive(work.text(R.string.ui_recovery_copy_code), recoveryCode) }
                }
            }
        }
    }
}

@Composable
internal fun QrInputButtons(enabled: Boolean, work: AppWork, onDecoded: (String) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) work.run {
            val decoded = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                requireUi(bounds.outWidth > 0 && bounds.outHeight > 0) { work.text(R.string.msg_image_unreadable) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: throw UiFailure(work.text(R.string.msg_image_unreadable))
                try { decodeQr(bitmap, work) } finally { bitmap.recycle() }
            }
            onDecoded(decoded)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) work.run {
            try {
                onDecoded(withContext(Dispatchers.Default) { decodeQr(bitmap, work) })
            } finally {
                bitmap.recycle()
            }
        }
    }
    Entry(work.text(R.string.msg_scan_camera), enabled) {
        try {
            camera.launch(null)
        } catch (_: android.content.ActivityNotFoundException) {
            work.feedback = work.text(R.string.msg_scan_no_camera)
        }
    }
    Entry(work.text(R.string.msg_scan_image), enabled) { image.launch(arrayOf("image/*")) }
}

private fun qrBitmap(text: String): Bitmap {
    val size = 420
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun decodeQr(bitmap: Bitmap, work: AppWork): String {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    return try {
        QRCodeReader().decode(binary, mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: com.google.zxing.ReaderException) {
        throw UiFailure(work.text(R.string.msg_scan_not_found))
    }
}
