package com.sbro.emucored.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringsLocalizationTest {
    private val requiredKeys = listOf(
        "emulation_shaders_tab",
        "emulation_shaders_title",
        "emulation_shaders_pack_title",
        "emulation_shaders_pack_hint",
        "emulation_shaders_pack_ready",
        "emulation_shaders_source_global",
        "emulation_shaders_source_game",
        "emulation_shaders_global_value",
        "emulation_shaders_help_scope",
        "emulation_shaders_picker_title",
        "emulation_shaders_search",
        "emulation_shaders_empty",
        "emulation_shaders_enable",
        "emulation_shaders_help_enable",
        "settings_game_menu_section_shaders"
    )

    @Test
    fun `required strings exist in every locale`() {
        val resDir = resolveResDir()
        val localeDirs = resDir
            .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty()
            .filter { dir -> File(dir, "strings.xml").isFile }
        assertTrue("Expected at least 18 locale string files", localeDirs.size >= 18)
        localeDirs.forEach { dir ->
            val content = File(dir, "strings.xml").readText()
            requiredKeys.forEach { key ->
                assertTrue("Missing $key in ${dir.name}/strings.xml", content.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun `shader format placeholders are preserved in every locale`() {
        val resDir = resolveResDir()
        val localeDirs = resDir
            .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty()
            .filter { dir -> File(dir, "strings.xml").isFile }
        val expectedPlaceholders = mapOf(
            "emulation_shaders_pack_ready" to "%1\$d",
            "emulation_shaders_global_value" to "%1\$s"
        )
        assertTrue("Expected at least 18 locale string files", localeDirs.size >= 18)
        localeDirs.forEach { dir ->
            val content = File(dir, "strings.xml").readText()
            expectedPlaceholders.forEach { (key, placeholder) ->
                val value = Regex(
                    "<string name=\"$key\">(.*?)</string>",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ).find(content)?.groupValues?.getOrNull(1)
                assertTrue("Missing $key in ${dir.name}/strings.xml", value != null)
                assertTrue(
                    "$key in ${dir.name}/strings.xml must keep the $placeholder placeholder",
                    value.orEmpty().contains(placeholder)
                )
            }
        }
    }

    private fun resolveResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File(System.getProperty("user.dir").orEmpty(), "src/main/res"),
            File(System.getProperty("user.dir").orEmpty(), "app/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate the Android res directory")
    }
}
