package com.sbro.emucored.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.OverlayControlLayout
import org.junit.Assert.*
import org.junit.Test

class ConsoleOverlayCanvasLayoutTest {
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
        width: Int = 960
    ) = buildOverlayCanvasLayout(
        canvasWidth = width.dp, canvasHeight = 540.dp, density = Density(1f), scaleFactor = 1f, stickScaleFactor = 1f,
        dpadOffset = 0f to 0f, lstickOffset = 0f to 0f, rstickOffset = 0f to 0f,
        actionOffset = 0f to 0f, lbtnOffset = 0f to 0f, rbtnOffset = 0f to 0f, centerOffset = 0f to 0f,
        controlLayouts = controls, safeLeftInset = 0.dp, safeRightInset = 0.dp, safeTopInset = 0.dp, safeBottomInset = 0.dp,
        previewMode = preview, consolePolicy = policy
    )
}
