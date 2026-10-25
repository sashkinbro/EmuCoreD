package com.sbro.emucored.core.platform

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class CoreOptionHelpTest {
    @Test fun everyAdvertisedOptionHasExplicitHelpForItsPlatform() {
        ConsolePlatform.entries.forEach { platform ->
            CoreOptionSchema.options(platform).forEach { option ->
                assertNotNull("Missing help for $platform/${option.key}", CoreOptionHelp.resourceName(platform, option.key))
            }
        }
        assertNull(CoreOptionHelp.resourceName(ConsolePlatform.NES, "resolution"))
        assertNull(CoreOptionHelp.resourceName(ConsolePlatform.GBA, "unknown_future_option"))
        assertNull(CoreOptionHelp.resourceName(ConsolePlatform.NDS, "graphics_backend"))
    }

    @Test fun aspectRatioExplainsTheActualBackendRatherThanSharingMisleadingHelp() {
        assertEquals("core_option_help_aspect_ratio_dolphin", CoreOptionHelp.resourceName(ConsolePlatform.WII, "aspect_ratio"))
        assertEquals("core_option_help_aspect_ratio_frame", CoreOptionHelp.resourceName(ConsolePlatform.GB, "aspect_ratio"))
    }

    @Test fun englishAndUkrainianContainEveryHelpAndSpecificTradeoffs() {
        val english = helpResources("values")
        val ukrainian = helpResources("values-uk")
        val required = ConsolePlatform.entries.flatMap { platform ->
            CoreOptionSchema.options(platform).map { requireNotNull(CoreOptionHelp.resourceName(platform, it.key)) }
        }.toSet()
        val withFooter = required + "core_option_help_recommended_default"
        assertEquals(withFooter, english.keys)
        assertEquals(withFooter, ukrainian.keys)
        assertEquals("Recommended starting value: %1\$s.", english["core_option_help_recommended_default"])
        for (name in required) {
            assertTrue("Incomplete English help: $name", english.getValue(name).length >= 100)
            assertTrue("Incomplete Ukrainian help: $name", ukrainian.getValue(name).length >= 100)
            assertNotEquals("Untranslated Ukrainian: $name", english.getValue(name), ukrainian.getValue(name))
        }
        assertTrue(english.getValue("core_option_help_resolution").contains("GPU"))
        assertTrue(english.getValue("core_option_help_skip_efb_access").contains("CPU"))
        assertTrue(english.getValue("core_option_help_bsnes_cpu_overclock").contains("timing"))
        assertTrue(english.getValue("core_option_help_audio_buffer_size").contains("latency"))
        assertTrue(english.getValue("core_option_help_shader_compilation").contains("missing"))
        assertTrue(english.values.none { it.contains("always faster", ignoreCase = true) })
    }

    @Test fun everySupportedLocaleHasCompleteTranslatedHelpAndFormatting() {
        val english = helpResources("values")
        val placeholder = Regex("%[0-9]+" + Regex.escape("$") + "[sd]")
        listOf("ar", "cs", "de", "es", "fa", "fr", "hi", "in", "it", "ja", "ko", "pl", "pt", "ru", "tr", "uk", "zh").forEach { locale ->
            val translated = helpResources("values-$locale")
            assertEquals("Incomplete help locale $locale", english.keys, translated.keys)
            english.forEach { (name, source) ->
                val text = translated.getValue(name)
                assertTrue("Empty help $locale/$name", text.isNotBlank())
                assertNotEquals("English fallback in $locale/$name", source, text)
                assertEquals("Broken help formatting $locale/$name",
                    placeholder.findAll(source).map { it.value }.toList(),
                    placeholder.findAll(text).map { it.value }.toList())
            }
        }
    }

    private fun helpResources(locale: String): Map<String, String> {
        val app = if (File("src/main/res").isDirectory) File(".") else File("app")
        val file = File(app, "src/main/res/$locale/core_option_help.xml")
        assertTrue("Missing translated core help: $locale", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val node = strings.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }.also { assertEquals("Duplicate help IDs", strings.length, it.size) }
    }
}
