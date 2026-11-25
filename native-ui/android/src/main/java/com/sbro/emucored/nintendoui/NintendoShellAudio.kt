package com.sbro.emucored.nintendoui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural 44.1 kHz PCM sound synthesizer and soft console ambient pad
 * for the native GLES 3.0 Nintendo UI shell.
 */
object NintendoShellAudio {
    const val SFX_MOVE = 0
    const val SFX_CONFIRM = 1
    const val SFX_CANCEL = 2
    const val SFX_TAB = 3
    const val SFX_ERROR = 4
    const val SFX_BOOT_INTRO = 5
    const val SFX_LAUNCH = 6

    private const val SAMPLE_RATE = 44100
    private val lock = Any()

    @Volatile
    var keyToneEnabled: Boolean = true

    @Volatile
    var ambientEnabled: Boolean = false
        set(value) {
            field = value
            syncAmbientPlayback()
        }

    @Volatile
    var hostVisible: Boolean = true
        set(value) {
            field = value
            syncAmbientPlayback()
        }

    private var initialized = false
    private var pool: SoundPool? = null
    private val soundIds = IntArray(7)
    private val readyIds = HashSet<Int>()

    private var ambientThread: Thread? = null
    @Volatile
    private var ambientStopRequested = false

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val sfxDir = File(context.cacheDir, "nintendoui_sfx_v2").apply { mkdirs() }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(attrs)
                .build()
            sp.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    synchronized(lock) {
                        readyIds.add(sampleId)
                    }
                }
            }
            pool = sp

            val generators: List<Pair<Int, () -> ShortArray>> = listOf(
                SFX_MOVE to ::synthMoveSfx,
                SFX_CONFIRM to ::synthConfirmSfx,
                SFX_CANCEL to ::synthCancelSfx,
                SFX_TAB to ::synthTabSfx,
                SFX_ERROR to ::synthErrorSfx,
                SFX_BOOT_INTRO to ::synthBootIntroSfx,
                SFX_LAUNCH to ::synthLaunchSfx,
            )

            for ((index, gen) in generators) {
                val wavFile = File(sfxDir, "nui_sfx_$index.wav")
                if (!wavFile.isFile || wavFile.length() < 128L) {
                    runCatching { writeWav(wavFile, gen()) }
                }
                if (wavFile.isFile) {
                    soundIds[index] = sp.load(wavFile.absolutePath, 1)
                }
            }
            initialized = true
        }
        syncAmbientPlayback()
    }

    @JvmStatic
    fun playSfx(kind: Int) {
        if (!keyToneEnabled) return
        if (kind !in soundIds.indices) return
        val sp = pool ?: return
        val id = soundIds[kind]
        if (id == 0) return
        val isReady = synchronized(lock) { readyIds.contains(id) }
        if (!isReady) return
        val vol = when (kind) {
            SFX_MOVE -> 0.42f
            SFX_CONFIRM -> 0.54f
            SFX_CANCEL -> 0.45f
            SFX_TAB -> 0.44f
            SFX_ERROR -> 0.46f
            SFX_BOOT_INTRO -> 0.58f
            SFX_LAUNCH -> 0.54f
            else -> 0.45f
        }
        sp.play(id, vol, vol, 1, 0, 1.0f)
    }

    fun pauseAudio() {
        hostVisible = false
    }

    fun resumeAudio() {
        hostVisible = true
    }

    fun shutdown() {
        stopAmbientThread()
        synchronized(lock) {
            pool?.release()
            pool = null
            readyIds.clear()
            soundIds.fill(0)
            initialized = false
        }
    }

    private fun syncAmbientPlayback() {
        val shouldPlay = initialized && ambientEnabled && hostVisible
        if (shouldPlay) {
            startAmbientThread()
        } else {
            stopAmbientThread()
        }
    }

    private fun startAmbientThread() {
        synchronized(lock) {
            if (ambientThread?.isAlive == true) return
            ambientStopRequested = false
            ambientThread = thread(start = true, isDaemon = true, name = "NintendoShellAmbient") {
                runAmbientLoop()
            }
        }
    }

    private fun stopAmbientThread() {
        ambientStopRequested = true
        val t = synchronized(lock) { ambientThread }
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(250) }
        }
        synchronized(lock) {
            if (ambientThread === t) {
                ambientThread = null
            }
        }
    }

    private fun runAmbientLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        val chunk = ShortArray(2048)
        var sampleIdx = 0L
        // Soft pastel Fmaj9 glass pad reminiscent of handheld home channels
        val freqs = doubleArrayOf(174.61, 220.00, 261.63, 329.63, 392.00, 523.25)
        val amps = doubleArrayOf(0.24, 0.20, 0.17, 0.13, 0.10, 0.06)
        var fadeGain = 0.0

        try {
            track.play()
            while (!ambientStopRequested) {
                for (i in chunk.indices) {
                    val t = sampleIdx.toDouble() / SAMPLE_RATE.toDouble()
                    val lfo1 = 0.70 + 0.30 * sin(2.0 * PI * 0.085 * t)
                    val lfo2 = 0.74 + 0.26 * sin(2.0 * PI * 0.055 * t + 1.6)
                    var sum = 0.0
                    for (fIdx in freqs.indices) {
                        val detune = 1.0 + 0.0010 * sin(2.0 * PI * (0.04 + fIdx * 0.014) * t)
                        val mod = if (fIdx % 2 == 0) lfo1 else lfo2
                        sum += amps[fIdx] * mod * sin(2.0 * PI * freqs[fIdx] * detune * t)
                    }
                    fadeGain = (fadeGain + 1.0 / (SAMPLE_RATE * 1.2)).coerceAtMost(1.0)
                    val s = tanh(sum * 0.19 * fadeGain)
                    chunk[i] = (s * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
                    sampleIdx++
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private fun synthMoveSfx(): ShortArray {
        val len = (SAMPLE_RATE * 0.055).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 1200.0)) * exp(-t * 78.0)
            val f = 1320.0 + 340.0 * (1.0 - exp(-t * 65.0))
            val tone = 0.80 * sin(2.0 * PI * f * t) + 0.20 * sin(2.0 * PI * (f * 2.0) * t)
            (tone * env * 0.48 * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthConfirmSfx(): ShortArray {
        val len = (SAMPLE_RATE * 0.20).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env1 = (1.0 - exp(-t * 820.0)) * exp(-t * 25.0)
            val s1 = 0.68 * sin(2.0 * PI * 987.77 * t) + 0.32 * sin(2.0 * PI * 1479.98 * t) // B5 + F#6
            val t2 = (t - 0.038).coerceAtLeast(0.0)
            val env2 = if (t >= 0.038) (1.0 - exp(-t2 * 700.0)) * exp(-t2 * 16.0) else 0.0
            val s2 = 0.65 * sin(2.0 * PI * 1567.98 * t2) + 0.35 * sin(2.0 * PI * 2093.00 * t2) // G6 + C7
            val mixed = tanh((s1 * env1 + s2 * env2) * 0.46)
            (mixed * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthCancelSfx(): ShortArray {
        val len = (SAMPLE_RATE * 0.14).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 680.0)) * exp(-t * 29.0)
            val f = 880.0 * exp(-t * 3.2)
            val tone = 0.80 * sin(2.0 * PI * f * t) + 0.20 * sin(2.0 * PI * (f * 1.5) * t)
            (tone * env * 0.46 * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthTabSfx(): ShortArray {
        val len = (SAMPLE_RATE * 0.09).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 950.0)) * exp(-t * 44.0)
            val f = 1046.5 + 440.0 * (1.0 - exp(-t * 40.0))
            val tone = 0.72 * sin(2.0 * PI * f * t) + 0.28 * sin(2.0 * PI * (f * 1.5) * t)
            (tone * env * 0.46 * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthErrorSfx(): ShortArray {
        val len = (SAMPLE_RATE * 0.18).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val p1 = (1.0 - exp(-t * 650.0)) * exp(-t * 38.0)
            val t2 = (t - 0.072).coerceAtLeast(0.0)
            val p2 = if (t >= 0.072) (1.0 - exp(-t2 * 650.0)) * exp(-t2 * 32.0) else 0.0
            val tone = sin(2.0 * PI * 220.0 * t) + 0.35 * sin(2.0 * PI * 330.0 * t)
            (tanh(tone * (p1 + p2) * 0.42) * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthBootIntroSfx(): ShortArray {
        val dur = 2.35
        val len = (SAMPLE_RATE * dur).toInt()
        // Act 1: 4 crystal marimba bounces matching tumbling prism cube
        val dropTimes = doubleArrayOf(0.06, 0.32, 0.56, 0.78)
        val dropFreqs = doubleArrayOf(659.25, 783.99, 987.77, 1318.51) // E5, G5, B5, E6
        // Act 2: Bright Nintendo luminous crystal chord swell
        val chordFreqs = doubleArrayOf(196.00, 293.66, 392.00, 493.88, 587.33, 783.99, 1174.66)
        val chordWeights = doubleArrayOf(0.26, 0.24, 0.22, 0.18, 0.15, 0.11, 0.07)

        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            var drops = 0.0
            for (d in dropTimes.indices) {
                val td = t - dropTimes[d]
                if (td >= 0.0) {
                    val envD = (1.0 - exp(-td * 460.0)) * exp(-td * 15.0)
                    val bend = dropFreqs[d] * (1.0 + 0.12 * (1.0 - exp(-td * 48.0)))
                    drops += envD * (0.76 * sin(2.0 * PI * bend * td) + 0.24 * sin(2.0 * PI * (bend * 2.0) * td)) * 0.42
                }
            }
            val ts = (t - 0.92).coerceAtLeast(0.0)
            val envSwell = if (t > 0.92) (1.0 - exp(-ts * 5.2)) * exp(-((ts - 0.22).coerceAtLeast(0.0)) * 1.5) else 0.0
            var swell = 0.0
            if (envSwell > 0.0005) {
                for (k in chordFreqs.indices) {
                    val detune = 1.0 + 0.0012 * sin(2.0 * PI * 0.45 * ts + k * 0.8)
                    swell += chordWeights[k] * sin(2.0 * PI * chordFreqs[k] * detune * ts)
                }
            }
            val mixed = tanh((drops + swell * envSwell) * 0.56)
            (mixed * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun synthLaunchSfx(): ShortArray {
        val dur = 0.80
        val len = (SAMPLE_RATE * dur).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = (1.0 - exp(-t * 11.0)) * exp(-((t - 0.15).coerceAtLeast(0.0)) * 3.9)
            val sweepFreq = 329.63 + 659.25 * (1.0 - exp(-t * 4.4))
            val tone = 0.45 * sin(2.0 * PI * sweepFreq * t) +
                0.35 * sin(2.0 * PI * 987.77 * t) +
                0.28 * sin(2.0 * PI * 1479.98 * t)
            val mixed = tanh(tone * env * 0.52)
            (mixed * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2
        val header = ByteArrayOutputStream(44)
        fun writeAscii(s: String) = s.forEach { header.write(it.code) }
        fun writeIntLE(v: Int) {
            header.write(v and 0xFF)
            header.write((v ushr 8) and 0xFF)
            header.write((v ushr 16) and 0xFF)
            header.write((v ushr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            header.write(v and 0xFF)
            header.write((v ushr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeIntLE(36 + dataBytes)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(SAMPLE_RATE)
        writeIntLE(SAMPLE_RATE * 2)
        writeShortLE(2)
        writeShortLE(16)
        writeAscii("data")
        writeIntLE(dataBytes)

        val pcmBytes = ByteArray(dataBytes)
        var p = 0
        for (sample in pcm) {
            val s = sample.toInt()
            pcmBytes[p] = (s and 0xFF).toByte()
            pcmBytes[p + 1] = ((s ushr 8) and 0xFF).toByte()
            p += 2
        }
        FileOutputStream(file).use { out ->
            out.write(header.toByteArray())
            out.write(pcmBytes)
        }
    }
}
