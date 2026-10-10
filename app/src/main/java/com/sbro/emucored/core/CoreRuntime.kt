// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.Context
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel

/**
 * Process-wide owner of the emulation session.
 *
 * EmuCoreD drives the bundled core through [EmuCoreNative]: the core owns its
 * emulation thread, its renderer (OpenGL or Vulkan) and its audio output, and
 * reports back through the upcalls implemented on [EmuCoreNative].
 */
internal object CoreRuntime {
    private const val TAG = "CoreRuntime"

    /** 3DS framebuffer the emulated touch coordinates are expressed in. */
    private const val FRAMEBUFFER_WIDTH = 400
    private const val FRAMEBUFFER_HEIGHT = 240

    private val _failure = MutableStateFlow<RuntimeFailure?>(null)
    val failure = _failure.asStateFlow()

    /** Room/chat messages pushed by the core (see [onNetPlayMessage]). */
    val netPlayEvents = Channel<String>(Channel.UNLIMITED)

    private val lifecycleLock = ReentrantLock()
    private var context: Context? = null

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var currentGamePath: String? = null
    @Volatile private var performanceMetricsEnabled = false
    @Volatile private var detailedPerformanceMetrics = false
    @Volatile private var performanceMetricsSnapshot: String? = null
    @Volatile private var requestedRenderer = RendererDefaults.defaultForHardware()
    @Volatile private var pendingCheatTitleId: Long = 0L

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    private val desiredPadButtons = AtomicIntegerArray(IntArray(2) { 0xFFFF })
    private val appliedPadButtons = AtomicIntegerArray(IntArray(2) { 0xFFFF })
    private val desiredPadAnalog = AtomicIntegerArray(IntArray(2) { 0x80808080.toInt() })
    private val appliedPadAnalog = AtomicIntegerArray(IntArray(2) { 0x80808080.toInt() })

    // 3DS pad bit (active low) -> core button id.
    private val padButtonMap = mapOf(
        0 to EmuCoreNative.ButtonType.BUTTON_SELECT,
        1 to EmuCoreNative.ButtonType.BUTTON_ZL,
        2 to EmuCoreNative.ButtonType.BUTTON_ZR,
        3 to EmuCoreNative.ButtonType.BUTTON_START,
        4 to EmuCoreNative.ButtonType.DPAD_UP,
        5 to EmuCoreNative.ButtonType.DPAD_RIGHT,
        6 to EmuCoreNative.ButtonType.DPAD_DOWN,
        7 to EmuCoreNative.ButtonType.DPAD_LEFT,
        8 to EmuCoreNative.ButtonType.BUTTON_HOME,
        10 to EmuCoreNative.ButtonType.TRIGGER_L,
        11 to EmuCoreNative.ButtonType.TRIGGER_R,
        12 to EmuCoreNative.ButtonType.BUTTON_X,
        13 to EmuCoreNative.ButtonType.BUTTON_A,
        14 to EmuCoreNative.ButtonType.BUTTON_B,
        15 to EmuCoreNative.ButtonType.BUTTON_Y
    )

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun initialize(context: Context) {
        this.context = context.applicationContext
        CoreOptionStore.initialize(context.applicationContext)
        if (!EmuCoreNative.isLoaded) {
            Log.e(TAG, "Native core library is unavailable")
            _failure.value = RuntimeFailure("core_library_missing")
            return
        }
        val root = userDirectory()
        runCatching {
            EmuCoreNative.createConfigFile()
            EmuCoreNative.createLogFile()
            EmuCoreNative.logUserDirectory(root)
            EmuCoreNative.setUserDirectory(root)
            EmuCoreNative.playTimeManagerInit()
        }.onFailure { Log.e(TAG, "Unable to initialise the core", it) }
    }

    /** Directory the core uses for its NAND, SD card and system data. */
    fun userDirectory(): String {
        val active = context ?: return ""
        return File(active.filesDir, "azahar").apply { mkdirs() }.absolutePath
    }

    fun isRunning(): Boolean = running && failure.value == null && EmuCoreNative.isRunning()

    fun hasSession(): Boolean = running || EmuCoreNative.isRunning()

