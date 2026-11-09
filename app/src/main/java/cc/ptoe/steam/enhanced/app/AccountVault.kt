package cc.ptoe.steam.enhanced.app

import android.content.Context
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.MaFileNormalizer
import cc.ptoe.steam.enhanced.core.crypto.VaultCrypto
import cc.ptoe.steam.enhanced.core.model.Manifest
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import cc.ptoe.steam.enhanced.core.readSdaBundle
import cc.ptoe.steam.enhanced.core.SdaManifest
import cc.ptoe.steam.enhanced.core.toAccountJson
import cc.ptoe.steam.enhanced.store.AccountStore
import cc.ptoe.steam.enhanced.store.StorageProvider
import cc.ptoe.steam.enhanced.store.VaultKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class VaultState(
    val initialized: Boolean = false,
    val unlocked: Boolean = false,
    val accounts: List<SteamGuardAccount> = emptyList(),
    val unreadable: Int = 0,
    val hasRecovery: Boolean = false,
)

internal data class ImportReport(val imported: Int, val skipped: Int, val unreadable: Int)

internal class AccountVault(context: Context) : VaultUnlockTarget {
    private val storage = StorageProvider(context.noBackupFilesDir)
    private val prefs = context.getSharedPreferences(VaultKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
    val protectedSettings = VaultKeyStore(prefs)
    private val mutex = Mutex()
    private var store: AccountStore? = null
    private var vaultKey: ByteArray? = null
    private val mutableState = MutableStateFlow(VaultState(initialized = protectedSettings.exists()))
    val state = mutableState.asStateFlow()

    override val isUnlocked: Boolean get() = state.value.unlocked

    override fun needsPinMigration(): Boolean = protectedSettings.needsMigration()

    override suspend fun open() = disk { openVault() }

    private suspend fun openVault() {
        check(!protectedSettings.needsMigration()) { "Migrate the existing vault first." }
        val key = protectedSettings.readKey() ?: run {
            check(!storage.manifestFile().exists() && storage.listFiles().isEmpty()) {
                "The existing vault key is unavailable."
            }
            VaultCrypto.generateDek().also {
                try { protectedSettings.storeKey(it) } catch (failure: Throwable) {
                    it.fill(0)
                    throw failure
                }
            }
        }
        loadWithKey(key)
    }

    suspend fun migratePin(pin: String) = disk {
        check(protectedSettings.needsMigration())
        loadWithKey(verifyPin(pin))
        protectedSettings.storeKey(checkNotNull(vaultKey))
    }

    private fun verifyPin(pin: String): ByteArray {
        val attempts = prefs.getInt("failed_attempts", 0)
        val wait = prefs.getLong("retry_at", 0L) - System.currentTimeMillis()
        check(wait <= 0L) { "Try again in ${(wait + 999) / 1_000} seconds." }
        val key = protectedSettings.unwrapWithPin(pin)
        if (key == null) {
            val failed = (attempts + 1).coerceAtMost(20)
            val timeout = if (failed < 5) 0L else (1_000L shl (failed - 5)).coerceAtMost(60_000L)
            check(prefs.edit().putInt("failed_attempts", failed)
                .putLong("retry_at", System.currentTimeMillis() + timeout).commit())
            error("Incorrect PIN.")
        }
        check(prefs.edit().remove("failed_attempts").remove("retry_at").commit())
        return key
    }

    override suspend fun openWithDeviceKey(key: ByteArray) = disk {
        check(protectedSettings.exists()) { "Set up a vault first." }
        loadWithKey(key.copyOf())
        if (protectedSettings.needsMigration()) protectedSettings.storeKey(checkNotNull(vaultKey))
    }

    suspend fun copyVaultKey(): ByteArray = disk {
        checkNotNull(vaultKey) { "Unlock the vault first." }.copyOf()
    }

    private suspend fun loadWithKey(key: ByteArray) {
        try {
            val loaded = if (storage.manifestFile().exists()) {
                val existing = AccountStore.load(storage, pruneMissing = false)
                check(existing.isVault) { "The vault manifest uses an unsupported format." }
                existing
            } else {
                // Setup commits the wrapped key first. An interrupted first run can
                // finish here, but a directory containing accounts needs recovery.
                check(storage.listFiles().isEmpty()) { "The vault manifest is missing." }
                AccountStore(storage, Manifest(encrypted = true, vault = true, schemaVersion = Manifest.SCHEMA_VAULT))
                    .also { it.save() }
            }
            loaded.setDek(key)
            val next = readState(loaded)
            check(prefs.edit().remove("failed_attempts").remove("retry_at").commit())
            store = loaded
            vaultKey?.fill(0)
            vaultKey = key
            mutableState.value = next
        } catch (failure: Throwable) {
            key.fill(0)
            throw failure
        }
    }

    suspend fun lock() = disk {
        store?.setDek(null)
        store = null
        vaultKey?.fill(0)
        vaultKey = null
        mutableState.value = VaultState(initialized = protectedSettings.exists())
    }

    suspend fun save(account: SteamGuardAccount) = disk {
        val open = requireOpen()
        val current = mutableState.value.accounts.firstOrNull { AccountStore.storageId(it) == AccountStore.storageId(account) }
        val updated = current?.let(account::withProfileFrom) ?: account
        check(open.saveAccount(updated)) { "The account could not be saved." }
        mutableState.value = readState(open)
    }

    suspend fun saveProfile(profile: SteamGuardAccount): Boolean = disk {
        // A network response may arrive after locking, deleting an account, or
        // refreshing its session. Only merge into an account that still exists.
        val open = store ?: return@disk false
        val current = mutableState.value.accounts.firstOrNull {
            AccountStore.storageId(it) == AccountStore.storageId(profile)
        } ?: return@disk false
        check(open.saveAccount(current.withProfileFrom(profile))) { "The profile could not be saved." }
        mutableState.value = readState(open)
        true
    }

    suspend fun saveAuthenticator(account: SteamGuardAccount) = disk {
        val open = requireOpen()
        val current = mutableState.value.accounts.firstOrNull { AccountStore.storageId(it) == AccountStore.storageId(account) }
        val updated = current?.let(account::withProfileFrom) ?: account
        // Keep a separately protected copy before replacing a local authenticator.
        // Steam may already have revoked the old one.
        runCatching { protectedSettings.writeProtected(RECOVERY, updated.toJson().toString()) }
        if (!open.saveAccount(updated)) {
            mutableState.value = readState(open)
            error("Steam returned new authenticator secrets, but saving the account failed.")
        }
        runCatching { protectedSettings.removeProtected(RECOVERY) }
        mutableState.value = readState(open)
    }

    suspend fun recoveryAccount(): SteamGuardAccount? = disk {
        requireOpen()
        protectedSettings.readProtected(RECOVERY)?.let(JsonCoerce::parseObject)
            ?.let(SteamGuardAccount::fromJson)
    }

    suspend fun remove(account: SteamGuardAccount) = disk {
        val open = requireOpen()
        check(open.removeAccount(account)) { "The account could not be removed." }
        mutableState.value = readState(open)
    }

    suspend fun import(files: Map<String, String>, manifest: SdaManifest?, password: String?): ImportReport = disk {
        val open = requireOpen()
        val result = readSdaBundle(files, manifest, password)
        val existing = mutableState.value.accounts.toMutableList()
        var imported = 0
        var skipped = 0
        var unreadable = result.failed.size
        for (entry in result.imported) {
            val json = entry.toAccountJson()
            val account = json?.let {
                val normalized = SteamGuardAccount.fromJson(MaFileNormalizer.normalize(it, entry.entry.filename))
                if (normalized.steamId == 0L && entry.entry.steamId > 0L) {
                    normalized.withSession(normalized.session.copy(steamId = entry.entry.steamId))
                } else normalized
            }
            if (account == null || runCatching { account.generateCode(0L) }.isFailure) {
                unreadable++
                continue
            }
            if (existing.any {
                    (account.steamId > 0 && it.steamId == account.steamId) ||
                        it.sharedSecret == account.sharedSecret
                }) {
                skipped++
                continue
            }
            if (open.saveAccount(account.copy(password = null))) {
                existing += account
                imported++
            } else {
                unreadable++
            }
        }
        mutableState.value = readState(open)
        ImportReport(imported, skipped, unreadable)
    }

    private fun requireOpen(): AccountStore = checkNotNull(store) { "Unlock the vault first." }

    private suspend fun readState(open: AccountStore): VaultState {
        val accounts = open.getAllAccounts()
        return VaultState(true, true, accounts, (open.entries.size - accounts.size).coerceAtLeast(0), prefs.contains(RECOVERY))
    }

    private suspend fun <T> disk(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    companion object {
        private const val RECOVERY = "authenticator_recovery"
    }
}
