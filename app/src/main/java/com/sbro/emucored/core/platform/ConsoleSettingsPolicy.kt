package com.sbro.emucored.core.platform

enum class SettingsCategory {
    General, Graphics, Controls, Emulation, Audio, Multiplayer, Library, Customization, GameMenu, Updates, Pro, About
}

object ConsoleSettingsPolicy {
    private val shared = setOf(SettingsCategory.General, SettingsCategory.Library, SettingsCategory.Customization,
        SettingsCategory.Updates, SettingsCategory.Pro, SettingsCategory.About)

    fun categories(platform: ConsolePlatform): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
        when {
            category in shared -> true
            !platform.isLaunchable -> false
            category == SettingsCategory.Multiplayer -> platform == ConsolePlatform.N3DS
            category == SettingsCategory.Audio -> platform == ConsolePlatform.N3DS
            else -> true
        }
    }

    fun normalizeCategory(platform: ConsolePlatform, selected: SettingsCategory): SettingsCategory =
        selected.takeIf { it in categories(platform) } ?: SettingsCategory.General
}
