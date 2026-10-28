// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.n64

import android.view.Surface

/** Native calls are confined to the session's single owner thread. */
internal object Mupen64Native {
    init { System.loadLibrary("emucored_n64") }

    external fun apiVersion(): Int
    external fun nativeCreate(path: String, directory: String, keys: Array<String>, values: Array<String>): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeFrame(handle: Long): Boolean
    external fun nativeRate(handle: Long): Double
    external fun nativeSurface(handle: Long, surface: Surface?)
    external fun nativePause(handle: Long, paused: Boolean)
    external fun nativeButton(handle: Long, player: Int, button: Int, pressed: Boolean)
    external fun nativeAnalog(handle: Long, player: Int, x: Float, y: Float, secondary: Boolean)
    external fun nativeStateFile(handle: Long, path: String, save: Boolean): Boolean
    external fun nativeOption(handle: Long, key: String, value: String): Boolean
}
