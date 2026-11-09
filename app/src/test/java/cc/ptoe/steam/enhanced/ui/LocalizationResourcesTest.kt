package cc.ptoe.steam.enhanced.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationResourcesTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val locales = listOf("values-zh-rCN")
    private val placeholder = Regex("%(\\d+)\\$([dsfb])")

    @Test
    fun `only simplified Chinese and US English are advertised and packaged`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "xml/locales_config.xml"))
        val nodes = document.getElementsByTagName("locale")
        val supported = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }.toSet()
        assertEquals(setOf("zh-CN", "en-US"), supported)
        val translated = root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") && strings(it.name).isNotEmpty() }
        assertEquals(locales.toSet(), translated.map { it.name }.toSet())
    }

    private fun strings(folder: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (file in File(root, folder).listFiles().orEmpty().filter { it.extension == "xml" }) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as Element
                if (node.getAttribute("translatable") == "false") continue
                val key = node.getAttribute("name")
                assertFalse(result.containsKey(key), "Duplicate $key in $folder")
                result[key] = node.textContent
            }
        }
        return result
    }

    @Test
    fun `every supported language contains all translatable resource keys`() {
        val default = strings("values")
        assertTrue(default.isNotEmpty())
        for (locale in locales) assertEquals(default.keys, strings(locale).keys, "Missing or extra translations in $locale")
    }

    @Test
    fun `translations preserve numbered format argument types`() {
        val default = strings("values")
        for (locale in locales) for ((key, value) in strings(locale)) {
            fun arguments(text: String) = placeholder.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toSet()
            assertEquals(arguments(default.getValue(key)), arguments(value), "$locale/$key")
        }
    }

    @Test
    fun `screen labels and accessibility titles are not hardcoded English`() {
        val source = File(root.parentFile, "java/cc/ptoe/steam/enhanced/ui")
        val label = Regex("(?:Text|Heading|Field|Entry|Primary|TextButton)\\(\\s*\"[A-Za-z]")
        val title = Regex("(?:title|summary|onClickLabel)\\s*=\\s*\"[A-Za-z]")
        for (file in source.listFiles().orEmpty().filter { it.extension == "kt" }) {
            for ((index, line) in file.readLines().withIndex()) {
                assertFalse(label.containsMatchIn(line) || title.containsMatchIn(line), "${file.name}:${index + 1}: $line")
            }
        }
    }
}
