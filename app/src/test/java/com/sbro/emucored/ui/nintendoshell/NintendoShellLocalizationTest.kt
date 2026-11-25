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
        "nintendoui_saves_tab_game",
        "nintendoui_saves_tab_memory",
        "nintendoui_settings_subtitle",
        "nintendoui_empty_games",
        "nintendoui_empty_quicksaves",
        "nintendoui_empty_memsaves",
        "nintendoui_loading_games",
        "nintendoui_loading_saves",
        "nintendoui_launching_game",
        "nintendoui_launching_save",
        "nintendoui_action_load",
        "nintendoui_action_delete",
        "nintendoui_action_backup",
        "nintendoui_action_refresh",
        "nintendoui_action_open",
        "nintendoui_action_view",
        "nintendoui_action_reset",
        "nintendoui_theme_wii",
        "nintendoui_theme_gc",
        "nintendoui_theme_famicom",
        "nintendoui_theme_gb",
        "nintendoui_theme_switch",
        "nintendoui_aspect_auto",
        "nintendoui_motion_smooth",
        "nintendoui_motion_calm",
        "nintendoui_motion_eco",
        "nintendoui_motion_static",
        "nintendoui_scan_lcd",
        "nintendoui_scan_crt",
        "nintendoui_boot_full",
        "nintendoui_boot_fast",
        "nintendoui_setting_theme",
        "nintendoui_setting_aspect",
        "nintendoui_setting_motion",
        "nintendoui_setting_filter",
        "nintendoui_setting_boot",
        "nintendoui_setting_sfx",
        "nintendoui_setting_ambient",
        "nintendoui_setting_confirm_btn",
        "nintendoui_setting_touch",
        "nintendoui_setting_touch_guide",
        "nintendoui_setting_time_fmt",
        "nintendoui_setting_date_fmt",
        "nintendoui_setting_keep_screen",
        "nintendoui_setting_sysinfo",
        "nintendoui_setting_restore",
        "nintendoui_state_on",
        "nintendoui_state_off",
        "nintendoui_time_24h",
        "nintendoui_time_12h",
        "nintendoui_slot_empty",
        "nintendoui_filter_all",
        "nintendoui_hub_systems_title",
        "nintendoui_btn_start",
        "nintendoui_btn_continue",
        "nintendoui_btn_options",
        "nintendoui_hint_select",
        "nintendoui_hint_exit",
        "nintendoui_hint_tab",
        "nintendoui_hint_change",
        "nintendoui_yes",
        "nintendoui_no",
        "nintendoui_close",
        "nintendoui_got_it",
        "nintendoui_exit_title",
        "nintendoui_exit_msg",
        "nintendoui_del_save_title",
        "nintendoui_del_save_msg",
        "nintendoui_del_mem_title",
        "nintendoui_del_mem_msg",
        "nintendoui_restore_title",
        "nintendoui_restore_msg",
        "nintendoui_opt_start",
        "nintendoui_opt_continue",
        "nintendoui_opt_saves",
        "nintendoui_opt_info",
        "nintendoui_opt_settings",
        "nintendoui_info_title",
        "nintendoui_info_console",
        "nintendoui_info_serial",
        "nintendoui_info_size",
        "nintendoui_sys_app",
        "nintendoui_sys_device",
        "nintendoui_sys_storage",
        "nintendoui_touch_guide_title",
        "nintendoui_touch_tip_1",
        "nintendoui_touch_tip_2",
        "nintendoui_touch_tip_3",
        "nintendoui_touch_tip_4",
        "nintendoui_backup_saved",
        "nintendoui_backup_failed",
        "nintendoui_deleted"
    )

    private val expectedLocales = listOf(
        "values", "values-ar", "values-cs", "values-de", "values-es", "values-fa",
        "values-fr", "values-hi", "values-in", "values-it", "values-ja", "values-ko",
        "values-pl", "values-pt", "values-ru", "values-tr", "values-uk", "values-zh"
    )

    private val unescapedQuoteRegex = Regex("""(?<!\\)'""")

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
            val rawXml = xmlFile.readText(Charsets.UTF_8)
            requiredKeys.forEach { key ->
                val translated = values[key]
                assertTrue("Missing $key in $folder/strings.xml", !translated.isNullOrBlank())
                assertFalse("Cringy debug copy in $folder/$key", translated!!.contains("Native UI", ignoreCase = true))
                val match = Regex("""<string name="$key">(.*?)</string>""").find(rawXml)
                if (match != null) {
                    assertFalse(
                        "Unescaped apostrophe in $folder/$key: ${match.groupValues[1]}",
                        unescapedQuoteRegex.containsMatchIn(match.groupValues[1])
                    )
                }
            }
        }
    }

    private fun resolveResDir(): File {
        val direct = File("src/main/res")
        if (direct.isDirectory) return direct
        return File("app/src/main/res")
    }
}
