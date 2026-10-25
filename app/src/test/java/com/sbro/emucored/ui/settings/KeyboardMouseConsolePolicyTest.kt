package com.sbro.emucored.ui.settings

import com.sbro.emucored.core.KeyboardMouseBindings
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Assert.*
import org.junit.Test

class KeyboardMouseConsolePolicyTest {
    @Test fun nesHasDigitalButtonsAndSaveActionsWithoutAnalogOrThreeDsShortcuts() {
        val policy = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NES))
        for (id in listOf("a", "b", "start", "select", "dpad_up", "quick_save", "quick_load")) assertTrue(id, policy.supportsAction(id))
        for (id in listOf("x", "zl", "home", "touch", "left_stick_up", "right_stick_right", "swap_screens", "toggle_second_screen", "fast_forward")) assertFalse(id, policy.supportsAction(id))
        assertEquals(listOf(0, 1), (0..5).filter(policy::supportsWheel))
        assertEquals(listOf(0), (0..2).filter(policy::supportsAim))
    }

    @Test fun wiiAttachmentsExposeOnlyTheirActualAxesAndButtons() {
        val remote = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "remote"))
        val nunchuk = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "nunchuk"))
        val classic = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic"))
        assertTrue(remote.supportsAction("right_stick_up")) // IR pointer
        assertFalse(remote.supportsAction("left_stick_up"))
        assertFalse(remote.supportsWheel(KeyboardMouseBindings.MOUSE_WHEEL_SHOULDERS))
        assertTrue(nunchuk.supportsWheel(KeyboardMouseBindings.MOUSE_WHEEL_SHOULDERS))
        assertFalse(nunchuk.supportsWheel(KeyboardMouseBindings.MOUSE_WHEEL_TRIGGERS))
        assertTrue(classic.supportsWheel(KeyboardMouseBindings.MOUSE_WHEEL_TRIGGERS))
        assertTrue(classic.supportsAction("home"))
    }

    @Test fun allNineConsoleFiltersAreReadOnlyAndTwoScreenShortcutsMatchImplementedLayouts() {
        for (platform in ConsolePlatform.entries) {
            val policy = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(platform))
            assertTrue(policy.supportsAction("quick_save"))
            assertEquals(platform in setOf(ConsolePlatform.NDS, ConsolePlatform.N3DS), policy.supportsAction("swap_screens"))
            assertEquals(platform == ConsolePlatform.N3DS, policy.supportsAction("toggle_second_screen"))
        }
        val saved = mapOf("x" to 42, "right_stick_up" to 43, "a" to 44)
        val unchanged = saved.toMap()
        val nes = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NES))
        assertEquals(setOf("a"), saved.keys.filter(nes::supportsAction).toSet())
        assertEquals(unchanged, saved)
    }
}
