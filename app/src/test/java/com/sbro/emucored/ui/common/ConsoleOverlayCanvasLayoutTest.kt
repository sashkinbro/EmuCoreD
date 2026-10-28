package com.sbro.emucored.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.ConsoleControlsDefaults
import com.sbro.emucored.data.ConsoleControlsScope
import com.sbro.emucored.data.OverlayControlLayout
import com.sbro.emucored.data.OverlayLayoutSnapshot
import com.sbro.emucored.data.forConsoleControls
import org.junit.Assert.*
import org.junit.Test

class ConsoleOverlayCanvasLayoutTest {
    @Test fun n64CButtonsIgnoreSavedAnalogSurfaceWidth() {
        val policy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N64)
        val defaults = ConsoleControlsDefaults.layout(ConsoleControlsScope(ConsolePlatform.N64)).controlLayouts
        val baseline = layout(policy, defaults).rightStick!!
        val staleAnalogSettings = defaults + ("right_stick" to defaults.getValue("right_stick")
            .copy(surfaceOnly = true, widthScale = 240))
        val actual = layout(policy, staleAnalogSettings).rightStick!!
        assertEquals(baseline.x.value, actual.x.value, 0.01f)
        assertEquals(baseline.y.value, actual.y.value, 0.01f)
        assertEquals(baseline.size.value, actual.size.value, 0.01f)
    }
    @Test fun n64FactoryControlsFitWithoutOverlappingInPortraitAndLandscape() {
        val policy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N64)
        val controls = ConsoleControlsDefaults.layout(ConsoleControlsScope(ConsolePlatform.N64)).controlLayouts
        for ((width, height) in listOf(360 to 800, 800 to 360, 1200 to 800, 800 to 1200)) {
            val canvas = layout(policy, controls, width = width, height = height)
            data class Rect(val name: String, val x: Float, val y: Float, val w: Float, val h: Float)
            val rects = canvas.allButtons.filter { it.visible }.map {
                Rect(it.id, it.x.value, it.y.value, it.width.value, it.height.value)
            }.toMutableList()
            canvas.leftStick?.let { rects += Rect(it.id, it.x.value, it.y.value, it.size.value, it.size.value) }
            canvas.rightStick?.let { rects += Rect("C", it.x.value, it.y.value, it.size.value, it.size.value) }
            canvas.dpadCluster?.let { rects += Rect(it.id, it.x.value, it.y.value, it.size.value, it.size.value) }
            for (a in rects) {
                assertTrue("$width x $height ${a.name} outside canvas: $a",
                    a.x >= -0.01f && a.y >= -0.01f && a.x + a.w <= width + 0.01f && a.y + a.h <= height + 0.01f)
                for (b in rects.drop(rects.indexOf(a) + 1)) {
                    val overlapX = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                    val overlapY = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    assertFalse("$width x $height overlap: ${a.name} and ${b.name}", overlapX > 0.01f && overlapY > 0.01f)
                }
            }
        }
    }
    @Test fun factoryLayoutsRenderBothSticksOnEveryAnalogConsole() {
        for (scope in ConsoleControlsScope.all) {
            val policy = ConsoleOverlayPolicy.forPlatform(scope.platform, scope.wiiProfile)
            val layout = layout(policy, ConsoleControlsDefaults.layout(scope).controlLayouts)
            assertEquals("${scope.key} left stick", policy.leftAnalog, layout.leftStick != null)
            assertEquals("${scope.key} right stick", policy.rightAnalog, layout.rightStick != null)
        }
    }

    @Test fun freshThreeDsSessionRendersRightStickThroughScopeResolution() {
        val scope = ConsoleControlsScope(ConsolePlatform.N3DS)
        val resolved = OverlayLayoutSnapshot(controlLayouts = emptyMap()).forConsoleControls(scope)
        val layout = layout(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N3DS), resolved.controlLayouts)
        assertTrue(layout.rightStick?.visible == true)
    }

    @Test fun unsupportedControlsStayAbsentEvenWhenGlobalProfileAndEditorRequestThem() {
        val controls = AppPreferences.defaultOverlayControlLayouts().mapValues { (_, value) -> value.copy(visible = true) }
        val stored = controls.toMap()
        for (platform in ConsolePlatform.entries) for (preview in listOf(false, true)) for (width in listOf(428, 960)) {
            val policy = ConsoleOverlayPolicy.forPlatform(platform)
            val layout = layout(policy, controls, preview, width)
            assertTrue("$platform", layout.allButtons.all { policy.supportsControl(it.id) })
            if (!policy.leftAnalog) assertNull(layout.leftStick)
            if (!policy.rightAnalog) assertNull(layout.rightStick)
        }
        assertEquals(stored, controls)
    }

    @Test fun twoButtonConsolesNeverRenderDeadXyShouldersOrTouchControls() {
        for (platform in listOf(ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES)) {
            val layout = layout(ConsoleOverlayPolicy.forPlatform(platform))
            assertEquals(setOf("a", "b"), layout.actionButtons.map { it.id }.toSet())
            assertTrue(layout.leftShoulders.isEmpty())
            assertTrue(layout.rightShoulders.isEmpty())
            assertEquals(setOf("select", "start"), layout.centerButtons.map { it.id }.toSet())
            assertNotNull(layout.dpadCluster)
        }
    }

    @Test fun wiiRemoteCannotExposeNunchukButtonsThroughGlobalThreeDsSettings() {
        val controls = AppPreferences.defaultOverlayControlLayouts().mapValues { (_, value) -> value.copy(visible = true) }
        val remote = layout(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "remote"), controls, true)
        assertTrue(remote.leftShoulders.isEmpty() && remote.rightShoulders.isEmpty())
        assertNull(remote.leftStick)
        assertNotNull(remote.rightStick)
        val classic = layout(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic"), controls, true)
        assertEquals(setOf("l", "zl"), classic.leftShoulders.map { it.id }.toSet())
        assertNotNull(classic.leftStick)
    }

    @Test fun supportedUserControlGeometryAndHiddenSticksSurviveConsoleProjection() {
        val defaults = AppPreferences.defaultOverlayControlLayouts()
        val controls = defaults + ("a" to defaults.getValue("a").copy(scale = 135, offset = -9f to 6f)) +
            ("right_stick" to defaults.getValue("right_stick").copy(visible = false))
        val stored = controls.toMap()
        val policy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GAMECUBE)
        val baseline = layout(policy).button("a")!!
        val custom = layout(policy, controls)
        assertEquals(baseline.width.value * 1.35f, custom.button("a")!!.width.value, 0.01f)
        assertEquals(baseline.x.value - 9f, custom.button("a")!!.x.value, 0.01f)
        assertNull(custom.rightStick)
        assertEquals(stored, controls)
    }

    private fun layout(
        policy: ConsoleOverlayPolicy,
        controls: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
        preview: Boolean = false,
        width: Int = 960,
        height: Int = 540
    ) = buildOverlayCanvasLayout(
        canvasWidth = width.dp, canvasHeight = height.dp, density = Density(1f), scaleFactor = 1f, stickScaleFactor = 1f,
        dpadOffset = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
        lstickOffset = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        rstickOffset = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        actionOffset = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
        lbtnOffset = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        rbtnOffset = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        centerOffset = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
        controlLayouts = controls, safeLeftInset = 0.dp, safeRightInset = 0.dp, safeTopInset = 0.dp, safeBottomInset = 0.dp,
        previewMode = preview, consolePolicy = policy
    )
}
