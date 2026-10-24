// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin;
import android.view.Surface;
/** Test APK owns the same JNI contract without depending on the app module. */
final class DolphinNative {
    static { System.loadLibrary("emucored_dolphin"); }
    static native int start(String path, String user, String sys, boolean wii, boolean paused, String[] keys, String[] values);
    static native void requestStop();
    static native void stop();
    static native int status();
    static native boolean pause(boolean paused);
    static native void surface(Surface surface, int width, int height);
    static native void control(int player, int control, double value);
    static native boolean speed(float multiplier);
    static native boolean state(String path, boolean save);
    static native boolean option(String key, String value);
    static native String optionValue(String key);
    static native String[] optionCapabilities();
    static native long frames();
    static native int probe(String path);
}
