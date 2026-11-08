package cc.ptoe.steam.enhanced.ui

import androidx.compose.ui.res.stringResource
import cc.ptoe.steam.enhanced.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    onSignedIn: (SteamGuardAccount) -> Unit,
) {
    var username by remember { mutableStateOf(previous?.accountName.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(previous?.password != null) }
    var session by remember { mutableStateOf<SteamAuthSession?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    var polling by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var method by remember { mutableStateOf<GuardType?>(null) }
    var loginPassword by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session, polling) {
        val current = session ?: return@LaunchedEffect
        if (!polling) return@LaunchedEffect
        try {
            repeat(75) {
                val response = current.poll()
                qr = response.newChallengeUrl ?: qr
                if (response.complete) {
                    val tokens = current.toSessionData(response)
                    check(tokens.steamId > 0L) { "Steam did not return an account ID." }
                    val name = response.accountName?.takeIf { it.isNotBlank() } ?: username.trim()
                    if (previous != null) {
                        check(previous.steamId == tokens.steamId ||
                            previous.steamId == 0L && !previous.accountName.isNullOrBlank() &&
                            previous.accountName.equals(name, ignoreCase = true)) {
                            "The signed-in Steam account does not match this maFile."
                        }
                    }
                    val existing = vault.state.value.accounts.firstOrNull { it.steamId == tokens.steamId }
                    val base = if (previous?.steamId == 0L && existing?.sharedSecret != null) existing
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
                    onSignedIn(updated)
                    return@LaunchedEffect
                }
                delay(2_400)
            }
            work.feedback = "Sign-in timed out. Start a new Steam session."
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            work.feedback = if (failure is IllegalStateException) failure.message
                else "Steam sign-in failed. Start a new session."
        } finally {
            polling = false
        }
    }

    Panel {
        val current = session
        if (current == null) {
            Heading("Steam account")
            Field(stringResource(R.string.ava_login_username), username, enabled = !work.busy) { username = it }
            Field(stringResource(R.string.ava_login_password), password, secret = true, enabled = !work.busy) { password = it }
            SwitchPreference(
                checked = rememberPassword,
                onCheckedChange = { rememberPassword = it },
                title = "Store password for automatic sign-in",
                summary = "Stored only in the encrypted local vault.",
                enabled = !work.busy,
            )
            Primary(stringResource(R.string.ava_login_via_credentials), username.isNotBlank() && password.isNotBlank() && !work.busy) {
                work.run {
                    val next = SteamAuthSession(services.api)
                    next.beginWithCredentials(username.trim(), password)
                    loginPassword = password.takeIf { rememberPassword }
                    password = ""
                    method = next.allowedConfirmations.firstOrNull { it == GuardType.DEVICE_CODE || it == GuardType.EMAIL_CODE }
                    session = next
                    polling = method == null
                }
            }
            Entry(stringResource(R.string.ava_login_via_qr), !work.busy) {
                work.run {
                    val next = SteamAuthSession(services.api)
                    qr = next.beginWithQr()
                    session = next
                    polling = true
                }
            }
        } else {
            val challenge = qr
            val guard = method
            if (challenge != null) {
                Heading(stringResource(R.string.ava_login_scan_with_app))
                val image = remember(challenge) { qrBitmap(challenge).asImageBitmap() }
                Image(image, "Steam sign-in QR code", Modifier.fillMaxWidth().heightIn(max = 320.dp))
            } else if (guard != null && !polling) {
                Heading(if (guard == GuardType.EMAIL_CODE) "Email verification" else "Steam Guard verification")
                Field("Verification code", code, enabled = !work.busy) { code = it.take(12) }
                Primary(stringResource(R.string.ava_login_submit_code), code.isNotBlank() && !work.busy) {
                    work.run {
                        current.submitSteamGuardCode(code.trim().uppercase(), guard)
                        code = ""
                        polling = true
                    }
                }
                if (guard == GuardType.DEVICE_CODE && previous?.sharedSecret != null) {
                    Entry("Use this account's current code", !work.busy) {
                        work.run {
                            if (!services.time.isAligned) services.time.align()
                            current.submitSteamGuardCode(previous.generateCode(services.time.currentSteamTime()), guard)
                            polling = true
                        }
                    }
                }
                for (other in current.allowedConfirmations.filter { it != guard }) {
                    when (other) {
                        GuardType.EMAIL_CODE, GuardType.DEVICE_CODE -> Entry(
                            if (other == GuardType.EMAIL_CODE) "Use email code" else "Use Steam Guard code", !work.busy,
                        ) { code = ""; method = other }
                        GuardType.DEVICE_CONFIRMATION, GuardType.EMAIL_CONFIRMATION ->
                            Entry("Wait for approval", !work.busy) { polling = true }
                        else -> Unit
                    }
                }
            }
            if (polling) Text(stringResource(R.string.ava_login_waiting))
            if (!polling && guard == null) Primary("Check again", !work.busy) { polling = true }
            Entry("Start over", !work.busy) {
                session = null
                qr = null
                code = ""
                loginPassword = null
                work.feedback = null
            }
        }
    }
}

@Composable
internal fun EnrollmentPage(account: SteamGuardAccount, vault: AccountVault, services: SteamServices, work: AppWork) {
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
                work.feedback = "This account already has an authenticator. Use Transfer existing authenticator."
            }
            LinkResult.AWAITING_FINALIZATION -> {
                val pending = checkNotNull(link.linkedAccount)
                stage = "activate"
                input = ""
                persist(pending)
            }
            else -> error(result.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
        }
    }

    Panel {
        Heading(account.displayName)
        val unsaved = recovery
        if (unsaved != null) {
            Text("Steam returned an authenticator that could not be saved. Keep a recovery copy before leaving.")
            Entry("Copy recovery maFile", !work.busy) {
                context.copySensitive("Steam Guard recovery", unsaved.toExportJson().toString())
            }
            Primary("Retry local save", !work.busy) { work.run { persist(unsaved) } }
        } else when (stage) {
            "choose" -> {
                Primary(stringResource(R.string.ava_welcome_login_sub), !work.busy) {
                    work.ask("Set up Steam Guard?", "Steam may ask for a phone number or send a verification code.", "Continue") {
                        withContext(NonCancellable) { startAdd() }
                    }
                }
                Entry("Transfer existing authenticator", !work.busy) {
                    work.ask("Transfer Steam Guard?", "Steam will email a transfer code. Entering that code later replaces the existing authenticator.", "Send email", hold = false) {
                        val result = activeLinker().moveInStart()
                        check(result == LinkResult.CHALLENGE_EMAIL_SENT) { result.name }
                        stage = "move"
                        input = ""
                    }
                }
            }
            "phone" -> {
                Field("Phone number with country code", input, enabled = !work.busy) { input = it }
                Field("Country code (optional)", country, enabled = !work.busy) { country = it.take(2) }
                Primary("Add phone number", input.isNotBlank() && !work.busy) {
                    work.run { withContext(NonCancellable) { startAdd(input.trim()) } }
                }
            }
            "email" -> {
                Text("Open the email from Steam and confirm the phone number.")
                Primary("Continue after confirmation", !work.busy) {
                    work.run { withContext(NonCancellable) { startAdd() } }
                }
            }
            "activate" -> {
                Text("Enter the activation code Steam sent by SMS or email.")
                if (linker == null) SwitchPreference(
                    checked = country == "EMAIL", onCheckedChange = { country = if (it) "EMAIL" else "" },
                    title = "Code was sent by email",
                )
                Field("Activation code", input, enabled = !work.busy) { input = it.take(16) }
                Primary("Activate authenticator", input.isNotBlank() && !work.busy) {
                    work.run {
                        withContext(NonCancellable) {
                            val link = activeLinker()
                            if (link.linkedAccount == null) link.resumeActivation(account, country == "EMAIL")
                            val result = link.finalize(input.trim())
                            check(result == FinalizeResult.SUCCESS) { result.name.replace('_', ' ') }
                            stage = "complete"
                            persist(checkNotNull(link.linkedAccount).copy(fullyEnrolled = true))
                            input = ""
                        }
                    }
                }
            }
            "move" -> {
                Text("Enter the code from Steam's transfer email.")
                Field("Transfer code", input, enabled = !work.busy) { input = it.take(16) }
                Primary("Transfer authenticator", input.isNotBlank() && !work.busy) {
                    work.ask("Replace the old authenticator?", "The old device and recovery code will stop working as soon as Steam accepts this code.", "Transfer") {
                        withContext(NonCancellable) {
                            val link = activeLinker()
                            val result = link.moveInContinue(input.trim())
                            check(result == LinkResult.MOVED_IN) { result.name.replace('_', ' ') }
                            stage = "complete"
                            persist(checkNotNull(link.linkedAccount))
                            input = ""
                        }
                    }
                }
            }
            "complete" -> {
                Heading("Authenticator ready")
                Text("Save the recovery code somewhere safe.")
                account.revocationCode?.let { recoveryCode ->
                    Entry("Copy recovery code", !work.busy) { context.copySensitive("Steam recovery", recoveryCode) }
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
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The image could not be read." }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("The image could not be read.")
                try { decodeQr(bitmap) } finally { bitmap.recycle() }
            }
            onDecoded(decoded)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) work.run {
            try {
                onDecoded(withContext(Dispatchers.Default) { decodeQr(bitmap) })
            } finally {
                bitmap.recycle()
            }
        }
    }
    Entry("Scan with camera", enabled) {
        try {
            camera.launch(null)
        } catch (_: android.content.ActivityNotFoundException) {
            work.feedback = "No camera app is available. Open a QR image instead."
        }
    }
    Entry("Open QR image", enabled) { image.launch(arrayOf("image/*")) }
}

private fun qrBitmap(text: String): Bitmap {
    val size = 420
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun decodeQr(bitmap: Bitmap): String {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    return try {
        QRCodeReader().decode(binary, mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: com.google.zxing.ReaderException) {
        error("No readable QR code was found.")
    }
}
