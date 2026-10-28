package com.sbro.emucored.ui.emulation

internal data class EmulationViewportControls(val showOverlay: Boolean, val reservedBottomDp: Float)

internal fun emulationViewportControls(
    dsMode: Boolean,
    controlsVisible: Boolean,
    gamepadConnected: Boolean,
    hideOverlayOnGamepad: Boolean,
    tvUiEnabled: Boolean,
    touchPadMapped: Boolean,
    dsControlsHeightDp: Float,
    isLandscape: Boolean = false
): EmulationViewportControls {
    val showOverlay = !tvUiEnabled && controlsVisible &&
        (touchPadMapped || !gamepadConnected || !hideOverlayOnGamepad)
    // In portrait DS uses a separate panel; landscape overlays the full surface.
    return EmulationViewportControls(showOverlay, if (dsMode && showOverlay && !isLandscape) dsControlsHeightDp else 0f)
}
