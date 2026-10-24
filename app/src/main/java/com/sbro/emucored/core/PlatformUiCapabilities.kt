package com.sbro.emucored.core

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.GameMenuTabId
import com.sbro.emucored.data.GameMenuSectionId
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreCapabilities
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.core.platform.SettingsCategory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Settings supported by the selected game's core, including before a session starts. */
class PlatformUiCapabilities private constructor(val platform: GamePlatform) {
    private val capabilities = CoreCapabilities.forPlatform(ConsolePlatform.fromGamePlatform(platform))
    val supportsAzaharOptions: Boolean get() = capabilities.supportsAzaharOptions
    val supportsAnalogControls: Boolean get() = capabilities.supportsAnalogControls
    val rendererIds: List<Int> get() = capabilities.rendererIds
    val screenCount: Int get() = capabilities.screenCount
    val supportsScreenSwap: Boolean get() = capabilities.supportsScreenSwap
    val supportsAudioOptions: Boolean get() = platform != GamePlatform.NDS

    fun supportsGameMenuTab(tab: GameMenuTabId): Boolean =
        tab != GameMenuTabId.CHEATS || supportsAzaharOptions

    fun supportsGameMenuSection(section: GameMenuSectionId): Boolean =
        supportsGameMenuTab(section.tab) && when (section) {
            GameMenuSectionId.EMULATION_CPU -> supportsAzaharOptions || CoreOptionSchema.options(platform.console).any { it.category == SettingsCategory.Emulation }
            GameMenuSectionId.EMULATION_AUDIO -> supportsAudioOptions
            else -> true
        }

    fun normalizeRenderer(renderer: Int): Int = renderer.takeIf { it in rendererIds } ?: capabilities.defaultRenderer ?: 14
    fun supportsInternalUpscaling(renderer: Int): Boolean = capabilities.maxInternalScale(renderer) > 1
    fun maxUpscaleMultiplier(threeDsLimit: Int): Int = if (supportsAzaharOptions) threeDsLimit
        else rendererIds.maxOfOrNull(capabilities::maxInternalScale) ?: 1

    companion object {
        fun forGame(path: String?, cachedPlatform: GamePlatform? = null): PlatformUiCapabilities =
            PlatformUiCapabilities(
                cachedPlatform ?: path?.let {
                    val decoded = runCatching {
                        URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name())
                    }.getOrDefault(it)
                    GamePlatform.forPath(decoded) ?: GamePlatform.forPath(it)
                } ?: GamePlatform.N3DS
            )
    }
}
