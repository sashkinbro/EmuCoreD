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
        assertEquals(SettingsCategory.General, ConsoleSettingsPolicy.normalizeCategory(ConsolePlatform.GBA, SettingsCategory.Graphics))
    }

    @Test fun unavailableNativeAdaptersCannotExposeCoreConfigurationCategories() {
        ConsolePlatform.entries.filterNot { it.isLaunchable }.forEach { platform ->
            assertFalse(ConsoleSettingsPolicy.categories(platform).contains(SettingsCategory.Graphics))
            assertFalse(ConsoleSettingsPolicy.categories(platform).contains(SettingsCategory.Controls))
            assertFalse(ConsoleSettingsPolicy.categories(platform).contains(SettingsCategory.Emulation))
        }
    }
}
