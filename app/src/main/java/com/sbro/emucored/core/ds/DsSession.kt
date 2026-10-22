// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core.ds

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.net.Uri
import android.view.Surface
import com.sbro.emucored.core.NdsGameMetadataReader
import com.sbro.emucored.data.GamePlatform
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object DsSession {
    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var secondarySurface: Surface? = null

    private var keyMask: Int = 0xFFF

    private var swapScreens: Boolean = false

    private var layoutMode: Int = LAYOUT_STACKED

    private var lastSampleAtMs: Long = 0L
    private var lastSampleFrames: Long = 0L

    private var renderScale: Int = 1

    private var stateDirectory: File? = null
    private var stateGamePath: String? = null
    private var stateGameTitle: String = ""
    private var stateGameSerial: String? = null

    fun start(
        context: Context,
        path: String,
        identityPath: String = path,
        renderScale: Int = 1
    ): Boolean {
        if (!EmuCoreDsNative.isLoaded) return false
        if (isActive) return false
        if (path.isBlank()) return false
        if (GamePlatform.fromFileName(path) != GamePlatform.NDS) return false

        val appContext = context.applicationContext
        trace(appContext, "start path=$path")
        val romFile = materializeRom(appContext, path) ?: run {
            trace(appContext, "materialize failed")
            return false
        }
        trace(appContext, "materialized ${romFile.absolutePath}")
        val saveDir = File(appContext.filesDir, "ds/saves").apply { mkdirs() }
        val userDir = File(appContext.filesDir, "ds").apply { mkdirs() }
        val saveName = romFile.nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(120)
        val saveFile = File(saveDir, "$saveName.sav")
        stateDirectory = File(appContext.filesDir, "ds/states/$saveName").apply { mkdirs() }
        stateGamePath = identityPath
        val metadata = runCatching {
            NdsGameMetadataReader.read(appContext, romFile.absolutePath)
        }.getOrNull()
        stateGameTitle = metadata?.title?.takeIf { it.isNotBlank() }
            ?: romFile.nameWithoutExtension
        stateGameSerial = metadata?.serial?.takeIf { it.isNotBlank() }

        this.renderScale = renderScale.coerceIn(1, 8)
        EmuCoreDsNative.nativeSetRenderScale(this.renderScale)
        trace(appContext, "calling nativeStart scale=${this.renderScale}")
        val started = EmuCoreDsNative.nativeStart(
            romFile.absolutePath,
            saveFile.absolutePath,
            userDir.absolutePath
        )
        trace(appContext, "nativeStart returned $started")
        if (!started) return false
        isActive = true
        keyMask = 0xFFF
        swapScreens = false
        lastSampleAtMs = 0L
        lastSampleFrames = 0L
        EmuCoreDsNative.nativeSetScreenLayout(layoutMode)
        EmuCoreDsNative.nativeSetSwapScreens(swapScreens)
        surface?.let { EmuCoreDsNative.nativeSurfaceChanged(it) }
        secondarySurface?.let { EmuCoreDsNative.nativeSecondarySurfaceChanged(it) }
        return true
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        runCatching { EmuCoreDsNative.nativeStop() }
        surface = null
        secondarySurface = null
        stateDirectory = null
        keyMask = 0xFFF
    }

    fun setSecondarySurface(surface: Surface?) {
        this.secondarySurface = surface
        if (!isActive) return
        if (surface != null) {
            EmuCoreDsNative.nativeSecondarySurfaceChanged(surface)
        } else {
            EmuCoreDsNative.nativeSecondarySurfaceDestroyed()
        }
    }

    fun setSwapScreens(swap: Boolean) {
        swapScreens = swap
        if (isActive) EmuCoreDsNative.nativeSetSwapScreens(swap)
    }

    fun setLayout(mode: Int) {
        layoutMode = mode.coerceIn(LAYOUT_STACKED, LAYOUT_SIDE_BY_SIDE)
        if (isActive) EmuCoreDsNative.nativeSetScreenLayout(layoutMode)
    }

    fun setRenderScale(scale: Int) {
        renderScale = scale.coerceIn(1, 8)
        if (isActive) EmuCoreDsNative.nativeSetRenderScale(renderScale)
    }

    fun currentRenderScale(): Int = renderScale

    const val LAYOUT_STACKED = 0
    const val LAYOUT_SEPARATE = 1
    const val LAYOUT_TOP_ONLY = 2
    const val LAYOUT_BOTTOM_ONLY = 3
    const val LAYOUT_SIDE_BY_SIDE = 4

    fun performanceMetricsSnapshot(): String? {
        if (!isActive) return null
        val now = SystemClock.elapsedRealtime()
        val frames = EmuCoreDsNative.nativeGetFrameCount()
        val previousAt = lastSampleAtMs
        val previousFrames = lastSampleFrames
        lastSampleAtMs = now
        lastSampleFrames = frames
        if (previousAt == 0L) return null
        val seconds = (now - previousAt) / 1000.0
        if (seconds <= 0.0) return null
        val fps = ((frames - previousFrames) / seconds).coerceAtLeast(0.0)
        val speed = (fps / 59.826 * 100.0).coerceAtLeast(0.0)
        val frameTime = if (fps > 0.5) 1000.0 / fps else 0.0
        val overlay = String.format(Locale.US, "FPS: %.1f | Speed: %.1f%%", fps, speed) +
            String.format(Locale.US, "\nFrame: %.1f ms", frameTime)
        return String.format(Locale.US, "%.3f\n%.3f\n%s", fps, speed, overlay)
    }

    fun pause() = Unit
    fun resume() = Unit

    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (!isActive) return
        if (surface != null) {
            EmuCoreDsNative.nativeSurfaceChanged(surface)
        } else {
            EmuCoreDsNative.nativeSurfaceDestroyed()
        }
    }

    fun resetKeys() {
        keyMask = 0xFFF
        if (isActive) EmuCoreDsNative.nativeSetKeyMask(keyMask)
    }

    fun statePath(slot: Int): String? {
        val directory = stateDirectory ?: return null
        return File(directory, "slot${slot.coerceIn(0, 10)}.dss").absolutePath
    }

    fun stateLastModified(slot: Int): Long {
        val path = statePath(slot) ?: return 0L
        return File(path).takeIf { it.isFile }?.lastModified() ?: 0L
    }

    fun saveState(slot: Int): Boolean {
        if (!isActive) return false
        val path = statePath(slot) ?: return false
        File(path).parentFile?.mkdirs()
        if (!EmuCoreDsNative.nativeSaveState(path)) return false
        runCatching {
            val metadata = JSONObject()
                .put("gamePath", stateGamePath ?: "")
                .put("title", stateGameTitle)
                .put("serial", stateGameSerial ?: "")
                .put("platform", "NDS")
                .put("slot", slot)
                .put("savedAt", System.currentTimeMillis())
            File("$path.json").writeText(metadata.toString())
        }
        return true
    }

    fun loadState(slot: Int): Boolean {
        if (!isActive) return false
        val path = statePath(slot) ?: return false
        if (!File(path).isFile) return false
        return EmuCoreDsNative.nativeLoadState(path)
    }

    fun setPadButton(index: Int, pressed: Boolean) {
        if (!isActive) return
        val bit = ndsButtonBit(index) ?: return
        keyMask = if (pressed) keyMask and (1 shl bit).inv() else keyMask or (1 shl bit)
        EmuCoreDsNative.nativeSetKeyMask(keyMask)
    }

    fun touch(x: Int, y: Int, pressed: Boolean) {
        if (!isActive) return
        if (pressed) {
            EmuCoreDsNative.nativeTouch(
                x.coerceIn(0, 255),
                y.coerceIn(0, 191)
            )
        } else {
            EmuCoreDsNative.nativeReleaseTouch()
        }
    }

    private fun ndsButtonBit(index: Int): Int? = when (index) {
        97 -> 0   // A
        96 -> 1   // B
        109 -> 2  // Select
        108 -> 3  // Start
        19 -> 6   // Up
        22 -> 4   // Right
        20 -> 7   // Down
        21 -> 5   // Left
        103 -> 8  // R
        102 -> 9  // L
        100 -> 10 // X
        99 -> 11  // Y
        else -> null
    }

    private fun trace(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, "ds/session.log")
            file.parentFile?.mkdirs()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private fun materializeRom(context: Context, path: String): File? {
        val rawPath = path.removePrefix("!")
        if (!rawPath.startsWith("content://")) {
            val localPath = if (rawPath.startsWith("file://")) {
                runCatching { Uri.parse(rawPath).path }.getOrNull() ?: rawPath
            } else {
                rawPath
            }
            return File(localPath).takeIf { it.isFile }
        }
        val extension = path.substringAfterLast('.', "nds")
            .takeIf { it in setOf("nds", "srl", "dsi") } ?: "nds"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        val dir = File(context.cacheDir, "ds-roms").apply { mkdirs() }
        val target = File(dir, "$digest.$extension")
        if (target.isFile && target.length() > 0) return target
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                val temporary = File.createTempFile(digest, ".tmp", dir)
                try {
                    temporary.outputStream().use { output -> input.copyTo(output) }
                    if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                    target.takeIf { it.isFile && it.length() > 0 }
                } finally {
                    temporary.delete()
                }
            }
        }.getOrNull()
    }
}
