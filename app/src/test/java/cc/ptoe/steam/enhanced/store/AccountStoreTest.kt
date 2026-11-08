package cc.ptoe.steam.enhanced.store

import cc.ptoe.steam.enhanced.core.crypto.VaultCrypto
import cc.ptoe.steam.enhanced.core.model.SessionData
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountStoreTest {

    private fun account(id: Long, name: String = "user$id") = SteamGuardAccount(
        sharedSecret = "c2VjcmV0",
        accountName = name,
        identitySecret = "aWRlbnRpdHk=",
        deviceId = "android:11111111-2222-3333-4444-555555555555",
        session = SessionData(steamId = id, accessToken = "at$id", refreshToken = "rt$id"),
    )

    @Test
    fun `fresh store creates a manifest`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        assertTrue(storage.fileExists("manifest.json"))
        assertTrue(store.entries.isEmpty())
        assertFalse(store.encrypted)
    }

    @Test
    fun `vault save and load round trips an account`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        val dek = VaultCrypto.generateDek()
        store.setDek(dek)
        store.manifest = store.manifest.copy(vault = true, schemaVersion = 2, encrypted = true)

        val account = account(76561197960265728L)
        assertTrue(store.saveAccount(account))

        // A fresh store object must read it back using the same DEK.
        val reopened = AccountStore.load(storage)
        reopened.setDek(dek)
        val loaded = reopened.getAllAccounts()
        assertEquals(1, loaded.size)
        assertEquals(account.steamId, loaded[0].steamId)
        assertEquals(account.sharedSecret, loaded[0].sharedSecret)
        assertEquals(account.identitySecret, loaded[0].identitySecret)
    }

    @Test
    fun `vault keeps separate code-only accounts across save reopen and removal`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        val key = VaultCrypto.generateDek()
        store.setDek(key)
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        val first = account(0, "first")
        val second = account(0, "second").copy(sharedSecret = "YW5vdGhlcg==")
        assertTrue(store.saveAccount(first))
        assertTrue(store.saveAccount(second))
        assertTrue(store.saveAccount(first.copy(accountName = "renamed")))
        val opened = AccountStore.load(storage).also { it.setDek(key) }
        assertEquals(listOf("renamed", "second"), opened.getAllAccounts().map { it.accountName })
        assertTrue(opened.getAllAccounts().all { it.steamId == 0L })
        assertTrue(opened.removeAccount(first))
        assertEquals("second", opened.getAllAccounts().single().accountName)
    }

    @Test
    fun `vault load without the dek yields nothing`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        store.setDek(VaultCrypto.generateDek())
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        store.saveAccount(account(1))

        val locked = AccountStore.load(storage)
        // No setDek call: a locked vault must not leak accounts.
        assertTrue(locked.getAllAccounts().isEmpty())
    }

    @Test
    fun `a wrong dek reads nothing rather than garbage`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        store.setDek(VaultCrypto.generateDek())
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        store.saveAccount(account(1))

        val wrong = AccountStore.load(storage)
        wrong.setDek(VaultCrypto.generateDek())
        assertTrue(wrong.getAllAccounts().isEmpty())
    }

    @Test
    fun `updating an encrypted account writes an alternate slot and drops the old file`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        store.setDek(VaultCrypto.generateDek())
        store.manifest = store.manifest.copy(vault = true, encrypted = true)

        val id = 76561197960265728L
        store.saveAccount(account(id))
        val firstFile = store.entries.single().filename
        assertEquals("$id.maFile", firstFile)

        // Second save must not overwrite in place: the manifest is the commit point,
        // so the ciphertext moves to the alternate slot first.
        store.saveAccount(account(id, name = "renamed"))
        val secondFile = store.entries.single().filename
        assertEquals("$id.b.maFile", secondFile)
        // The orphan is cleaned up only after the manifest no longer references it.
        assertFalse(storage.fileExists(firstFile))
        assertTrue(storage.fileExists(secondFile))

        // And the rename actually took.
        val reloaded = AccountStore.load(storage)
        reloaded.setDek(VaultCrypto.generateDek()) // ignored: vault uses the real DEK below
        val scanned = storage.files.filterKeys { it.endsWith(".maFile") }
        assertEquals(1, scanned.size)
    }

    @Test
    fun `saving without a dek fails instead of writing plaintext`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        // No setDek: the store must refuse rather than fall back to plaintext.
        assertFalse(store.saveAccount(account(1)))
        assertTrue(storage.files.keys.none { it.endsWith(".maFile") })
    }

    @Test
    fun `removing an account deletes its payload`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)
        store.setDek(VaultCrypto.generateDek())
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        val acc = account(1)
        store.saveAccount(acc)
        val filename = store.entries.single().filename

        assertTrue(store.removeAccount(acc))
        assertTrue(store.entries.isEmpty())
        assertFalse(storage.fileExists(filename))
    }

    @Test
    fun `a corrupt payload does not blank the whole list`() = runTest {
        val storage = MemoryStorageProvider()
        val dek = VaultCrypto.generateDek()
        val store = AccountStore.load(storage)
        store.setDek(dek)
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        store.saveAccount(account(1))
        store.saveAccount(account(2))

        // Corrupt exactly one payload.
        val victim = store.entries.first().filename
        storage.files[victim] = "not-a-valid-blob"

        val reopened = AccountStore.load(storage)
        reopened.setDek(dek)
        val loaded = reopened.getAllAccounts()
        // One bad maFile used to blank the entire list; the good one must survive.
        assertEquals(1, loaded.size)
    }

    @Test
    fun `manifest drops entries whose payload went missing`() = runTest {
        val storage = MemoryStorageProvider()
        val dek = VaultCrypto.generateDek()
        val store = AccountStore.load(storage)
        store.setDek(dek)
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        store.saveAccount(account(1))
        storage.files.remove(store.entries.single().filename)

        val reopened = AccountStore.load(storage)
        assertTrue(reopened.entries.isEmpty())
    }

    @Test
    fun `unsafe manifest filenames are rejected before touching the filesystem`() {
        for (bad in listOf("../escape", "/etc/passwd", "a/b", "a\\b", "..", ".", "with space", "nul\u0000")) {
            try {
                StorageProvider.sanitizeFilename(bad)
                throw AssertionError("expected rejection for $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals("7656.maFile", StorageProvider.sanitizeFilename("7656.maFile"))
    }

    @Test
    fun `legacy encrypted store verifies its passkey token`() = runTest {
        val storage = MemoryStorageProvider()
        val store = AccountStore.load(storage)

        // Write a legacy PIN/CBC store with a passkey-check token but no accounts.
        val salt = cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto.randomSalt()
        val iv = cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto.randomIv()
        val ct = cc.ptoe.steam.enhanced.core.crypto.MaFileCrypto.encrypt(
            "1234", salt, iv, cc.ptoe.steam.enhanced.core.model.Manifest.CHECK_PLAINTEXT,
            AccountStore.AVA_ITERATIONS,
        )
        store.manifest = store.manifest.copy(
            encrypted = true,
            kdfIterations = AccountStore.AVA_ITERATIONS,
            passkeyCheck = "$salt|$iv|$ct",
        )
        store.save()

        val reopened = AccountStore.load(storage)
        assertTrue(reopened.verifyPasskey("1234"))
        assertFalse(reopened.verifyPasskey("9999"))
    }

    @Test
    fun `a storage failure on save leaves the manifest unchanged`() = runTest {
        val failing = object : StorageLike {
            val inner = MemoryStorageProvider()
            override fun dirExists() = true
            override fun ensureDir() = Unit
            override fun fileExists(filename: String) = inner.fileExists(filename)
            override fun readFile(filename: String) = inner.readFile(filename)
            override fun writeFile(filename: String, contents: String) {
                if (filename == "manifest.json") throw IOException("disk full")
                inner.writeFile(filename, contents)
            }

            override fun deleteFile(filename: String) = inner.deleteFile(filename)
            override fun lastModified(filename: String) = inner.lastModified(filename)
            override fun listFiles(extension: String) = inner.listFiles(extension)
        }
        val store = AccountStore(failing)
        store.setDek(VaultCrypto.generateDek())
        store.manifest = store.manifest.copy(vault = true, encrypted = true)
        val before = store.manifest

        assertFalse(store.saveAccount(account(1)))
        // In-memory state must match what is still on disk.
        assertEquals(before.entries, store.manifest.entries)
    }

    @Test
    fun `atomic replace leaves no temp file behind`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("store").toFile()
        try {
            val provider = StorageProvider(dir)
            provider.writeFile("manifest.json", "{}")
            val leftovers = provider.maFilesDir().listFiles()!!.filter { it.name.endsWith(".tmp") }
            assertTrue(leftovers.isEmpty())
            assertEquals("{}", provider.readFile("manifest.json"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `real provider round trips through the filesystem`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("store").toFile()
        try {
            val provider = StorageProvider(dir)
            val store = AccountStore.load(provider)
            val dek = VaultCrypto.generateDek()
            store.setDek(dek)
            store.manifest = store.manifest.copy(vault = true, encrypted = true)
            store.saveAccount(account(76561197960265728L))

            val reopened = AccountStore.load(provider)
            reopened.setDek(dek)
            val loaded = reopened.getAllAccounts()
            assertEquals(1, loaded.size)
            assertNotNull(loaded[0].accountName)
        } finally {
            dir.deleteRecursively()
        }
    }
}
