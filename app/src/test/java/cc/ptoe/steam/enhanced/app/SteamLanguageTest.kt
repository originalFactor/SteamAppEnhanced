package cc.ptoe.steam.enhanced.app

import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals

class SteamLanguageTest {
    @Test
    fun `only supported languages are requested and other locales fall back to English`() {
        assertEquals("english", steamLanguage(Locale.forLanguageTag("en-US")))
        assertEquals("schinese", steamLanguage(Locale.forLanguageTag("zh-CN")))
        for (tag in listOf("de-DE", "es-ES", "fr-FR", "ru-RU", "ja-JP", "zh-Hant", "zh-TW", "zh-HK", "zh-MO")) {
            assertEquals("english", steamLanguage(Locale.forLanguageTag(tag)), tag)
        }
    }

    @Test
    fun `simplified Chinese variants match Chinese resources with explicit script taking priority`() {
        for (tag in listOf("zh", "zh-SG", "zh-Hans", "zh-Hans-HK")) {
            assertEquals("schinese", steamLanguage(Locale.forLanguageTag(tag)), tag)
        }
        assertEquals("english", steamLanguage(Locale.forLanguageTag("zh-Hant-CN")))
    }
}
