package cc.ptoe.steam.enhanced.app

internal interface VaultUnlockTarget {
    val isUnlocked: Boolean
    fun needsPinMigration(): Boolean
    suspend fun open()
    suspend fun openWithDeviceKey(key: ByteArray)
}

internal interface DeviceVaultUnlock {
    val enabled: Boolean
    suspend fun unlock(): ByteArray?
}

/** Owns the startup attempt across activity recreation, without retaining an Activity. */
internal class VaultUnlockController(private val vault: VaultUnlockTarget) {
    private var startupAttempted = false

    suspend fun onOpen(device: DeviceVaultUnlock?) {
        if (startupAttempted) return
        startupAttempted = true
        unlock(device)
    }

    suspend fun unlock(device: DeviceVaultUnlock?): Boolean {
        if (vault.isUnlocked) return true
        if (device?.enabled == true) {
            // Cancellation and authentication errors must never fall back to
            // opening with the unauthenticated device key.
            val key = device.unlock() ?: return false
            try {
                vault.openWithDeviceKey(key)
            } finally {
                key.fill(0)
            }
        } else {
            if (vault.needsPinMigration()) return false
            vault.open()
        }
        return true
    }
}
