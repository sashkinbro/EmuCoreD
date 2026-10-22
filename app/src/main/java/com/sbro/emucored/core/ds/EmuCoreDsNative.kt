// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core.ds

import android.util.Log
import android.view.Surface
import androidx.annotation.Keep

@Keep
object EmuCoreDsNative {
    private const val TAG = "EmuCoreDsNative"

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("emucored_ds")
            isLoaded = true
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG, "Unable to load libemucored_ds.so", error)
            isLoaded = false
        }
    }

    external fun nativeGetVersion(): String
    external fun nativeStart(romPath: String, sramPath: String, userDir: String): Boolean
    external fun nativeStop()
    external fun nativeReset()
    external fun nativeIsRunning(): Boolean
    external fun nativeSurfaceChanged(surface: Surface)
    external fun nativeSurfaceDestroyed()
    external fun nativeSecondarySurfaceChanged(surface: Surface)
    external fun nativeSecondarySurfaceDestroyed()
    external fun nativeSetSwapScreens(swap: Boolean)
    external fun nativeSetScreenLayout(mode: Int)
    external fun nativeGetFrameCount(): Long
    external fun nativeSetKeyMask(mask: Int)
    external fun nativeTouch(x: Int, y: Int)
    external fun nativeReleaseTouch()
    external fun nativeSetRenderScale(scale: Int)
    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean
}
