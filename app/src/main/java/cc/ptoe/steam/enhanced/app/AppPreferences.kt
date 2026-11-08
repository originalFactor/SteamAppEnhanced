package cc.ptoe.steam.enhanced.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class Appearance { SYSTEM, LIGHT, DARK }
internal enum class Skin { STEAM, NEON, PIXEL }

internal class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("interface", Context.MODE_PRIVATE)

    var appearance by mutableStateOf(read("appearance", Appearance.SYSTEM))
        private set
    var skin by mutableStateOf(read("skin", Skin.STEAM))
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

    fun useSkin(value: Skin) {
        prefs.edit().putString("skin", value.name).apply()
        skin = value
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
