package com.sbro.emucored.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.OverlayControlLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsOverlayCanvasLayoutTest {
    @Test
    fun `DS fit keeps every target clear of asymmetric cutouts and navigation bars`() {
        for (width in listOf(320f, 428f, 800f)) {
            val buttons = buildLayout(width, safeLeft = 16f, safeTop = 24f, safeBottom = 24f)
                .allButtons.filter { it.visible }
            for (button in buttons) {
                assertTrue("${button.id} cutout", button.x.value >= 16f - EPSILON)
                assertTrue("${button.id} right safe edge", (button.x + button.width).value <= width - 16f + EPSILON)
                assertTrue("${button.id} top safe edge", button.y.value >= 24f - EPSILON)
                assertTrue("${button.id} navigation", (button.y + button.height).value <= 236f + EPSILON)
            }
        }
    }

    @Test
    fun `default DS controls use the free panel for comfortable targets`() {
        val layout = buildLayout(428f)
        for (button in layout.dpadButtons + layout.actionButtons) {
            assertTrue("${button.id} width ${button.width}", button.width.value >= 48f)
            assertTrue("${button.id} height ${button.height}", button.height.value >= 48f)
        }
        assertTrue(layout.leftShoulders.single().height.value >= 36f)
        assertTrue(layout.centerButtons.all { it.height.value >= 32f })
    }

    @Test
    fun `DS controls fit narrow and wide panels without overlapping`() {
        for (width in listOf(320f, 428f, 800f)) {
            for (scale in listOf(0.8f, 1f, 1.4f)) {
                val height = 260f * scale
                val buttons = buildLayout(width, scale).allButtons.filter { it.visible }
                for (button in buttons) {
                    assertTrue("${button.id} left", button.x.value >= -EPSILON)
                    assertTrue("${button.id} top", button.y.value >= -EPSILON)
                    assertTrue("${button.id} right", (button.x + button.width).value <= width + EPSILON)
                    assertTrue("${button.id} bottom", (button.y + button.height).value <= height + EPSILON)
                }
                for ((index, first) in buttons.withIndex()) {
                    for (second in buttons.drop(index + 1)) {
                        val overlapWidth = minOf((first.x + first.width).value, (second.x + second.width).value) - maxOf(first.x.value, second.x.value)
                        val overlapHeight = minOf((first.y + first.height).value, (second.y + second.height).value) - maxOf(first.y.value, second.y.value)
                        assertTrue("${first.id} overlaps ${second.id} at $width scale $scale", overlapWidth <= EPSILON || overlapHeight <= EPSILON)
                    }
                }
            }
        }
    }

    @Test
    fun `DS honors user scale and per-control geometry without changing the profile`() {
        val defaults = AppPreferences.defaultOverlayControlLayouts()
        val custom = defaults + ("a" to defaults.getValue("a").copy(scale = 120, offset = -12f to 9f))
        val snapshot = custom.toMap()
        val baseline = requireNotNull(buildLayout(428f).button("a"))
        val modified = requireNotNull(buildLayout(428f, controls = custom).button("a"))
        assertEquals(baseline.width.value * 1.2f, modified.width.value, EPSILON)
        assertEquals(baseline.x.value - 4f, modified.x.value, EPSILON)
        assertEquals(baseline.y.value + 3f, modified.y.value, EPSILON)
        assertEquals(snapshot, custom)
        val scaled = requireNotNull(buildLayout(800f, scale = 1.4f).button("a"))
        assertEquals(baseline.width.value * 1.4f, scaled.width.value, EPSILON)
    }

    private fun buildLayout(
        width: Float,
        scale: Float = 1f,
        controls: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
        safeLeft: Float = 0f,
        safeTop: Float = 0f,
        safeBottom: Float = 0f
    ) = buildOverlayCanvasLayout(
        canvasWidth = width.dp, canvasHeight = (260f * scale).dp,
        density = Density(3f), scaleFactor = scale, stickScaleFactor = 1f,
        dpadOffset = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
        lstickOffset = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
        rstickOffset = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
        actionOffset = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
        lbtnOffset = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
        rbtnOffset = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
        centerOffset = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
        controlLayouts = controls,
        safeLeftInset = safeLeft.dp, safeRightInset = 0.dp,
        safeTopInset = safeTop.dp, safeBottomInset = safeBottom.dp,
        dsMode = true
    )

    private companion object { const val EPSILON = 0.01f }
}
