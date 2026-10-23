// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin;
final class DolphinServices {
    public static int sampleRate() { return 48000; }
    public static int framesPerBuffer() { return 256; }
    public static boolean hasMicrophonePermission() { return false; }
    public static void requestMicrophonePermission() {}
}
