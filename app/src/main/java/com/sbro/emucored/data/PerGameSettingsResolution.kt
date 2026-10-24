package com.sbro.emucored.data

internal fun PerGameSettings.resolveAgainst(defaultProfile: PerGameSettings): PerGameSettings {
    val keys = providedKeys ?: return copy(providedKeys = null)
    fun <T> pick(key: String, current: T, fallback: T): T = if (key in keys) current else fallback
    return defaultProfile.copy(
        gameKey = gameKey,
        gameTitle = gameTitle,
        gameSerial = gameSerial,
        gamePlatform = gamePlatform ?: defaultProfile.gamePlatform,
        keyboardMouse = pick("keyboardMouse", keyboardMouse, defaultProfile.keyboardMouse),
        renderer = pick("renderer", renderer, defaultProfile.renderer),
        mediatekAngleOpenGl = pick("mediatekAngleOpenGl", mediatekAngleOpenGl, defaultProfile.mediatekAngleOpenGl),
        upscaleMultiplier = pick("upscaleMultiplier", upscaleMultiplier, defaultProfile.upscaleMultiplier),
        aspectRatio = pick("aspectRatio", aspectRatio, defaultProfile.aspectRatio),
        swapScreens = pick("swapScreens", swapScreens, defaultProfile.swapScreens),
        coreOptions = pick("coreOptions", coreOptions, defaultProfile.coreOptions),
        displayCrop = pick("displayCrop", displayCrop, defaultProfile.displayCrop),
        showFps = pick("showFps", showFps, defaultProfile.showFps),
        fpsOverlayMode = pick("fpsOverlayMode", fpsOverlayMode, defaultProfile.fpsOverlayMode),
        racingMode = pick("racingMode", racingMode, defaultProfile.racingMode),
        stickyButtons = pick("stickyButtons", stickyButtons, defaultProfile.stickyButtons),
        touchscreenLeftStick = pick("touchscreenLeftStick", touchscreenLeftStick, defaultProfile.touchscreenLeftStick),
        touchscreenLeftStickSensitivity = pick(
            "touchscreenLeftStickSensitivity",
            touchscreenLeftStickSensitivity,
            defaultProfile.touchscreenLeftStickSensitivity
        ),
        touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick, defaultProfile.touchscreenRightStick),
        touchscreenRightStickSensitivity = pick(
            "touchscreenRightStickSensitivity",
            touchscreenRightStickSensitivity,
            defaultProfile.touchscreenRightStickSensitivity
        ),
        touchHaptics = pick("touchHaptics", touchHaptics, defaultProfile.touchHaptics),
        touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset, defaultProfile.touchHapticsPreset),
        gyroMode = pick("gyroMode", gyroMode, defaultProfile.gyroMode),
        gyroSensitivity = pick("gyroSensitivity", gyroSensitivity, defaultProfile.gyroSensitivity),
        gyroSmoothing = pick("gyroSmoothing", gyroSmoothing, defaultProfile.gyroSmoothing),
        gyroInvertX = pick("gyroInvertX", gyroInvertX, defaultProfile.gyroInvertX),
        gyroInvertY = pick("gyroInvertY", gyroInvertY, defaultProfile.gyroInvertY),
        gamepadRightStickUpToR2 = pick("gamepadRightStickUpToR2", gamepadRightStickUpToR2, defaultProfile.gamepadRightStickUpToR2),
        gamepadRightStickDownToL2 = pick("gamepadRightStickDownToL2", gamepadRightStickDownToL2, defaultProfile.gamepadRightStickDownToL2),
        gamepadButtonHaptics = pick("gamepadButtonHaptics", gamepadButtonHaptics, defaultProfile.gamepadButtonHaptics),
        gamepadStickDeadzone = pick("gamepadStickDeadzone", gamepadStickDeadzone, defaultProfile.gamepadStickDeadzone),
        gamepadLeftStickSensitivity = pick(
            "gamepadLeftStickSensitivity",
            gamepadLeftStickSensitivity,
            defaultProfile.gamepadLeftStickSensitivity
        ),
        gamepadRightStickSensitivity = pick(
            "gamepadRightStickSensitivity",
            gamepadRightStickSensitivity,
            defaultProfile.gamepadRightStickSensitivity
        ),
        gamepadBindingsByPad = if ("gamepadBindingsByPad" in keys) {
            gamepadBindingsByPad
        } else {
            defaultProfile.gamepadBindingsByPad
        },
        autoSaveOnExit = pick("autoSaveOnExit", autoSaveOnExit, defaultProfile.autoSaveOnExit),
        autoLoadOnStart = pick("autoLoadOnStart", autoLoadOnStart, defaultProfile.autoLoadOnStart),
        enableCheats = pick("enableCheats", enableCheats, defaultProfile.enableCheats),
        frameSkip = pick("frameSkip", frameSkip, defaultProfile.frameSkip),
        frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled, defaultProfile.frameLimitEnabled),
        targetFps = pick("targetFps", targetFps, defaultProfile.targetFps),
        audioVolume = pick("audioVolume", audioVolume, defaultProfile.audioVolume),
        audioMuted = pick("audioMuted", audioMuted, defaultProfile.audioMuted),
        audioOutputLatencyMs = pick("audioOutputLatencyMs", audioOutputLatencyMs, defaultProfile.audioOutputLatencyMs),
        audioMinimalOutputLatency = pick(
            "audioMinimalOutputLatency",
            audioMinimalOutputLatency,
            defaultProfile.audioMinimalOutputLatency
        )
    )
}
