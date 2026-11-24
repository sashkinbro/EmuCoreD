package com.sbro.emucored.nintendoui

object NintendoShellNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("emucored_nintendoui")
                nativeInitBridge()
                loaded = true
            }
        }
        return loaded
    }

    @JvmStatic external fun nativeInitBridge()
    @JvmStatic external fun nativeOnSurfaceCreated()
    @JvmStatic external fun nativeOnSurfaceChanged(width: Int, height: Int)
    @JvmStatic external fun nativeDrawFrame(dtSeconds: Float)
    @JvmStatic external fun nativeReleaseGl()

    @JvmStatic external fun nativeSetStrings(keys: Array<String>, values: Array<String>)

    @JvmStatic external fun nativeSetCatalog(
        paths: Array<String>,
        titles: Array<String>,
        consoleIds: Array<String>,
        consoleShorts: Array<String>,
        consoleLabels: Array<String>,
        coverKeys: Array<String>,
        coverPaths: Array<String>,
        recentPaths: Array<String>
    )

    @JvmStatic external fun nativeSetQuickSaves(
        ids: Array<String>,
        gamePaths: Array<String>,
        gameTitles: Array<String>,
        consoleIds: Array<String>,
        consoleShorts: Array<String>,
        slotLabels: Array<String>,
        timeTexts: Array<String>,
        sizeTexts: Array<String>,
        previewPaths: Array<String>,
        slots: IntArray
    )

    @JvmStatic external fun nativeSetMemorySaves(
        ids: Array<String>,
        filePaths: Array<String>,
        titles: Array<String>,
        consoleIds: Array<String>,
        consoleShorts: Array<String>,
        sizeTexts: Array<String>,
        timeTexts: Array<String>
    )

    @JvmStatic external fun nativeSetSettings(
        themeId: Int,
        aspectMode: Int,
        ecoMode: Boolean,
        sfxEnabled: Boolean
    )

    @JvmStatic external fun nativeSetClock(clockText: String)
    @JvmStatic external fun nativeShowToast(message: String)
    @JvmStatic external fun nativeOnTouch(action: Int, x: Float, y: Float)
    @JvmStatic external fun nativeOnKey(keyCode: Int, down: Boolean): Boolean
    @JvmStatic external fun nativeOnAxis(axisX: Float, axisY: Float)
    @JvmStatic external fun nativeOnBackPressed(): Boolean
    @JvmStatic external fun nativeUploadImage(key: String, width: Int, height: Int, rgba: ByteArray)
    @JvmStatic external fun nativeMarkImageFailed(key: String)
}
