package com.sbro.emucored.core.platform

/** Implemented frontend features, not a promise of everything an upstream engine can do. */
class CoreCapabilities private constructor(
    val rendererIds: List<Int>,
    val defaultRenderer: Int?,
    val supportsAzaharOptions: Boolean,
    val supportsAnalogControls: Boolean,
    val screenCount: Int,
    private val internalScaleLimits: Map<Int, Int>,
    private val mappedActions: Set<String> = dsActions
) {
    val supportsScreenSwap: Boolean get() = screenCount == 2

    fun maxInternalScale(rendererId: Int): Int = internalScaleLimits[rendererId] ?: 1

    fun supportsMappedAction(action: String): Boolean = when {
        supportsAzaharOptions -> true
        else -> action in mappedActions
    }

    companion object {
        private val dsActions = setOf("a", "b", "x", "y", "l", "r", "start", "select",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "quick_save", "quick_load",
            "swap_screens", "toggle_second_screen")
        private val azahar = CoreCapabilities(listOf(12, 14), 14, true, true, 2, mapOf(12 to 10, 14 to 10))
        private val melonDs = CoreCapabilities(listOf(14, 13), 14, false, false, 2, mapOf(14 to 8, 13 to 1))
        private val basicActions = setOf("a", "b", "start", "select", "dpad_up", "dpad_down", "dpad_left", "dpad_right", "quick_save", "quick_load")
        private val analogActions = setOf("left_stick_up", "left_stick_down", "left_stick_left", "left_stick_right",
            "right_stick_up", "right_stick_down", "right_stick_left", "right_stick_right")
        private val gameCube = CoreCapabilities(listOf(14, 12), 14, false, true, 1, mapOf(14 to 4, 12 to 4),
            basicActions + analogActions + setOf("x", "y", "l", "r", "zl", "zr", "z"))
        private val wii = CoreCapabilities(listOf(14, 12), 14, false, true, 1, mapOf(14 to 4, 12 to 4),
            basicActions + analogActions + setOf("x", "y", "l", "r", "zl", "zr", "z", "home", "plus", "minus", "one", "two"))
        private val gba = CoreCapabilities(listOf(13), 13, false, false, 1, emptyMap(), basicActions + setOf("l", "r"))
        private val gameBoy = CoreCapabilities(listOf(13), 13, false, false, 1, emptyMap(), basicActions)
        private val snes = CoreCapabilities(listOf(13), 13, false, false, 1, emptyMap(), basicActions + setOf("x", "y", "l", "r"))

        fun forPlatform(platform: ConsolePlatform): CoreCapabilities = when (platform) {
            ConsolePlatform.N3DS -> azahar
            ConsolePlatform.NDS -> melonDs
            ConsolePlatform.GAMECUBE -> gameCube
            ConsolePlatform.WII -> wii
            ConsolePlatform.GBA -> gba
            ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES -> gameBoy
            ConsolePlatform.SNES -> snes
        }
    }
}
