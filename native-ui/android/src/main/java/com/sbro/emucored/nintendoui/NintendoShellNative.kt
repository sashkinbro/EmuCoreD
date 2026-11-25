package com.sbro.emucored.nintendoui

/**
 * JNI bridge for the native GLES 3.0 Nintendo console shell renderer.
 * Nothing in this module touches any emulation core.
 */
object NintendoShellNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("emucored_nintendoui")
                loaded = true
            }
        }
        return loaded
    }

    init {
        ensureLoaded()
    }

    @JvmStatic external fun nativeInit(): Boolean

    @JvmStatic external fun nativeResize(width: Int, height: Int)

    @JvmStatic external fun nativeSetSafeInsets(leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float)

    /**
     * Renders one frame and returns true if continuous animation should keep rendering
     * on the next vsync, or false when Static mode is idle and can pause on RENDERMODE_WHEN_DIRTY.
     */
    @JvmStatic external fun nativeDraw(): Boolean

    @JvmStatic external fun nativeShutdown()

    @JvmStatic external fun nativeKey(key: Int)

    @JvmStatic external fun nativeTouchDown(normX: Float, normY: Float)

    @JvmStatic external fun nativeTouchMove(normX: Float, normY: Float)

    @JvmStatic external fun nativeTouchUp(normX: Float, normY: Float)

    @JvmStatic external fun nativeTouchCancel()

    @JvmStatic external fun nativeTouchTwoFingerBack()

    @JvmStatic external fun nativeTouchLongPress(normX: Float, normY: Float)

    @JvmStatic external fun nativeSetStrings(keys: Array<String>, values: Array<String>)

    @JvmStatic external fun nativeSetConsoleTabs(
        tabIds: Array<String>,
        tabLabels: Array<String>,
        accentColors: IntArray,
    )

    @JvmStatic external fun nativeSetGames(
        titles: Array<String>,
        paths: Array<String>,
        serials: Array<String>,
        covers: Array<String>,
        sizes: Array<String>,
        dates: Array<String>,
        platformIds: Array<String>,
        platformLabels: Array<String>,
        saveCounts: IntArray,
        latestSaveSlots: IntArray,
        recentRanks: IntArray,
    )

    @JvmStatic external fun nativeSetSaves(
        ids: Array<String>,
        titles: Array<String>,
        slotLabels: Array<String>,
        slotNums: IntArray,
        dates: Array<String>,
        sizes: Array<String>,
        serials: Array<String>,
        platformIds: Array<String>,
        platformLabels: Array<String>,
        previews: Array<String>,
        savePaths: Array<String>,
        gamePaths: Array<String>,
    )

    @JvmStatic external fun nativeSetMemorySaves(
        ids: Array<String>,
        filePaths: Array<String>,
        titles: Array<String>,
        consoleIds: Array<String>,
        consoleShorts: Array<String>,
        sizes: Array<String>,
        dates: Array<String>,
        sizeBytes: LongArray,
    )

    @JvmStatic external fun nativeSetSettings(
        theme: Int,
        aspectMode: Int,
        motionMode: Int,
        scanlinesMode: Int,
        bootAnimMode: Int,
        keyTone: Boolean,
        ambientSound: Boolean,
        swapConfirm: Boolean,
        touchControls: Boolean,
        time24h: Boolean,
        dateFormat: Int,
        keepScreenOn: Boolean,
    )

    @JvmStatic external fun nativeSetSystemStatus(
        batteryLevel: Int,
        batteryCharging: Boolean,
        wifiConnected: Boolean,
        bluetoothConnected: Boolean,
        audioMuted: Boolean,
        freeStorage: String,
        deviceName: String,
        appVersion: String,
        systemsSummary: String,
        coreCount: Int,
    )

    @JvmStatic external fun nativeSetTouchMode(active: Boolean, showTutorial: Boolean)

    @JvmStatic external fun nativeSetLoading(gamesLoading: Boolean, savesLoading: Boolean)

    @JvmStatic external fun nativeShowToast(message: String)

    @JvmStatic external fun nativeTriggerTransition(kind: Int)

    @JvmStatic external fun nativeGetState(): IntArray

    @JvmStatic external fun nativeSetState(
        screen: Int,
        mainIndex: Int,
        tabIndex: Int,
        gameIndex: Int,
        fileTab: Int,
        memFilterIndex: Int,
        saveIndex: Int,
    )

    @JvmStatic external fun nativeNotifyImageDecoded(
        path: String,
        width: Int,
        height: Int,
        rgba: ByteArray?,
    )
}
