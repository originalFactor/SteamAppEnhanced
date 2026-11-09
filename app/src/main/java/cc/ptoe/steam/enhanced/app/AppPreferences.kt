package cc.ptoe.steam.enhanced.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class Appearance { SYSTEM, LIGHT, DARK }

internal class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("interface", Context.MODE_PRIVATE)

    var appearance by mutableStateOf(read("appearance", Appearance.SYSTEM))
        private set
    var activeAccountId by mutableStateOf(
        if (prefs.contains("active_account")) prefs.getLong("active_account", 0L) else null,
    )
        private set
    var autoSync by mutableStateOf(prefs.getBoolean("auto_sync", false))
        private set
    var showGuide by mutableStateOf(!prefs.getBoolean("guide_seen", false))
        private set
    var pollLogins by mutableStateOf(prefs.getBoolean("poll_logins", true))
        private set

    fun useAppearance(value: Appearance) {
        prefs.edit().putString("appearance", value.name).apply()
        appearance = value
    }

    fun useAccount(id: Long?) {
        if (id == activeAccountId) return
        val editor = prefs.edit()
        if (id == null) editor.remove("active_account") else editor.putLong("active_account", id)
        editor.apply()
        activeAccountId = id
    }

    fun useAutoSync(value: Boolean) {
        prefs.edit().putBoolean("auto_sync", value).apply()
        autoSync = value
    }

    fun useLoginPolling(value: Boolean) {
        prefs.edit().putBoolean("poll_logins", value).apply()
        pollLogins = value
    }

    fun showGuide(value: Boolean) {
        prefs.edit().putBoolean("guide_seen", !value).apply()
        showGuide = value
    }

    private inline fun <reified T : Enum<T>> read(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
}
