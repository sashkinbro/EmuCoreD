// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

/**
 * JNI surface of the frontend's own shader library (`libemucore_shaders.so`).
 *
 * The library holds the RetroArch (.slangp) chain and the GL post-processing
 * presenter used for the downloadable shader packs. The emulator core itself
 * is reached through [EmuCoreNative]; nothing here talks to the core.
 */
class NativeCoreBridge {
    companion object {
        init {
            System.loadLibrary("emucore_shaders")
        }
    }

    /** RetroArch-style post effect (0 off, 1 CRT, 2 LCD, 3 sharp bilinear, ...). */
    external fun nativeSetShaderEffect(effect: Int)

    /** Installs (or clears) the .slangp filter chain from [path]. */
    external fun nativeSetShaderPreset(path: String, enabled: Boolean)
}
