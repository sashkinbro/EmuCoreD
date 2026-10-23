package com.sbro.emucored.ui.emulation

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SettingsSnapshot
import com.sbro.emucored.data.forPlatform

/** Replace game-owned values without replacing the live session, menu or save-slot state. */
internal fun EmulationUiState.withGlobalRuntimeDefaults(
    globals: SettingsSnapshot,
    platform: GamePlatform,
    swapped: Boolean
): EmulationUiState {
    val settings = globals.forPlatform(platform)
    return copy(
        renderer = settings.renderer,
        upscale = settings.upscaleMultiplier,
        swapScreens = swapped,
        aspectRatio = settings.aspectRatio,
        displayCrop = settings.displayCrop,
        showFps = settings.showFps,
        fpsOverlayMode = settings.fpsOverlayMode,
        confirmSaveLoadActions = settings.confirmSaveLoadActions,
        backButtonExitsGame = settings.backButtonExitsGame,
        frameSkip = settings.frameSkip,
        frameLimitEnabled = settings.frameLimitEnabled,
        fastForwardSpeed = settings.fastForwardSpeed,
        targetFps = settings.targetFps,
        enableCheats = settings.enableCheats,
        audioVolume = settings.audioVolume,
        audioMuted = settings.audioMuted,
        racingMode = settings.racingMode,
        stickyButtons = settings.stickyButtons,
        touchscreenLeftStick = settings.touchscreenLeftStick,
        touchscreenLeftStickSensitivity = settings.touchscreenLeftStickSensitivity,
        touchscreenRightStick = settings.touchscreenRightStick,
        touchscreenRightStickSensitivity = settings.touchscreenRightStickSensitivity,
        touchHaptics = settings.touchHaptics,
        touchHapticsPreset = settings.touchHapticsPreset,
        touchHapticsStrength = settings.touchHapticsStrength,
        touchControlVisualStyle = settings.touchControlVisualStyle,
        touchControlPressEffect = settings.touchControlPressEffect,
        customTouchControls = settings.customTouchControls,
        gyroMode = settings.gyroMode,
        gyroSensitivity = settings.gyroSensitivity,
        gyroSmoothing = settings.gyroSmoothing,
        gyroInvertX = settings.gyroInvertX,
        gyroInvertY = settings.gyroInvertY,
        gamepadRightStickUpToR2 = settings.gamepadRightStickUpToR2,
        gamepadRightStickDownToL2 = settings.gamepadRightStickDownToL2,
        gamepadButtonHaptics = settings.gamepadButtonHaptics,
        gamepadStickDeadzone = settings.gamepadStickDeadzone,
        gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
        gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity,
        gamepadBindingsByPad = settings.gamepadBindingsByPad,
        autoSaveOnExit = false,
        autoLoadOnStart = false,
        gameSettingsProfileActive = false,
        perGameCoreOptions = emptyMap()
    )
}