    fun start(gamePath: String, biosOnly: Boolean): Boolean = lifecycleLock.withLock {
        val active = context ?: return@withLock false
        if (!EmuCoreNative.isLoaded) {
            _failure.value = RuntimeFailure("core_library_missing")
            return@withLock false
        }
        if (running) {
            Log.w(TAG, "Refusing to start a second session")
            return@withLock false
        }
        _failure.value = null
        stopping = false
        currentGamePath = gamePath
        pendingCheatTitleId = 0L
        runCatching {
            EmuCoreNative.createConfigFile()
            EmuCoreNative.setUserDirectory(userDirectory())
            EmuCoreNative.run(if (biosOnly) "" else gamePath)
        }.onFailure { error ->
            Log.e(TAG, "Unable to start emulation", error)
            _failure.value = RuntimeFailure(error.message ?: "core_start_failed")
            running = false
            return@withLock false
        }
        running = true
        paused = false
        true
    }

    fun pause() {
        if (!running) return
        paused = true
        runCatching { EmuCoreNative.pauseEmulation() }
            .onFailure { Log.e(TAG, "Unable to pause emulation", it) }
    }

    fun resume() {
        if (!running) return
        paused = false
        attachSurface(surface, surfaceWidth, surfaceHeight)
        runCatching { EmuCoreNative.unPauseEmulation() }
            .onFailure { Log.e(TAG, "Unable to resume emulation", it) }
    }

    fun shutdown() {
        if (!running && !EmuCoreNative.isRunning()) return
        stopping = true
        runCatching { EmuCoreNative.stopEmulation() }
            .onFailure { Log.e(TAG, "Unable to stop emulation", it) }
        running = false
        paused = false
        currentGamePath = null
        resetPadState()
    }

    /** Re-asserts the stored renderer for a fresh session. */
    fun restartWithRenderer(renderer: Int): Boolean = lifecycleLock.withLock {
        val path = currentGamePath
        requestedRenderer = renderer
        if (path.isNullOrBlank()) return@withLock false
        restart(path)
        true
    }

    private fun restart(path: String) {
        runCatching {
            EmuCoreNative.stopEmulation()
            EmuCoreNative.run(path)
        }.onFailure { error ->
            Log.e(TAG, "Unable to restart emulation", error)
            _failure.value = RuntimeFailure(error.message ?: "core_restart_failed")
        }
    }

    fun changeDisc(path: String): Boolean {
        if (path.isBlank() || !running) return false
        return runCatching {
            EmuCoreNative.setInsertedCartridge(path)
            true
        }.getOrDefault(false)
    }

    fun hasDiscMedia(): Boolean = currentGamePath?.isNotBlank() == true

    // ---------------------------------------------------------------------
    // Core callbacks
    // ---------------------------------------------------------------------

    fun reportNativeExit(resultCode: Int) {
        Log.e(TAG, "Core exited with result $resultCode")
        _failure.value = RuntimeFailure("core_exit_$resultCode")
        running = false
    }

    fun reportNativeError(name: String, details: String) {
        Log.e(TAG, "Core error $name: $details")
        _failure.value = RuntimeFailure(details.ifBlank { name })
    }

    fun requestCameraPermission(): Boolean = true

    fun requestMicPermission(): Boolean = true

    fun onNetPlayMessage(type: Int, message: String) {
        netPlayEvents.trySend(message)
    }

    // ---------------------------------------------------------------------
    // Presentation
    // ---------------------------------------------------------------------

    fun attachSurface(value: Surface?, width: Int, height: Int) {
        surface = value
        surfaceWidth = width
        surfaceHeight = height
        if (value == null || !value.isValid || width <= 0 || height <= 0) return
        if (!running) return
        runCatching { EmuCoreNative.surfaceChanged(value) }
            .onFailure { Log.e(TAG, "Unable to attach the presentation surface", it) }
    }

    fun hasAttachedSurface(value: Surface?, width: Int, height: Int): Boolean =
        surface === value && surfaceWidth == width && surfaceHeight == height

