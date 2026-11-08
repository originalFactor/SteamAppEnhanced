package cc.ptoe.steam.enhanced.store

import cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto
import cc.ptoe.steam.enhanced.core.crypto.VaultCrypto
import cc.ptoe.steam.enhanced.core.dlog
import cc.ptoe.steam.enhanced.core.JsonCoerce
import cc.ptoe.steam.enhanced.core.MaFileNormalizer
import cc.ptoe.steam.enhanced.core.model.Manifest
import cc.ptoe.steam.enhanced.core.model.ManifestEntry
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Thrown when `manifest.json` exists but cannot be parsed. */
class ManifestParseException : Exception("manifest.json is not valid")

/**
 * Manages the `maFiles/` directory: the manifest plus the per-account `*.maFile`
 * payloads.
 *
 * Ported from AVA's `services/account_store.dart` — a faithful port of the legacy
 * C# `Manifest` business logic, with file IO delegated to a [StorageLike].
 *
 * Two schemes coexist:
 *  - **vault** (this app's own): random 256-bit DEK + AES-256-GCM. The DEK is held
 *    in Keystore-backed storage and supplied via [setDek] at unlock.
 *  - **legacy** (SDA-compatible): PIN-derived key + AES-256-CBC, with the salt/IV
 *    in the manifest entry. Only reachable when importing an old encrypted store.
 *
 * All mutating methods are `suspend` and serialize on a mutex: concurrent writers
 * (session refresh, avatar refresh, save, unlock migration) otherwise interleave
 * check-then-act sequences over the shared manifest — see the alt-slot
 * double-delete in [saveAccount].
 */
