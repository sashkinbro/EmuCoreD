package com.sbro.emucored.ui.emulation

internal data class EmulationViewportControls(val showOverlay: Boolean, val reservedBottomDp: Float)

internal fun emulationViewportControls(
    dsMode: Boolean,
    controlsVisible: Boolean,
    gamepadConnected: Boolean,
    hideOverlayOnGamepad: Boolean,
    tvUiEnabled: Boolean,
    touchPadMapped: Boolean,
    dsControlsHeightDp: Float
): EmulationViewportControls {
    val showOverlay = !tvUiEnabled && controlsVisible &&
        (touchPadMapped || !gamepadConnected || !hideOverlayOnGamepad)
    // Only DS places controls in a separate panel; 3DS controls overlay its full surface.
    return EmulationViewportControls(showOverlay, if (dsMode && showOverlay) dsControlsHeightDp else 0f)
}
