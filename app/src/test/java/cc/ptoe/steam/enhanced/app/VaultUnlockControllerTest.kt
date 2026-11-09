package cc.ptoe.steam.enhanced.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultUnlockControllerTest {
    @Test
    fun `startup opens a vault without device authentication`() = runTest {
        val vault = FakeVault()
        VaultUnlockController(vault).onOpen(null)
        assertTrue(vault.isUnlocked)
        assertEquals(1, vault.passwordlessOpens)
    }

    @Test
    fun `enabled device authentication supplies the key and erases the temporary copy`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice()
        val expected = device.key!!.copyOf()
        VaultUnlockController(vault).onOpen(device)
        assertTrue(vault.isUnlocked)
        assertEquals(1, device.prompts)
        assertEquals(0, vault.passwordlessOpens)
        assertContentEquals(expected, vault.receivedKey)
        assertTrue(device.key!!.all { it == 0.toByte() })
    }

    @Test
    fun `cancelled authentication stays locked without an automatic second prompt`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice().apply { key = null }
        val controller = VaultUnlockController(vault)
        controller.onOpen(device)
        controller.onOpen(device)
        assertFalse(vault.isUnlocked)
        assertEquals(1, device.prompts)
        assertEquals(0, vault.passwordlessOpens)
        assertEquals(0, vault.authenticatedOpens)
    }

    @Test
    fun `user can retry after cancelling the startup prompt`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice().apply { key = null }
        val controller = VaultUnlockController(vault)
        controller.onOpen(device)
        device.key = ByteArray(32) { 7 }
        assertTrue(controller.unlock(device))
        assertTrue(vault.isUnlocked)
        assertEquals(2, device.prompts)
    }

    @Test
    fun `authentication failure never falls back to passwordless opening`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice().apply { failure = IllegalStateException("Key invalidated") }
        val controller = VaultUnlockController(vault)
        assertFailsWith<IllegalStateException> { controller.onOpen(device) }
        assertFalse(vault.isUnlocked)
        assertEquals(0, vault.passwordlessOpens)
        assertEquals(0, vault.authenticatedOpens)
    }

    @Test
    fun `coroutine cancellation never falls back to passwordless opening`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice().apply { failure = CancellationException() }
        assertFailsWith<CancellationException> { VaultUnlockController(vault).onOpen(device) }
        assertEquals(0, vault.passwordlessOpens)
        assertFalse(vault.isUnlocked)
    }

    @Test
    fun `temporary device key is erased even when opening fails`() = runTest {
        val vault = FakeVault().apply { failAuthenticatedOpen = true }
        val device = FakeDevice()
        assertFailsWith<IllegalStateException> { VaultUnlockController(vault).onOpen(device) }
        assertTrue(device.key!!.all { it == 0.toByte() })
        assertEquals(0, vault.passwordlessOpens)
        assertFalse(vault.isUnlocked)
    }

    @Test
    fun `activity recreation does not reopen a manually locked vault`() = runTest {
        val vault = FakeVault()
        val controller = VaultUnlockController(vault)
        controller.onOpen(null)
        vault.isUnlocked = false
        controller.onOpen(null)
        assertFalse(vault.isUnlocked)
        assertEquals(1, vault.passwordlessOpens)
        assertTrue(controller.unlock(null))
        assertEquals(2, vault.passwordlessOpens)
    }

    @Test
    fun `an already open vault does not prompt or reload`() = runTest {
        val vault = FakeVault().apply { isUnlocked = true }
        val device = FakeDevice()
        VaultUnlockController(vault).onOpen(device)
        assertEquals(0, device.prompts)
        assertEquals(0, vault.passwordlessOpens)
        assertEquals(0, vault.authenticatedOpens)
    }

    @Test
    fun `disabled device unlock uses passwordless opening`() = runTest {
        val vault = FakeVault()
        val device = FakeDevice().apply { enabled = false }
        VaultUnlockController(vault).onOpen(device)
        assertEquals(1, vault.passwordlessOpens)
        assertEquals(0, device.prompts)
    }

    @Test
    fun `legacy vault waits for PIN migration when device unlock is disabled`() = runTest {
        val vault = FakeVault().apply { migration = true }
        VaultUnlockController(vault).onOpen(null)
        assertFalse(vault.isUnlocked)
        assertEquals(0, vault.passwordlessOpens)
    }

    @Test
    fun `legacy vault can migrate through existing device authentication`() = runTest {
        val vault = FakeVault().apply { migration = true }
        VaultUnlockController(vault).onOpen(FakeDevice())
        assertTrue(vault.isUnlocked)
        assertEquals(1, vault.authenticatedOpens)
        assertEquals(0, vault.passwordlessOpens)
    }

    private class FakeVault : VaultUnlockTarget {
        override var isUnlocked = false
        var migration = false
        var passwordlessOpens = 0
        var authenticatedOpens = 0
        var receivedKey: ByteArray? = null
        var failAuthenticatedOpen = false

        override fun needsPinMigration() = migration

        override suspend fun open() {
            passwordlessOpens++
            isUnlocked = true
        }

        override suspend fun openWithDeviceKey(key: ByteArray) {
            authenticatedOpens++
            check(!failAuthenticatedOpen) { "Unreadable vault" }
            receivedKey = key.copyOf()
            isUnlocked = true
        }
    }

    private class FakeDevice : DeviceVaultUnlock {
        override var enabled = true
        var key: ByteArray? = ByteArray(32) { 42 }
        var failure: Exception? = null
        var prompts = 0

        override suspend fun unlock(): ByteArray? {
            prompts++
            failure?.let { throw it }
            return key
        }
    }
}
