package com.sbro.emucored.data

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Assert.*
import org.junit.Test

class ConsoleControlsDefaultsTest {
    @Test fun everyVisibleDefaultControlIsSupportedByItsConsoleProfile() {
        ConsoleControlsScope.all.forEach { scope ->
            val policy = ConsoleOverlayPolicy.forPlatform(scope.platform, scope.normalizedWiiProfile)
            val visible = ConsoleControlsDefaults.layout(scope).controlLayouts
                .filterValues { it.visible }
                .keys
            val unsupported = visible.filterNot(policy::supportsControl)
            assertTrue("${scope.key} exposes unsupported controls: $unsupported", unsupported.isEmpty())
        }
    }

    @Test fun freshConsolesExposeTheirActualInputsIncludingEveryAnalogStick() {
        val basic = setOf("a", "b", "select", "start", "dpad_cluster")
        val expected = mapOf(
            ConsolePlatform.GB to basic, ConsolePlatform.GBC to basic, ConsolePlatform.NES to basic,
            ConsolePlatform.GBA to basic + setOf("l", "r"),
            ConsolePlatform.SNES to basic + setOf("x", "y", "l", "r"),
            ConsolePlatform.NDS to basic - "dpad_cluster" +
                setOf("x", "y", "l", "r", "dpad_up", "dpad_down", "dpad_left", "dpad_right"),
            ConsolePlatform.GAMECUBE to basic + setOf("x", "y", "l", "r", "left_stick", "right_stick"),
            ConsolePlatform.N3DS to basic +
                setOf("x", "y", "home", "touch", "l", "r", "zl", "zr", "left_stick", "right_stick")
        )
        expected.forEach { (platform, visible) ->
            val layout = ConsoleControlsDefaults.layout(ConsoleControlsScope(platform))
            assertEquals(platform.name, visible, layout.controlLayouts.filterValues { it.visible }.keys)
        }
    }

    @Test fun wiiProfilesHaveIndependentCompleteControllerDefaults() {
        val base = setOf("a", "b", "x", "y", "select", "start", "home", "dpad_cluster", "right_stick")
        for ((profile, extra) in mapOf("remote" to emptySet(), "nunchuk" to setOf("l", "r", "left_stick"),
            "classic" to setOf("l", "r", "zl", "zr", "left_stick"))) {
            val scope = ConsoleControlsScope(ConsolePlatform.WII, profile)
            assertEquals(base + extra, ConsoleControlsDefaults.layout(scope).controlLayouts.filterValues { it.visible }.keys)
        }
        assertEquals(3, listOf("remote", "nunchuk", "classic").map { ConsoleControlsScope(ConsolePlatform.WII, it).key }.toSet().size)
        assertEquals(ConsoleControlsScope(ConsolePlatform.WII).key, ConsoleControlsScope(ConsolePlatform.WII, "invalid").key)
    }

    @Test fun threeDsEnablesBothAnalogSticksAndDsHasNoAnalogControls() {
        val threeDs = ConsoleControlsDefaults.layout(ConsoleControlsScope(ConsolePlatform.N3DS))
        assertTrue(threeDs.controlLayouts.getValue("left_stick").visible)
        assertTrue(threeDs.controlLayouts.getValue("right_stick").visible)
        assertEquals(
            AppPreferences.defaultOverlayControlLayouts().keys,
            threeDs.controlLayouts.keys
        )
        val ds = ConsoleControlsDefaults.layout(ConsoleControlsScope(ConsolePlatform.NDS))
        assertFalse(ds.controlLayouts.getValue("left_stick").visible)
        assertFalse(ds.controlLayouts.getValue("right_stick").visible)
        assertTrue(ds.controlLayouts.getValue("dpad_up").visible)
        assertFalse(ds.controlLayouts.getValue("dpad_cluster").visible)
    }

    @Test fun unsetGlobalLayoutSelectsConsoleDefaultsInsteadOfHidingRequiredSticks() {
        // DataStore feeds an empty control map until the user saves a layout; this is the
        // shape a fresh install and an old untouched install both produce.
        val fresh = OverlayLayoutSnapshot(controlLayouts = emptyMap())
        ConsoleControlsScope.all.forEach { scope ->
            assertEquals(scope.key, ConsoleControlsDefaults.layout(scope),
                fresh.forConsoleControls(scope).toTouchControlsLayoutProfile())
        }
        for (scope in listOf(
            ConsoleControlsScope(ConsolePlatform.N3DS),
            ConsoleControlsScope(ConsolePlatform.GAMECUBE),
            ConsoleControlsScope(ConsolePlatform.WII, "remote"),
            ConsoleControlsScope(ConsolePlatform.WII, "nunchuk"),
            ConsoleControlsScope(ConsolePlatform.WII, "classic")
        )) {
            assertTrue(scope.key, fresh.forConsoleControls(scope).controlLayouts.getValue("right_stick").visible)
        }
    }

    @Test fun customizedLegacyLayoutAndCombosArePreservedExactlyWithoutMigrationOnRead() {
        val custom = TouchControlsLayoutProfile(dpadOffset = 19f to -23f, stickScale = 127,
            controlLayouts = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
                put("right_stick", OverlayControlLayout(visible = false, offset = 77f to 42f, surfaceOnly = true, widthScale = 210))
            })
        val combos = CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "combo", actionId = "a", secondaryActionId = "x")))
        ConsolePlatform.entries.forEach { platform ->
            val resolved = ConsoleControlsDefaults.resolve(ConsoleControlsScope(platform), custom, combos)
            assertSame(custom, resolved.layout)
            assertSame(combos, resolved.customControls)
        }
        assertFalse(custom.controlLayouts.getValue("right_stick").visible)
    }

    @Test fun consoleSaveResetAndPerGameOverrideDoNotLeakAcrossConsolesOrWiiProfiles() {
        val gc = ConsoleControlsScope(ConsolePlatform.GAMECUBE)
        val remote = ConsoleControlsScope(ConsolePlatform.WII, "remote")
        val nunchuk = ConsoleControlsScope(ConsolePlatform.WII, "nunchuk")
        val edited = ConsoleControlsProfile(TouchControlsLayoutProfile(stickScale = 150), CustomTouchControlLibrary.Empty)
        val stored = mapOf(gc.key to edited, remote.key to edited)
        assertSame(edited, ConsoleControlsDefaults.resolve(gc, saved = stored))
        assertTrue(ConsoleControlsDefaults.resolve(nunchuk, saved = stored).layout.controlLayouts.getValue("right_stick").visible)
        val game = TouchControlsLayoutProfile(actionOffset = -11f to 43f)
        assertSame(game, ConsoleControlsDefaults.resolve(gc, saved = stored, perGameLayout = game).layout)
        val reset = ConsoleControlsProfile(ConsoleControlsDefaults.layout(gc), CustomTouchControlLibrary.Empty)
        assertEquals(reset, ConsoleControlsDefaults.resolve(gc, legacyLayout = game, saved = stored + (gc.key to reset)))
        assertSame(edited, stored.getValue(gc.key))
    }

    @Test fun backupCodecRoundTripsAllScopesAndCustomControlFields() {
        val combo = CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "hold", actionId = "b", secondaryActionId = "l", widthDp = 88)))
        val stored = ConsoleControlsScope.all.associate { scope ->
            scope.key to ConsoleControlsProfile(ConsoleControlsDefaults.layout(scope), combo)
        }
        assertEquals(11, stored.size)
        assertEquals(stored, ConsoleControlsProfiles.decode(ConsoleControlsProfiles.encode(stored)))
        assertTrue(ConsoleControlsProfiles.decode("not json").isEmpty())
    }
}
