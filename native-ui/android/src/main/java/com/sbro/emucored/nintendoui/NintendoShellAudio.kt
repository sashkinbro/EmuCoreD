package com.sbro.emucored.nintendoui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates crisp, lightweight Nintendo-inspired UI chimes & navigation clicks cleanly in memory
 * using AudioTrack, with zero bundled PS1 audio assets.
 */
object NintendoShellAudio {
    private const val SAMPLE_RATE = 44100
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var enabled: Boolean = true

    private val cues: Map<Int, ShortArray> by lazy {
        mapOf(
            0 to synthTone(doubleArrayOf(880.0), 0.045, 0.14),                       // Move tick
            1 to synthTone(doubleArrayOf(659.25, 987.77), 0.09, 0.20),             // Confirm sparkle
            2 to synthTone(doubleArrayOf(587.33, 392.0), 0.09, 0.16),              // Back step
            3 to synthTone(doubleArrayOf(523.25, 659.25), 0.075, 0.16),            // Tab whoosh-chime
            4 to synthTone(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 0.22, 0.24), // Game launch
            5 to synthTone(doubleArrayOf(587.33, 880.0, 1174.66), 0.28, 0.22)      // Intro crest chime
        )
    }

    @JvmStatic
    fun playSfx(sfxId: Int) {
        if (!enabled) return
        val pcm = cues[sfxId] ?: return
        executor.execute {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L / SAMPLE_RATE) + 30L)
                track.release()
            }
        }
    }

    private fun synthTone(notesHz: DoubleArray, totalSeconds: Double, gain: Double): ShortArray {
        val totalSamples = (SAMPLE_RATE * totalSeconds).toInt().coerceAtLeast(256)
        val out = ShortArray(totalSamples)
        val segLen = totalSamples / notesHz.size.coerceAtLeast(1)
        for (i in 0 until totalSamples) {
            val noteIdx = (i / segLen).coerceAtMost(notesHz.size - 1)
            val localT = (i - noteIdx * segLen).toDouble() / SAMPLE_RATE.toDouble()
            val env = exp(-localT * (18.0 / notesHz.size.toDouble())) *
                minOf(1.0, localT / 0.004)
            val f = notesHz[noteIdx]
            val wave = sin(2.0 * PI * f * localT) + 0.25 * sin(4.0 * PI * f * localT)
            val sample = (wave * env * gain * 32767.0).toInt().coerceIn(-32767, 32767)
            out[i] = sample.toShort()
        }
        return out
    }
}
