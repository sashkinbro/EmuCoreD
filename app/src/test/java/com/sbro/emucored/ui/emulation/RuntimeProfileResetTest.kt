package com.sbro.emucored.ui.emulation

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SettingsSnapshot
import org.junit.Assert.*
import org.junit.Test

class RuntimeProfileResetTest {
    @Test fun resetUsesTheSelectedConsolesGraphicsWithoutChangingSessionOrSlot() {
        val globals = SettingsSnapshot(renderer = 12, upscaleMultiplier = 5f, dsRenderer = 14, dsUpscaleMultiplier = 2f)
        val running = EmulationUiState(renderer = 13, upscale = 7f, isRunning = true,
            isPaused = true, showMenu = true, currentGameTitle = "Owned game", currentSlot = 4,
            activePlayTimeMs = 12345, gameSettingsProfileActive = true, perGameCoreOptions = mapOf("frame_skip" to "3"))
        for (platform in GamePlatform.entries) {
            val reset = running.withGlobalRuntimeDefaults(globals, platform, swapped = false)
            assertEquals(if (platform == GamePlatform.NDS) 14 else 12, reset.renderer)
            assertEquals(if (platform == GamePlatform.NDS) 2f else 5f, reset.upscale, 0f)
            assertFalse(reset.gameSettingsProfileActive)
            assertTrue(reset.perGameCoreOptions.isEmpty())
            assertTrue(reset.isRunning && reset.isPaused && reset.showMenu)
            assertEquals("Owned game", reset.currentGameTitle)
            assertEquals(4, reset.currentSlot)
            assertEquals(12345L, reset.activePlayTimeMs)
        }
    }

    @Test fun resetDropsBehaviorAndInputOverridesAsWellAsGraphics() {
        val globals = SettingsSnapshot(renderer = 14, showFps = false, frameSkip = 1, audioVolume = 65,
            audioMuted = false, racingMode = false, stickyButtons = setOf("a"),
            gamepadStickDeadzone = 12, gamepadBindingsByPad = mapOf(0 to mapOf("a" to 96)))
        val running = EmulationUiState(renderer = 14, showFps = true, frameSkip = 4, audioVolume = 0,
            audioMuted = true, racingMode = true, stickyButtons = setOf("b"),
            gamepadStickDeadzone = 30, gamepadBindingsByPad = mapOf(0 to mapOf("a" to 99)),
            autoSaveOnExit = true, autoLoadOnStart = true)
        val reset = running.withGlobalRuntimeDefaults(globals, GamePlatform.N3DS, swapped = true)
        assertFalse(reset.showFps)
        assertEquals(1, reset.frameSkip)
        assertEquals(65, reset.audioVolume)
        assertFalse(reset.audioMuted || reset.racingMode || reset.autoSaveOnExit || reset.autoLoadOnStart)
        assertEquals(setOf("a"), reset.stickyButtons)
        assertEquals(12, reset.gamepadStickDeadzone)
        assertEquals(mapOf(0 to mapOf("a" to 96)), reset.gamepadBindingsByPad)
        assertTrue(reset.swapScreens)
    }
}