class AccountStore(
    private val storage: StorageLike,
    manifest: Manifest = Manifest(),
) {
    @Volatile
    var manifest: Manifest = manifest

    /** The in-memory DEK for the vault scheme, set at unlock. Null until then. */
    @Volatile
    private var dek: ByteArray? = null

    private val writeLock = kotlinx.coroutines.sync.Mutex()

    val encrypted: Boolean
        get() = manifest.encrypted

    val isVault: Boolean
        get() = manifest.vault

    val entries: List<ManifestEntry>
        get() = manifest.entries

    /** Provides (or clears) the vault DEK for the current unlocked session. */
    fun setDek(dek: ByteArray?) {
        this.dek = dek
    }

    companion object {
        /**
         * PBKDF2 rounds for stores this app writes in the legacy scheme.
         *
         * Deliberately low: a 6-digit PIN's keyspace dominates, so extra rounds buy
         * nothing but unlock latency.
         */
        const val AVA_ITERATIONS = 100

        /** Vault DEK is present — used to distinguish "locked" from "no vault". */
        fun hasDek(store: AccountStore): Boolean = store.dek != null

        /** A local-only identifier for code-only exports. Never sent to Steam. */
        fun storageId(account: SteamGuardAccount): Long {
            if (account.steamId > 0L) return account.steamId
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(account.sharedSecret.orEmpty().encodeToByteArray())
            return java.nio.ByteBuffer.wrap(digest).long or Long.MIN_VALUE
        }

        /**
         * Loads the manifest from disk, creating a fresh one if none exists.
         * Drops entries whose maFile is missing (`RecomputeExistingEntries`).
         */
        @Throws(ManifestParseException::class)
        suspend fun load(storage: StorageLike, pruneMissing: Boolean = true): AccountStore {
            if (!storage.dirExists()) {
                val store = AccountStore(storage, Manifest())
                store.save()
                return store
            }
            if (!storage.fileExists("manifest.json")) throw ManifestParseException()
            val manifest = try {
                val text = storage.readFile("manifest.json")
                val json = JsonCoerce.parseObject(text) ?: throw ManifestParseException()
                Manifest.fromJson(json)
            } catch (e: Exception) {
                if (e is ManifestParseException) throw e
                throw ManifestParseException()
            }
            val store = AccountStore(storage, manifest)
            if (manifest.encrypted && !manifest.vault &&
                manifest.entries.isEmpty() && manifest.passkeyCheck == null
            ) {
                store.manifest = manifest.copy(encrypted = false)
                store.save()
            }
            if (pruneMissing) store.recomputeExistingEntries()
            return store
        }
    }

    private suspend fun recomputeExistingEntries() {
        val kept = manifest.entries.filter { storage.fileExists(it.filename) }
        manifest = manifest.copy(entries = kept)
        if (!manifest.vault && manifest.entries.isEmpty() && manifest.passkeyCheck == null) {
            manifest = manifest.copy(encrypted = false)
        }
    }

    suspend fun save() = writeLock.withLock { saveUnlocked() }

    private suspend fun saveUnlocked() {
        storage.ensureDir()
        storage.writeFile("manifest.json", manifest.toJson().toString())
    }

    /**
     * Decrypts and returns all accounts.
     *
     * Returns empty on a bad passkey, matching the C# contract used by
     * [verifyPasskey] — but only when the key genuinely cannot open *anything*. If
     * at least one entry decrypted, the rest are individually corrupt, and the good
     * accounts are kept instead of hiding them all (a single bad maFile used to
     * blank the whole list).
     */
    suspend fun getAllAccounts(passKey: String? = null, limit: Int = -1): List<SteamGuardAccount> =
        withContext(Dispatchers.Default) {
            if (manifest.vault && dek == null) return@withContext emptyList()
            if (passKey == null && manifest.encrypted && !manifest.vault) {
                return@withContext emptyList()
            }
            val chosen = if (limit < 0) manifest.entries else manifest.entries.take(limit)

            // Read raw payloads (IO), then decrypt them all in one batch so the
            // expensive PBKDF2 derivations do not interleave with UI work.
            val raws = withContext(Dispatchers.IO) {
                chosen.map { entry -> runCatching { storage.readFile(entry.filename) }.getOrNull() }
            }
            val texts: List<String?> = when {
                manifest.vault -> {
                    val key = dek ?: return@withContext emptyList()
                    raws.map { raw -> raw?.let { VaultCrypto.decryptPayload(key, it) } }
                }

                manifest.encrypted -> {
                    chosen.indices.map { i ->
                        val salt = chosen[i].salt ?: return@map null
                        val iv = chosen[i].iv ?: return@map null
                        val raw = raws[i] ?: return@map null
                        MaFileCrypto.decrypt(passKey!!, salt, iv, raw, manifest.kdfIterations)
                    }
                }

                else -> raws
            }

            val accounts = mutableListOf<SteamGuardAccount>()
            var decodedAny = false
            var failed = 0
            for (text in texts) {
                if (text == null) {
                    failed++ // corrupt blob OR wrong key — decided below
                    continue
                }
                val parsed = JsonCoerce.parseObject(text)
                if (parsed == null) {
                    failed++
                    continue
                }
                accounts.add(
                    SteamGuardAccount.fromJson(MaFileNormalizer.normalize(parsed)),
                )
                decodedAny = true
            }
            if (failed > 0) dlog("getAllAccounts: $failed/${chosen.size} unreadable")
            // For the vault the DEK was already validated by its wrapper, so a null is
            // always corruption, never a wrong key; return whatever decoded.
            if (chosen.isNotEmpty() && !decodedAny && !manifest.vault) {
                return@withContext emptyList()
            }
            accounts
        }

    suspend fun verifyPasskey(passkey: String): Boolean {
        if (!manifest.encrypted) return true
        // Prefer the verification token (works with zero accounts).
        val check = manifest.passkeyCheck
        if (check != null) {
            val parts = check.split("|")
            if (parts.size == 3) {
                val dec = MaFileCrypto.decryptBatch(
                    passkey,
                    listOf(Triple(parts[0], parts[1], parts[2])),
                    manifest.kdfIterations,
                ).first()
                return dec == Manifest.CHECK_PLAINTEXT
            }
        }
        if (manifest.entries.isEmpty()) return true
        return getAllAccounts(passKey = passkey, limit = 1).size == 1
    }

    /**
     * Saves (or updates) an account.
     *
     * In vault mode [encrypt]/[passKey] are legacy no-ops: every payload is sealed
     * with the in-memory DEK.
     */
    suspend fun saveAccount(
        account: SteamGuardAccount,
        encrypt: Boolean = true,
        passKey: String? = null,
    ): Boolean = writeLock.withLock { saveAccountUnlocked(account, encrypt, passKey) }

    private suspend fun saveAccountUnlocked(
        account: SteamGuardAccount,
        encrypt: Boolean,
        passKey: String?,
    ): Boolean {
        if (manifest.vault) {
            val key = dek ?: return false
            return saveVault(account, key)
        }
        if (encrypt && passKey.isNullOrEmpty()) return false
        if (!encrypt && manifest.encrypted) return false

        var salt: String? = null
        var iv: String? = null
        var jsonAccount = account.toJson().toString()

        if (encrypt) {
            salt = MaFileCrypto.randomSalt()
            iv = MaFileCrypto.randomIv()
            jsonAccount = MaFileCrypto.encrypt(passKey!!, salt, iv, jsonAccount, manifest.kdfIterations)
        }

        val idx = manifest.entries.indexOfFirst { it.steamId == account.steamId }
        val oldEntry = if (idx >= 0) manifest.entries[idx] else null

        // Encrypted UPDATE of an existing account: write the new ciphertext under a
        // *fresh* filename so the manifest (carrying the matching salt/iv) is the
        // single atomic commit point. Overwriting in place would let a crash leave
        // the manifest's salt/iv describing a different file's ciphertext — the
        // legacy corruption window. New accounts and the unencrypted path have no
        // salt/iv to desync, so they keep the canonical name.
        val filename = if (encrypt && oldEntry != null) {
            altSlot(oldEntry.filename, account.steamId)
        } else {
            oldEntry?.filename ?: "${account.steamId}.maFile"
        }
        val newEntry = ManifestEntry(
            steamId = account.steamId,
            iv = iv,
            salt = salt,
            filename = filename,
        )

        val newEntries = manifest.entries.toMutableList()
        if (idx >= 0) newEntries[idx] = newEntry else newEntries.add(newEntry)

        val previous = manifest
        manifest = manifest.copy(
            entries = newEntries,
            encrypted = encrypt || manifest.encrypted,
        )
        return try {
            // Payload before manifest (both atomic): a crash in between leaves the
            // manifest pointing at the previous (valid) file — for an update that is
            // the untouched old file, for a new account an orphan payload that
            // recompute ignores. save() is the commit point.
            storage.writeFile(filename, jsonAccount)
            saveUnlocked()
            // The old ciphertext is now orphaned (the manifest points at the new
            // file). Re-check after the awaits: only delete a file the manifest no
            // longer references (a concurrent writer may have re-pointed it).
            if (oldEntry != null && oldEntry.filename != filename &&
                manifest.entries.none { it.filename == oldEntry.filename }
            ) {
                runCatching { storage.deleteFile(oldEntry.filename) }
            }
            true
        } catch (e: Exception) {
            // Restore in-memory state to match what is still on disk.
            manifest = previous
            false
        }
    }

    /** Vault-mode save: seal with the DEK, then commit the manifest. */
    private suspend fun saveVault(account: SteamGuardAccount, key: ByteArray): Boolean {
        val salt = MaFileCrypto.randomSalt()
        val iv = MaFileCrypto.randomIv()
        val ciphertext = VaultCrypto.encryptPayload(key, account.toJson().toString())

        val id = storageId(account)
        val idx = accountIndex(account)
        val oldEntry = if (idx >= 0) manifest.entries[idx] else null
        val filename = if (oldEntry != null) {
            altSlot(oldEntry.filename, id)
        } else {
            "$id.maFile"
        }
        // In vault mode the salt/iv are decorative — the DEK does the work — but they
        // are kept so an exported manifest still looks like an SDA one.
        val newEntry = ManifestEntry(
            steamId = id,
            iv = iv,
            salt = salt,
            filename = filename,
        )
        val newEntries = manifest.entries.toMutableList()
        if (idx >= 0) newEntries[idx] = newEntry else newEntries.add(newEntry)

        val previous = manifest
        manifest = manifest.copy(entries = newEntries, encrypted = true)
        return try {
            storage.writeFile(filename, ciphertext)
            saveUnlocked()
            if (oldEntry != null && oldEntry.filename != filename &&
                manifest.entries.none { it.filename == oldEntry.filename }
            ) {
                runCatching { storage.deleteFile(oldEntry.filename) }
            }
            true
        } catch (e: Exception) {
            manifest = previous
            false
        }
    }

    suspend fun removeAccount(account: SteamGuardAccount, deleteMaFile: Boolean = true): Boolean =
        writeLock.withLock {
            val idx = accountIndex(account)
            if (idx < 0) return@withLock true
            val entry = manifest.entries[idx]
            val remaining = manifest.entries.toMutableList().apply { removeAt(idx) }
            val clearEncryption = !manifest.vault &&
                remaining.isEmpty() &&
                manifest.passkeyCheck == null
            val previous = manifest
            manifest = previous.copy(
                entries = remaining,
                encrypted = if (clearEncryption) false else manifest.encrypted,
            )
            try {
                saveUnlocked()
            } catch (_: java.io.IOException) {
                manifest = previous
                return@withLock false
            }
            if (deleteMaFile) {
                runCatching { storage.deleteFile(entry.filename) }
                    .onFailure { dlog("Could not remove obsolete account payload") }
            }
            true
        }

    private fun accountIndex(account: SteamGuardAccount): Int {
        val id = if (manifest.vault) storageId(account) else account.steamId
        val index = manifest.entries.indexOfFirst { it.steamId == id }
        if (index >= 0 || id >= 0) return index

        // Early vaults stored a single code-only account under ID zero.
        val key = dek ?: return -1
        return manifest.entries.indexOfFirst { entry ->
            entry.steamId == 0L && runCatching {
                val plaintext = VaultCrypto.decryptPayload(key, storage.readFile(entry.filename))
                val json = plaintext?.let(JsonCoerce::parseObject) ?: return@runCatching false
                SteamGuardAccount.fromJson(MaFileNormalizer.normalize(json)).sharedSecret ==
                    account.sharedSecret
            }.getOrDefault(false)
        }
    }

    /**
     * The alternate filename slot for [steamId], used to write an updated ciphertext
     * without overwriting the one the current manifest still points at (two-phase
     * commit). Toggles between `<id>.maFile` and `<id>.b.maFile`.
     */
    private fun altSlot(current: String, steamId: Long): String {
        val a = "$steamId.maFile"
        return if (current == a) "$steamId.b.maFile" else a
    }

    /**
     * Re-encrypts every maFile with [newKey] (or decrypts when [newKey] is null) for
     * the legacy scheme, or switches the store into vault mode when [vaultDek] is
     * supplied.
     *
     * Two-phase for the legacy path: every account is re-written to a *fresh*
     * filename first, then the manifest (new filenames + salt/iv + iteration count) is
     * committed in one atomic save; only afterwards are the old ciphertexts deleted.
     * Until that save lands the old files and old manifest stay fully consistent, so
     * a crash mid-rotation leaves the store openable with the old key rather than a
     * half-rotated, partly-undecryptable mess.
     */
    suspend fun changeEncryptionKey(oldKey: String?, newKey: String?): Boolean =
        writeLock.withLock {
            if (manifest.encrypted && !manifest.vault) {
                if (oldKey == null || !verifyPasskey(oldKey)) return@withLock false
            }
            val oldIterations = manifest.kdfIterations
            val toEncrypt = newKey != null
            val newIterations = if (toEncrypt) AVA_ITERATIONS else oldIterations

            // Phase 1: write every re-keyed payload to a new filename. No manifest
            // mutation yet, so a failure here leaves the store untouched.
            val oldFiles = mutableListOf<String>()
            data class Rewritten(val filename: String, val salt: String?, val iv: String?)
            val rewritten = HashMap<Long, Rewritten>()

            for (entry in manifest.entries) {
                if (!storage.fileExists(entry.filename)) continue
                var contents = storage.readFile(entry.filename)
                if (manifest.encrypted && !manifest.vault) {
                    val dec = MaFileCrypto.decrypt(
                        oldKey!!,
                        entry.salt!!,
                        entry.iv!!,
                        contents,
                        oldIterations,
                    ) ?: return@withLock false
                    contents = dec
                }
                var newSalt: String? = null
                var newIv: String? = null
                var toWrite = contents
                if (toEncrypt) {
                    newSalt = MaFileCrypto.randomSalt()
                    newIv = MaFileCrypto.randomIv()
                    toWrite = MaFileCrypto.encrypt(
                        newKey!!, newSalt, newIv, contents, newIterations,
                    )
                }
                val newName = altSlot(entry.filename, entry.steamId)
                storage.writeFile(newName, toWrite)
                oldFiles.add(entry.filename)
                rewritten[entry.steamId] = Rewritten(newName, newSalt, newIv)
            }

            // Phase 2 (commit): point every entry at its new file + params, save once.
            val newEntries = manifest.entries.map { entry ->
                val r = rewritten[entry.steamId] ?: return@map entry
                entry.copy(filename = r.filename, salt = r.salt, iv = r.iv)
            }
            var newPasskeyCheck: String? = null
            if (toEncrypt) {
                // Store a passkey-verification token so the PIN can be checked even
                // with no accounts.
                val salt = MaFileCrypto.randomSalt()
                val iv = MaFileCrypto.randomIv()
                val ct = MaFileCrypto.encrypt(
                    newKey!!, salt, iv, Manifest.CHECK_PLAINTEXT, newIterations,
                )
                newPasskeyCheck = "$salt|$iv|$ct"
            }
            manifest = manifest.copy(
                entries = newEntries,
                encrypted = toEncrypt,
                kdfIterations = newIterations,
                passkeyCheck = newPasskeyCheck,
            )
            saveUnlocked() // commit point — new files are now the source of truth

            // Delete the old ciphertexts the manifest no longer references. A leftover
            // is harmless (recompute ignores unreferenced files); never delete a name
            // a rewrite reused.
            val live = rewritten.values.map { it.filename }.toSet()
            for (f in oldFiles) {
                if (f in live) continue
                runCatching { storage.deleteFile(f) }
            }
            true
        }
}
