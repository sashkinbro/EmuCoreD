package com.sbro.emucored.ui.common

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.CustomTouchControl
import org.junit.Assert.*
import org.junit.Test

class ConsoleOverlayPolicyTest {
    @Test fun digitalConsolesExposeOnlyTheirOwnButtonsAndNoAnalogOrTouch() {
        val basic = setOf("a", "b", "select", "start", "dpad_up", "dpad_down", "dpad_left", "dpad_right")
        for (platform in listOf(ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES, ConsolePlatform.GBA, ConsolePlatform.SNES)) {
            val policy = ConsoleOverlayPolicy.forPlatform(platform)
            val expected = basic + when (platform) {
                ConsolePlatform.GBA -> setOf("l", "r")
                ConsolePlatform.SNES -> setOf("x", "y", "l", "r")
                else -> emptySet()
            }
            val candidates = basic + setOf("x", "y", "l", "r", "zl", "zr", "home", "touch", "swap_screen")
            assertEquals(platform.name, expected, candidates.filter(policy::supportsAction).toSet())
            assertFalse(policy.leftAnalog)
            assertFalse(policy.rightAnalog)
            assertFalse(policy.guestTouch)
        }
    }

    @Test fun gameCubeUsesSelectTransportForZAndDoesNotDuplicateTriggers() {
        val policy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GAMECUBE)
        assertEquals("Z", policy.buttonLabel("select"))
        assertEquals("select", policy.inputAlias("z"))
        assertTrue(policy.supportsAction("z"))
        assertFalse(policy.supportsControl("zl"))
        assertFalse(policy.supportsControl("zr"))
        assertFalse(policy.guestTouch)
        assertTrue(policy.leftAnalog && policy.rightAnalog)
    }

    @Test fun wiiProfilesHaveCorrectButtonsLabelsAndAnalogDevices() {
        val remote = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "remote")
        assertEquals("1", remote.buttonLabel("x"))
        assertEquals("2", remote.buttonLabel("y"))
        assertEquals("+", remote.buttonLabel("start"))
        assertEquals("−", remote.buttonLabel("select"))
        assertFalse(remote.leftAnalog)
        assertTrue(remote.rightAnalog)
        assertFalse(remote.supportsAction("l"))
        val nunchuk = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "nunchuk")
        assertEquals("C", nunchuk.buttonLabel("l"))
        assertEquals("Z", nunchuk.buttonLabel("r"))
        assertTrue(nunchuk.leftAnalog && nunchuk.rightAnalog)
        assertFalse(nunchuk.supportsAction("zl"))
        val classic = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic")
        assertNull(classic.buttonLabel("x"))
        assertTrue(classic.supportsAction("zl"))
        assertTrue(classic.supportsAction("zr"))
        assertFalse(classic.guestTouch)
        assertEquals(nunchuk, ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "invalid"))
    }

    @Test fun runtimeCustomButtonFilteringNeverMutatesStoredLayouts() {
        val original = listOf(CustomTouchControl(id = "valid", actionId = "a", secondaryActionId = "x", widthDp = 88),
            CustomTouchControl(id = "unsupported", actionId = "x"))
        val filtered = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GB).customControls(original)
        assertEquals(1, filtered.size)
        assertEquals("valid", filtered.single().id)
        assertEquals(88, filtered.single().widthDp)
        assertNull(filtered.single().secondaryActionId)
        assertEquals("x", original.first().secondaryActionId)
        assertEquals(2, original.size)
    }

    @Test fun dsAndThreeDsKeepEstablishedButtonsAndTouch() {
        val ds = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NDS)
        assertTrue(ds.guestTouch)
        assertFalse(ds.leftAnalog || ds.rightAnalog)
        assertTrue(ds.supportsAction("swap_screen"))
        assertFalse(ds.supportsAction("home"))
        val threeDs = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N3DS)
        assertTrue(threeDs.leftAnalog && threeDs.rightAnalog && threeDs.guestTouch)
        for (id in listOf("zl", "zr", "home", "touch", "left_input_toggle")) assertTrue(threeDs.supportsControl(id))
    }
}
