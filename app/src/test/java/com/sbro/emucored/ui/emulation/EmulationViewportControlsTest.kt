package com.sbro.emucored.ui.emulation

import org.junit.Assert.*
import org.junit.Test

class EmulationViewportControlsTest {
    @Test fun gamepadConnectionReleasesTheDsPanelAndDisconnectRestoresIt() {
        val touch = layout(ds = true)
        val gamepad = layout(ds = true, connected = true)
        val disconnected = layout(ds = true)
        assertTrue(touch.showOverlay)
        assertEquals(260f, touch.reservedBottomDp, 0f)
        assertFalse(gamepad.showOverlay)
        assertEquals(0f, gamepad.reservedBottomDp, 0f)
        assertEquals(touch, disconnected)
    }

    @Test fun explicitControlVisibilityAndAutoHidePreferenceRestoreTheDsPanel() {
        assertEquals(0f, layout(ds = true, visible = false).reservedBottomDp, 0f)
        assertEquals(260f, layout(ds = true, connected = true, autoHide = false).reservedBottomDp, 0f)
        assertEquals(260f, layout(ds = true, mappedTouchPad = true, connected = true).reservedBottomDp, 0f)
        assertEquals(0f, layout(ds = true, tv = true).reservedBottomDp, 0f)
    }

    @Test fun threeDsKeepsItsFullSurfaceForEachControlVisibilityMode() {
        for (connected in listOf(false, true)) {
            for (visible in listOf(false, true)) {
                assertEquals(0f, layout(ds = false, connected = connected, visible = visible).reservedBottomDp, 0f)
            }
        }
        assertTrue(layout(ds = false).showOverlay)
        assertFalse(layout(ds = false, connected = true).showOverlay)
    }

    private fun layout(
        ds: Boolean,
        connected: Boolean = false,
        visible: Boolean = true,
        autoHide: Boolean = true,
        tv: Boolean = false,
        mappedTouchPad: Boolean = false
    ) = emulationViewportControls(
        dsMode = ds, controlsVisible = visible, gamepadConnected = connected,
        hideOverlayOnGamepad = autoHide, tvUiEnabled = tv,
        touchPadMapped = mappedTouchPad, dsControlsHeightDp = 260f
    )
}