    fun detachSurface() {
        runCatching { EmuCoreNative.surfaceDestroyed() }
            .onFailure { Log.e(TAG, "Unable to detach the presentation surface", it) }
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    fun displayRect(): FloatArray? {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return null
        // The core letterboxes the emulated screens itself; the overlay uses
        // the full surface and never overlaps content.
        return floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
    }

    fun setDisplayAspectRatio(type: Int) {
        // Aspect handling belongs to the core layout; keep the preference so a
        // later config write can translate it into layout_option.
        CoreOptionStore.set("aspect_ratio", type.toString())
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    fun setPadButtons(port: Int, buttons: Int) {
        if (port !in 0..1) return
        desiredPadButtons.set(port, buttons and 0xFFFF)
        flushPadButtons(port)
    }

    fun setPadAnalog(port: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        if (port !in 0..1) return
        desiredPadAnalog.set(port, packedAnalog(lx, ly, rx, ry))
        flushPadAnalog(port)
    }

    fun setPadAnalogMode(port: Int, enabled: Boolean): Boolean {
        // The 3DS always exposes both sticks; the toggle only affects whether
        // the D-pad mirrors the left stick.
        return enabled
    }

    fun togglePadAnalogMode(port: Int): Boolean = true

    fun getPadRumble(port: Int): FloatArray? = null

    fun setTouchState(x: Int, y: Int, pressed: Boolean) {
        if (!running) return
        runCatching {
            EmuCoreNative.onTouchEvent(
                x.toFloat(),
                y.toFloat(),
                pressed
            )
        }
    }

    /** Normalised touch coordinates in 0..1 of the emulated 3DS framebuffer. */
    fun setNormalizedTouch(nx: Float, ny: Float, pressed: Boolean) {
        setTouchState(
            (nx.coerceIn(0f, 1f) * FRAMEBUFFER_WIDTH).toInt(),
            (ny.coerceIn(0f, 1f) * FRAMEBUFFER_HEIGHT).toInt(),
            pressed
        )
    }

    private fun flushPadButtons(port: Int) {
        if (!running) return
        val desired = desiredPadButtons.get(port)
        val applied = appliedPadButtons.getAndSet(port, desired)
        if (desired == applied) return
        val changed = desired xor applied
        padButtonMap.forEach { (bit, button) ->
            val mask = 1 shl bit
            if (changed and mask == 0) return@forEach
            val pressed = desired and mask == 0
            runCatching {
                EmuCoreNative.onGamePadEvent(
                    EmuCoreNative.TOUCHSCREEN_DEVICE,
                    button,
                    if (pressed) EmuCoreNative.ButtonState.PRESSED
                    else EmuCoreNative.ButtonState.RELEASED
                )
            }
        }
    }

    private fun flushPadAnalog(port: Int) {
        if (!running) return
        val desired = desiredPadAnalog.get(port)
        val applied = appliedPadAnalog.getAndSet(port, desired)
        if (desired == applied) return
        val (lx, ly, rx, ry) = unpackAnalog(desired)
        runCatching {
            EmuCoreNative.onGamePadMoveEvent(
                EmuCoreNative.TOUCHSCREEN_DEVICE,
                EmuCoreNative.ButtonType.STICK_LEFT,
                axisValue(lx),
                axisValue(ly)
            )
            EmuCoreNative.onGamePadMoveEvent(
                EmuCoreNative.TOUCHSCREEN_DEVICE,
                EmuCoreNative.ButtonType.STICK_C,
                axisValue(rx),
                axisValue(ry)
            )
        }
    }

    private fun resetPadState() {
        for (port in 0..1) {
            desiredPadButtons.set(port, 0xFFFF)
            appliedPadButtons.set(port, 0xFFFF)
            desiredPadAnalog.set(port, 0x80808080.toInt())
            appliedPadAnalog.set(port, 0x80808080.toInt())
        }
    }

    // ---------------------------------------------------------------------
    // Save states
    // ---------------------------------------------------------------------

    fun saveState(slot: Int): Boolean {
        if (!running) return false
        return runCatching {
            EmuCoreNative.saveState(slot)
            true
        }.getOrDefault(false)
    }

    fun loadState(slot: Int): Boolean {
        if (!running) return false
        return runCatching {
            EmuCoreNative.loadState(slot)
            attachSurface(surface, surfaceWidth, surfaceHeight)
            true
        }.getOrDefault(false)
    }

    fun availableSaveStateSlots(): List<Int> = runCatching {
        EmuCoreNative.getSavestateInfo()?.map { it.slot }?.sorted().orEmpty()
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------------
    // Cheats
    // ---------------------------------------------------------------------

    fun loadCheats(path: String) {
        pendingCheatTitleId = EmuCoreNative.getRunningTitleId()
        if (pendingCheatTitleId == 0L) return
        runCatching { CheatEngine.loadCheatFile(pendingCheatTitleId) }
            .onFailure { Log.w(TAG, "Unable to load the core cheat file", it) }
    }

    fun clearCheats() {
        val titleId = if (pendingCheatTitleId != 0L) pendingCheatTitleId
        else EmuCoreNative.getRunningTitleId()
        if (titleId == 0L) return
        runCatching {
            CheatEngine.getCheats()?.forEachIndexed { index, _ -> CheatEngine.removeCheat(index) }
            CheatEngine.saveCheatFile(titleId)
        }.onFailure { Log.w(TAG, "Unable to clear the core cheat file", it) }
    }

    fun reloadCheats() {
        if (pendingCheatTitleId != 0L) {
            runCatching { CheatEngine.loadCheatFile(pendingCheatTitleId) }
        }
    }

    fun coreCheats(): Array<Cheat>? = runCatching {
        if (EmuCoreNative.isRunning()) CheatEngine.getCheats() else null
    }.getOrNull()

    fun saveCoreCheats() {
        val titleId = EmuCoreNative.getRunningTitleId()
        if (titleId == 0L) return
        runCatching { CheatEngine.saveCheatFile(titleId) }
    }

    // ---------------------------------------------------------------------
    // Audio / performance
    // ---------------------------------------------------------------------

    fun setAudioGain(volume: Int, muted: Boolean) {
        CoreOptionStore.set("volume", volume.toString())
        CoreOptionStore.set("audio_muted", muted.toString())
    }

    fun setTimeControl(mode: Int) {
        // 0 normal, 1 fast forward, 2 rewind (the core has no rewind).
        if (!running) return
        val speed = if (mode == 1) CoreOptionStore.optional("turbo_limit")?.toDoubleOrNull() ?: 2.0
        else 0.0
        runCatching {
            if (mode == 1) EmuCoreNative.setTemporaryFrameLimit(speed)
            else EmuCoreNative.disableTemporaryFrameLimit()
        }
    }

    fun setPerformanceMetricsEnabled(visible: Boolean, detailed: Boolean) {
        performanceMetricsEnabled = visible
        detailedPerformanceMetrics = visible && detailed
        if (!visible) performanceMetricsSnapshot = null
        CoreOptionStore.set("performance_overlay_enable", visible.toString())
    }

    fun performanceMetricsSnapshot(): String? {
        if (!performanceMetricsEnabled || !running) return null
        val stats = runCatching { EmuCoreNative.getPerfStats() }.getOrNull() ?: return null
        if (stats.size < 9) return null
        val fps = stats[0]
        val speed = stats[3]
        val frameTime = stats[2]
        val overlay = buildString {
            append(String.format(Locale.US, "FPS:%.1f | Speed:%.1f%%", fps, speed))
            if (detailedPerformanceMetrics) {
                append('\n').append("Frame:").append(
                    String.format(Locale.US, "%.1f ms", frameTime)
                )
            }
        }
        performanceMetricsSnapshot = String.format(Locale.US, "%.3f\n%.3f\n%s", fps, speed, overlay)
        return performanceMetricsSnapshot
    }

    fun diagnostics(): String = buildString {
        append("{\"core\":\"emucored\",\"running\":").append(EmuCoreNative.isRunning())
        append(",\"titleId\":").append(EmuCoreNative.getRunningTitleId())
        append(",\"renderer\":").append(RendererDefaults.coreRendererName(requestedRenderer))
        append('}')
    }

    fun gpuBackendSubmissions(): Long = 0L

    // ---------------------------------------------------------------------
    // Core options (config.ini values the core re-reads on demand)
    // ---------------------------------------------------------------------

    fun setCoreOption(key: String, value: String) {
        CoreOptionStore.set(key, value)
    }

    fun applyCoreOption(key: String, value: String) {
        CoreOptionStore.set(key, value)
    }

    fun coreOptionValue(key: String): String? = CoreOptionStore.value(key)

    fun setCoreUserDirectory(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { EmuCoreNative.setUserDirectory(path) }
    }

    fun setFrameSkip(frames: Int) {
        CoreOptionStore.set("frame_skip", frames.coerceIn(0, 4).toString())
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun packedAnalog(lx: Int, ly: Int, rx: Int, ry: Int): Int =
        lx.coerceIn(0, 255) or
            (ly.coerceIn(0, 255) shl 8) or
            (rx.coerceIn(0, 255) shl 16) or
            (ry.coerceIn(0, 255) shl 24)

    private fun unpackAnalog(packed: Int): IntArray = intArrayOf(
        packed and 0xFF,
        (packed shr 8) and 0xFF,
        (packed shr 16) and 0xFF,
        (packed shr 24) and 0xFF
    )

    /** 0..255 unsigned axis value the core expects (-1..1 float). */
    private fun axisValue(raw: Int): Float = ((raw.coerceIn(0, 255) - 128) / 127f)
}
