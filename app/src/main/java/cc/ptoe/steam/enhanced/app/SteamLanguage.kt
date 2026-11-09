package cc.ptoe.steam.enhanced.app

import java.util.Locale

/** Simplified Chinese follows zh-CN resources; unsupported locales fall back to en-US. */
internal fun steamLanguage(locale: Locale): String {
    val simplifiedChinese = locale.language == "zh" && when {
        locale.script == "Hans" -> true
        locale.script == "Hant" -> false
        else -> locale.country !in setOf("TW", "HK", "MO")
    }
    return if (simplifiedChinese) "schinese" else "english"
}
