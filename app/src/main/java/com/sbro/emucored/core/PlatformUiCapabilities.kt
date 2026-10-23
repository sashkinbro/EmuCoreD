package com.sbro.emucored.core

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.GameMenuTabId
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreCapabilities
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Settings supported by the selected game's core, including before a session starts. */
class PlatformUiCapabilities private constructor(val platform: GamePlatform) {
    private val capabilities = CoreCapabilities.forPlatform(ConsolePlatform.fromGamePlatform(platform))
    val supportsAzaharOptions: Boolean get() = capabilities.supportsAzaharOptions
    val rendererIds: List<Int> get() = capabilities.rendererIds

    fun supportsGameMenuTab(tab: GameMenuTabId): Boolean =
        tab != GameMenuTabId.CHEATS || supportsAzaharOptions

    fun normalizeRenderer(renderer: Int): Int = renderer.takeIf { it in rendererIds } ?: 14
    fun maxUpscaleMultiplier(threeDsLimit: Int): Int = if (supportsAzaharOptions) threeDsLimit else 8

    companion object {
        fun forGame(path: String?, cachedPlatform: GamePlatform? = null): PlatformUiCapabilities =
            PlatformUiCapabilities(
                path?.let {
                    val decoded = runCatching {
                        URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name())
                    }.getOrDefault(it)
                    GamePlatform.fromFileName(decoded)
                } ?: cachedPlatform ?: GamePlatform.N3DS
            )
    }
}
