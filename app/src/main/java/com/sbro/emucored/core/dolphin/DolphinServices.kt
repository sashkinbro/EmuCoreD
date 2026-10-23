// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin

import android.content.Context
import android.media.AudioManager
import androidx.annotation.Keep

/** Services used by Dolphin's Android audio backend, without upstream UI classes. */
@Keep
internal object DolphinServices {
    @Volatile private var outputRate = 48000
    @Volatile private var outputFrames = 256
    fun configure(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        outputRate = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
        outputFrames = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()?.takeIf { it in 16..8192 } ?: 256
    }
    @JvmStatic fun sampleRate(): Int = outputRate
    @JvmStatic fun framesPerBuffer(): Int = outputFrames
    // Microphone peripherals have no frontend permission workflow yet. Playback
    // audio remains available; do not trigger unsolicited permission dialogs.
    @JvmStatic fun hasMicrophonePermission(): Boolean = false
    @JvmStatic fun requestMicrophonePermission() = Unit
}
