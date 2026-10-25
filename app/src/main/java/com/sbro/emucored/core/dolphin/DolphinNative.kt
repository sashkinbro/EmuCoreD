// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin

import android.view.Surface
import androidx.annotation.Keep

/** Own JNI ABI; upstream org.dolphinemu classes are never loaded. */
@Keep
internal object DolphinNative {
    val available: Boolean = runCatching { System.loadLibrary("emucored_dolphin") }.isSuccess
    external fun start(path: String, user: String, sys: String, wii: Boolean, paused: Boolean,
                       keys: Array<String>, values: Array<String>): Int
    external fun requestStop()
    external fun stop()
    external fun status(): Int
    external fun pause(paused: Boolean): Boolean
    external fun surface(surface: Surface?, width: Int, height: Int)
    external fun control(player: Int, control: Int, value: Double)
    external fun speed(multiplier: Float): Boolean
    external fun state(path: String, save: Boolean): Boolean
    external fun option(key: String, value: String): Boolean
    /** Actual upstream Config readback. Null when stopped or the key is unavailable. */
    external fun optionValue(key: String): String?
    /** key<TAB>boot|live<TAB>accepted values; reflects initialized GPU capabilities. */
    external fun optionCapabilities(): Array<String>
    external fun frames(): Long
    /** Unique frames, actual VI target Hz, emulated-clock speed %, XFB size, backend ID. */
    external fun performance(): DoubleArray?
    external fun probe(path: String): Int
    external fun probeIdentity(path:String):Array<String>?
    external fun cheats(formats:Array<String>,codes:Array<String>):Boolean
}
