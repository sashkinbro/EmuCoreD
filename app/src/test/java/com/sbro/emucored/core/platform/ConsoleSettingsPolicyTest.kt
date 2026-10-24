package com.sbro.emucored.core.platform

import org.junit.Assert.*
import org.junit.Test

class ConsoleSettingsPolicyTest {
    @Test fun dsCategoriesIncludeItsGraphicsAndSharedControlsWithoutAzaharNetwork() {
        val tabs = ConsoleSettingsPolicy.categories(ConsolePlatform.NDS)
        assertTrue(tabs.containsAll(listOf(SettingsCategory.Graphics, SettingsCategory.Controls)))
        assertFalse(tabs.contains(SettingsCategory.Multiplayer))
        assertFalse(tabs.contains(SettingsCategory.Audio))
        assertEquals(SettingsCategory.General, ConsoleSettingsPolicy.normalizeCategory(ConsolePlatform.NDS, SettingsCategory.Multiplayer))
    }

    @Test fun changingConsoleRetainsSupportedCategoryAndNormalizesUnsupportedSelection() {
        assertEquals(SettingsCategory.Graphics, ConsoleSettingsPolicy.normalizeCategory(ConsolePlatform.NDS, SettingsCategory.Graphics))
        assertEquals(SettingsCategory.Multiplayer, ConsoleSettingsPolicy.normalizeCategory(ConsolePlatform.N3DS, SettingsCategory.Multiplayer))
        assertEquals(SettingsCategory.Graphics, ConsoleSettingsPolicy.normalizeCategory(ConsolePlatform.GBA, SettingsCategory.Graphics))
    }

    @Test fun everyIntegratedCoreHasItsOwnConfigurationWithoutAzaharMultiplayer() {
        ConsolePlatform.entries.forEach { platform ->
            val categories = ConsoleSettingsPolicy.categories(platform)
            assertTrue(platform.id, categories.contains(SettingsCategory.Graphics))
            assertTrue(platform.id, categories.contains(SettingsCategory.Controls))
            assertTrue(platform.id, categories.contains(SettingsCategory.Emulation))
            assertEquals(platform == ConsolePlatform.N3DS, categories.contains(SettingsCategory.Multiplayer))
            assertEquals(platform != ConsolePlatform.NDS, categories.contains(SettingsCategory.Audio))
        }
    }
}
