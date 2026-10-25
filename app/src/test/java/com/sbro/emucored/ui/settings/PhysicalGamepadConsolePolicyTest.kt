package com.sbro.emucored.ui.settings

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Assert.*
import org.junit.Test

class PhysicalGamepadConsolePolicyTest {
    @Test fun selectedWiiAttachmentChangesAvailableBindingsAndPhysicalLabels() {
        val actions = listOf("a", "x", "select", "start", "l", "r", "zl", "zr", "home", "left_stick_up", "right_stick_up")
        val classic = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic"))
        assertEquals(actions, actions.filter(classic::supportsAction))
        val remoteOverlay = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "remote")
        val remote = KeyboardMouseConsolePolicy(remoteOverlay)
        assertEquals(listOf("a", "x", "select", "start", "home", "right_stick_up"), actions.filter(remote::supportsAction))
        assertEquals("1", remoteOverlay.buttonLabel("x"))
        assertEquals("+", remoteOverlay.buttonLabel("start"))
        assertEquals("\u2212", remoteOverlay.buttonLabel("select"))
    }

    @Test fun dsKeepsSwapWhileTheSecondScreenPanelRemainsThreeDsOnly() {
        val shortcuts = listOf("quick_save", "quick_load", "swap_screens", "toggle_second_screen")
        val ds = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NDS))
        val threeDs = KeyboardMouseConsolePolicy(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N3DS))
        assertEquals(shortcuts - "toggle_second_screen", shortcuts.filter(ds::supportsAction))
        assertEquals(shortcuts, shortcuts.filter(threeDs::supportsAction))
        val gameCubeOverlay = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GAMECUBE)
        val gameCube = KeyboardMouseConsolePolicy(gameCubeOverlay)
        assertEquals(listOf("quick_save", "quick_load"), shortcuts.filter(gameCube::supportsAction))
        assertEquals("Z", gameCubeOverlay.buttonLabel("select"))
        assertFalse(gameCube.supportsAction("zl"))
        assertFalse(gameCube.supportsAction("zr"))
    }
}
