// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.nintendoshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NintendoShellLocalizationTest {

    private val requiredKeys = listOf(
        "nintendoui_tab_recent",
        "nintendoui_action_back",
        "nintendoui_menu_games",
        "nintendoui_menu_saves",
        "nintendoui_menu_settings",
        "nintendoui_unit_consoles",
        "nintendoui_saves_tab_quick",
        "nintendoui_saves_tab_memory",
        "nintendoui_settings_subtitle",
        "nintendoui_empty_games",
        "nintendoui_empty_quicksaves",
        "nintendoui_empty_memsaves",
        "nintendoui_action_load",
        "nintendoui_action_delete",
        "nintendoui_action_backup",
        "nintendoui_theme_wii",
        "nintendoui_theme_gc",
        "nintendoui_theme_famicom",
        "nintendoui_theme_gb",
        "nintendoui_theme_switch",
        "nintendoui_aspect_auto",
        "nintendoui_setting_theme",
        "nintendoui_setting_aspect",
        "nintendoui_setting_eco",
        "nintendoui_state_on",
        "nintendoui_state_off",
        "nintendoui_setting_sfx",
        "nintendoui_setting_standard_ui",
        "nintendoui_action_open",
        "nintendoui_confirm_delete",
        "nintendoui_backup_saved",
        "nintendoui_backup_failed",
        "nintendoui_deleted"
    )

    private val expectedLocales = listOf(
        "values", "values-ar", "values-cs", "values-de", "values-es", "values-fa",
        "values-fr", "values-hi", "values-in", "values-it", "values-ja", "values-ko",
        "values-pl", "values-pt", "values-ru", "values-tr", "values-uk", "values-zh"
    )

    @Test
    fun all18LocalesContainValidNintendoShellTranslations() {
        val resDir = resolveResDir()
        assertTrue("Missing res dir at ${resDir.absolutePath}", resDir.isDirectory)

        val factory = DocumentBuilderFactory.newInstance()
        expectedLocales.forEach { folder ->
            val xmlFile = File(resDir, "$folder/strings.xml")
            assertTrue("Missing $folder/strings.xml", xmlFile.isFile)
            val doc = factory.newDocumentBuilder().parse(xmlFile)
            val nodes = doc.getElementsByTagName("string")
            val values = HashMap<String, String>()
            for (i in 0 until nodes.length) {
                val el = nodes.item(i)
                val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
                values[name] = el.textContent?.trim().orEmpty()
            }
            requiredKeys.forEach { key ->
                val translated = values[key]
                assertTrue("Missing $key in $folder/strings.xml", !translated.isNullOrBlank())
                assertFalse("Cringy debug copy in $folder/$key", translated!!.contains("Native UI", ignoreCase = true))
            }
        }
    }

    private fun resolveResDir(): File {
        val direct = File("src/main/res")
        if (direct.isDirectory) return direct
        return File("app/src/main/res")
    }
}
