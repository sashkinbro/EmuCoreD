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
    var isPaused: Boolean = false
        private set

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var secondarySurface: Surface? = null

    private var keyMask: Int = 0xFFF

    private var swapScreens: Boolean = false

    private var layoutMode: Int = LAYOUT_STACKED

    private val performanceSampler = GuestFrameRateSampler()

    private var renderScale: Int = 1
    private var renderer: Int = 14

    private var stateDirectory: File? = null
    private var stateGamePath: String? = null
    private var stateGameTitle: String = ""
    private var stateGameSerial: String? = null
    private var appContext: Context? = null
    private var launchPath: String? = null
    private var launchIdentityPath: String? = null
    private var appliedCheats:List<com.sbro.emucored.core.platform.session.CoreCheatCode> = emptyList()

    /** Prefetches a SAF DS image while the previous core is shutting down. */
    fun prepareRom(context: Context, path: String): File? =
        materializeRom(context.applicationContext, path)

    @Synchronized
    fun start(
        context: Context,
        path: String,
        identityPath: String = path,
        renderScale: Int = 1,
        renderer: Int = 14,
        startPaused: Boolean = false,
        swapped: Boolean = false
    ): Boolean {
        if (!EmuCoreDsNative.isLoaded) return false
        if (isActive) return false
        if (path.isBlank()) return false
        if (GamePlatform.fromFileName(path) != GamePlatform.NDS) return false
        performanceSampler.reset()

        val appContext = context.applicationContext
        this.appContext = appContext
        this.launchPath = path
        this.launchIdentityPath = identityPath
        this.renderer = if (renderer == 13) 13 else 14
        trace(appContext, "start path=$path")
        val romFile = materializeRom(appContext, path) ?: run {
            trace(appContext, "materialize failed")
            return false
        }
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
        com.sbro.emucored.core.GpuDriverRuntime.configureDs(appContext, identityPath, this.renderer == 14)
        EmuCoreDsNative.nativeSetRenderScale(this.renderScale)
        EmuCoreDsNative.nativeSetRenderer(if (this.renderer == 13) 0 else 1)
        swapScreens = swapped
        EmuCoreDsNative.nativeSetSwapScreens(swapped)
        val started = EmuCoreDsNative.nativeStart(
            romFile.absolutePath,
            saveFile.absolutePath,
            userDir.absolutePath,
            startPaused
        )
        trace(appContext, "nativeStart returned $started")
        if (!started) return false
        isActive = true
        isPaused = startPaused
        keyMask = 0xFFF
        EmuCoreDsNative.nativeSetScreenLayout(layoutMode)
        EmuCoreDsNative.nativeSetSwapScreens(swapScreens)
        surface?.let { EmuCoreDsNative.nativeSurfaceChanged(it) }
        secondarySurface?.let { EmuCoreDsNative.nativeSecondarySurfaceChanged(it) }
        return true
    }

    @Synchronized
    fun stop() {
        performanceSampler.reset()
        if (!isActive) return
        isActive = false
        isPaused = false
        runCatching { EmuCoreDsNative.nativeStop() }
        surface = null
        secondarySurface = null
        stateDirectory = null
        keyMask = 0xFFF
        appliedCheats=emptyList()
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

    fun currentSwapScreens(): Boolean = swapScreens

    fun setLayout(mode: Int) {
        layoutMode = mode.coerceIn(LAYOUT_STACKED, LAYOUT_SIDE_BY_SIDE)
        if (isActive) EmuCoreDsNative.nativeSetScreenLayout(layoutMode)
    }

    fun setRenderScale(scale: Int) {
        renderScale = scale.coerceIn(1, 8)
        if (isActive) EmuCoreDsNative.nativeSetRenderScale(renderScale)
    }

    fun currentRenderScale(): Int = renderScale
    @Synchronized
    fun replaceCheats(codes:List<com.sbro.emucored.core.platform.session.CoreCheatCode>):Boolean {
        if(!isActive || codes.size>4096 || codes.any{!com.sbro.emucored.core.platform.session.CoreCheatFormats.accepts(
            com.sbro.emucored.core.platform.CoreId.MELONDS,it)})return false
        val applied=EmuCoreDsNative.nativeReplaceCheats(codes.map{it.format}.toTypedArray(),codes.map{it.code}.toTypedArray())
        if(applied)appliedCheats=codes.toList()
        return applied
    }

    @Synchronized
    fun setRenderer(renderer: Int): Boolean {
        val normalized = if (renderer == 13) 13 else 14
        if (!isActive) {
            this.renderer = normalized
            return true
        }
        if (normalized == this.renderer) return true
        val context = appContext ?: return false
        val path = launchPath ?: return false
        val identity = launchIdentityPath ?: path
        val previousRenderer = this.renderer
        val wasPaused = isPaused
        val currentCheats=appliedCheats.toList()
        val swap = swapScreens
        val layout = layoutMode
        val scale = renderScale
        val surface = this.surface
        val secondary = this.secondarySurface
        val checkpoint = runCatching {
            File.createTempFile("ds-renderer-", ".dss", context.cacheDir)
        }.getOrElse {
            Log.e("DsSession", "Cannot create renderer recovery checkpoint", it)
            return false
        }
        pause()
        if (!runCatching { EmuCoreDsNative.nativeSaveState(checkpoint.absolutePath) }.getOrDefault(false)) {
            checkpoint.delete()
            if (!wasPaused) resume()
            return false
        }

        fun restartAndRestore(target: Int): Boolean = runCatching {
            stop()
            this.surface = surface
            this.secondarySurface = secondary
            if (!start(context, path, identity, scale, target, startPaused = true, swapped = swap)) return@runCatching false
            setLayout(layout)
            setSwapScreens(swap)
            if (!EmuCoreDsNative.nativeLoadState(checkpoint.absolutePath)) return@runCatching false
            if (!replaceCheats(currentCheats)) return@runCatching false
            if (!wasPaused) resume()
            true
        }.getOrElse {
            Log.e("DsSession", "Renderer recovery failed for renderer $target", it)
            false
        }

        if (restartAndRestore(normalized)) {
            checkpoint.delete()
            return true
        }
        if (restartAndRestore(previousRenderer)) {
            checkpoint.delete()
        } else {
            stop()
            this.renderer = previousRenderer
            Log.e("DsSession", "Renderer rollback failed; recovery checkpoint retained at ${checkpoint.absolutePath}")
        }
        return false
    }

    fun currentRenderer(): Int = renderer

    const val LAYOUT_STACKED = 0
    const val LAYOUT_SEPARATE = 1
    const val LAYOUT_TOP_ONLY = 2
    const val LAYOUT_BOTTOM_ONLY = 3
    const val LAYOUT_SIDE_BY_SIDE = 4

    @Synchronized
    fun performanceMetricsSnapshot(): String? {
        if (!isActive || isPaused) {
            performanceSampler.reset()
            return null
        }
        val sample = performanceSampler.sample(SystemClock.elapsedRealtime(), EmuCoreDsNative.nativeGetFrameCount())
            ?: return null
        val fps = sample.fps
        val speed = sample.speedPercent
        val frameTime = sample.frameTimeMs
        val overlay = String.format(Locale.US, "FPS: %.1f | Speed: %.1f%%", fps, speed) +
            String.format(Locale.US, "\nFrame: %.1f ms", frameTime)
        return String.format(Locale.US, "%.3f\n%.3f\n%s", fps, speed, overlay)
    }

    @Synchronized
    fun pause() {
        if (isActive) {
            EmuCoreDsNative.nativeSetPaused(true)
            isPaused = true
            performanceSampler.reset()
        }
    }

    @Synchronized
    fun resume() {
        if (isActive) {
            val wasPaused = isPaused
            EmuCoreDsNative.nativeSetPaused(false)
            isPaused = false
            if (wasPaused) performanceSampler.reset()
        }
    }

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
        traceDsSession(context, message)
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
