// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EmuCoreD's app-facing facade over the core bridge.
 *
 * Everything the UI and the gamepad/overlay layers need goes through here:
 * session control, save states, input, audio level and crash context. There is
 * no libretro session, no PCM pump and no generic option table — the core owns
 * its own threads, renderer and audio output.
 */
object NativeApp {

    private const val TAG = "NativeApp"

    @JvmStatic
    val hasNativeCore: Boolean = EmuCoreNative.isLoaded

    @JvmStatic
    val loadedCoreLibraryName: String = "emucore_core"

    private var contextRef: WeakReference<Context>? = null
    private var dataRootOverride: String? = null

    @Volatile private var currentGamePath: String = ""
    // The path the user actually launched, used to name save-state files so
    // the writer and every listing agree even when the core needs a prepared
    // (materialized) launch path.
    @Volatile private var saveStateIdentityPath: String? = null
    private val padButtons = intArrayOf(0xFFFF, 0xFFFF)
    private val derivedDpadButtons = intArrayOf(0xFFFF, 0xFFFF)
    private val padAnalogHalfAxes = Array(2) { IntArray(8) }
    // Keyboard and mouse keep their own active-low masks and analog axes. The core
    // receives the merged state, so releasing one source cannot clear another.
    private val padSourceButtons = Array(2) {
        IntArray(KEYBOARD_MOUSE_SOURCE_COUNT) { 0xFFFF }
    }
    private val padSourceAnalogHalfAxes = Array(2) {
        Array(KEYBOARD_MOUSE_SOURCE_COUNT) { IntArray(8) }
    }
    private val sourceTimeControlHeld = Array(2) {
        Array(KEYBOARD_MOUSE_SOURCE_COUNT) { BooleanArray(2) }
    }
    private val combinedAnalogHalfAxes = IntArray(8)
    private val timeControlHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeControlButtons = Array(2) { Array(4) { HoldButton() } }
    private val timeControlTapPulse = Array(2) { BooleanArray(2) }
    private val _timeControlMode = MutableStateFlow(0)
    val timeControlMode: StateFlow<Int> = _timeControlMode.asStateFlow()

    @JvmStatic fun initialize(path: String, apiVer: Int) = Unit

    @JvmStatic fun reloadDataRoot(path: String) { dataRootOverride = path.takeIf(String::isNotBlank) }

    @JvmStatic fun setSaveStateIdentityPath(path: String?) {
        saveStateIdentityPath = path?.takeIf(String::isNotBlank)
    }

    @JvmStatic fun setSystemCaBundlePath(path: String) = Unit

    @JvmStatic fun getGameTitle(path: String): String? {
        val fallback = if (path.startsWith("content://")) {
            contextRef?.get()?.let { DocumentPathResolver.getDisplayName(it, path) }
                ?.substringBeforeLast('.')
        } else {
            File(path).nameWithoutExtension
        }.orEmpty().takeIf(String::isNotBlank)
        contextRef?.get()?.let { context ->
            N3dsGameMetadataReader.read(context, path)?.let { metadata ->
                val title = metadata.title ?: fallback
                val serial = metadata.serial
                if (!serial.isNullOrBlank()) return "${title.orEmpty()}|$serial|$serial"
                if (!title.isNullOrBlank()) return title
            }
        }
        return fallback
    }

    @JvmStatic fun setPerformanceMetricsEnabled(visible: Boolean, detailed: Boolean) {
        CoreRuntime.setPerformanceMetricsEnabled(visible, detailed)
    }

    @JvmStatic fun getPerformanceMetricsSnapshot(): String? = CoreRuntime.performanceMetricsSnapshot()

    @JvmStatic fun getDisplayDrawRect(): FloatArray? = CoreRuntime.displayRect()

    @JvmStatic fun getCoreName(): String? = "Azahar"

    @JvmStatic fun getCoreVersion(): String? =
        CoreRuntime.coreVersionString().takeIf { it.isNotBlank() }

    @JvmStatic fun setAudioOutputGain(volume: Int, muted: Boolean) =
        CoreRuntime.setAudioGain(volume, muted)

    @JvmStatic fun setAudioOutputLatencyMs(milliseconds: Int) {
        CoreOptionStore.set("audio_output_latency_ms", milliseconds.toString())
    }

    @JvmStatic fun setAudioLowLatency(enabled: Boolean) {
        CoreOptionStore.set("audio_low_latency", enabled.toString())
    }

    @JvmStatic fun setRewindEnabled(enabled: Boolean) {
        CoreOptionStore.set("rewind_enabled", enabled.toString())
    }

    @JvmStatic @Synchronized fun setPadButton(padIndex: Int, index: Int, range: Int, pressed: Boolean) {
        if (padIndex !in 0..1) return
        val n64Start = index == PAD_START &&
            com.sbro.emucored.core.platform.session.AdditionalConsoleSession.platform ==
            com.sbro.emucored.core.platform.ConsolePlatform.N64
        if ((!n64Start && (index == PAD_START || index == PAD_SELECT)) ||
            index == PAD_FAST_FORWARD || index == PAD_REWIND) {
            handleTimeControlHold(padIndex, index, pressed)
            return
        }
        val halfAxis = analogHalfAxisIndex(index)
        if (halfAxis != null) {
            padAnalogHalfAxes[padIndex][halfAxis] = if (pressed) range.coerceIn(0, 255) else 0
            dispatchPadAnalog(padIndex)
            return
        }
        val bit = n3dsButtonBit(index) ?: return
        padButtons[padIndex] = if (pressed) padButtons[padIndex] and (1 shl bit).inv()
        else padButtons[padIndex] or (1 shl bit)
        CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
    }

    @JvmStatic @Synchronized fun setPadButtonFromSource(
        padIndex: Int,
        index: Int,
        range: Int,
        pressed: Boolean,
        source: Int
    ) {
        if (padIndex !in 0..1) return
        val sourceSlot = keyboardMouseSourceSlot(source)
        if (sourceSlot == null) {
            setPadButton(padIndex, index, range, pressed)
            return
        }
        if (index == PAD_FAST_FORWARD || index == PAD_REWIND) {
            val controlSlot = if (index == PAD_FAST_FORWARD) 0 else 1
            val held = sourceTimeControlHeld[padIndex][sourceSlot]
            if (held[controlSlot] == pressed) return
            held[controlSlot] = pressed
            updateTimeControl()
            return
        }
        val halfAxis = analogHalfAxisIndex(index)
        if (halfAxis != null) {
            val axes = padSourceAnalogHalfAxes[padIndex][sourceSlot]
            val value = if (pressed) range.coerceIn(0, 255) else 0
            if (axes[halfAxis] == value) return
            axes[halfAxis] = value
            dispatchPadAnalog(padIndex)
            return
        }
        val bit = n3dsButtonBit(index) ?: return
        val mask = 1 shl bit
        val buttons = padSourceButtons[padIndex][sourceSlot]
        val updated = if (pressed) buttons and mask.inv() else buttons or mask
        if (updated == buttons) return
        padSourceButtons[padIndex][sourceSlot] = updated
        CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
    }

    @JvmStatic fun setTouchState(x: Int, y: Int, pressed: Boolean) =
        CoreRuntime.setTouchState(x, y, pressed)

    @JvmStatic fun onHostKeyEvent(keyCode: Int, pressed: Boolean) {
        setPadButton(0, keyCode, 0, pressed)
    }

    @JvmStatic fun onHostMousePosition(x: Float, y: Float) = Unit
    @JvmStatic fun onHostMouseButton(button: Int, pressed: Boolean) = Unit
    @JvmStatic fun onHostMouseWheel(deltaX: Float, deltaY: Float) = Unit

    @JvmStatic fun resetKeyStatus() { resetPadState(0); resetPadState(1) }

    @JvmStatic @Synchronized fun resetPadState(padIndex: Int) {
        if (padIndex !in 0..1) return
        timeControlButtons[padIndex].forEach { it.reset() }
        timeControlTapPulse[padIndex].fill(false)
        sourceTimeControlHeld[padIndex].forEach { it.fill(false) }
        updateTimeControl()
        padButtons[padIndex] = 0xFFFF
        derivedDpadButtons[padIndex] = 0xFFFF
        padAnalogHalfAxes[padIndex].fill(0)
        padSourceButtons[padIndex].fill(0xFFFF)
        padSourceAnalogHalfAxes[padIndex].forEach { it.fill(0) }
        CoreRuntime.setPadButtons(padIndex, 0xFFFF)
        CoreRuntime.setPadAnalog(padIndex, 128, 128, 128, 128)
    }

    @JvmStatic fun setAspectRatio(type: Int) { CoreRuntime.setDisplayAspectRatio(type) }

    @JvmStatic fun setSetting(section: String, key: String, type: String, value: String): Boolean {
        CoreOptionStore.set(key, value)
        return true
    }

    @JvmStatic fun getSetting(section: String, key: String, type: String): String? =
        CoreOptionStore.value(key)

    @JvmStatic fun setCoreOption(key: String, value: String) {
        CoreRuntime.setCoreOption(key, value)
    }

    @JvmStatic fun getCoreOption(key: String): String? = CoreRuntime.coreOptionValue(key)

    @JvmStatic fun applyCoreOption(key: String, value: String) {
        CoreRuntime.applyCoreOption(key, value)
    }

    @JvmStatic fun setFrameSkip(frames: Int) {
        CoreRuntime.setFrameSkip(frames)
    }

    @JvmStatic fun setDisplayCrop(crop: com.sbro.emucored.data.DisplayCrop) {
        CoreOptionStore.set("display_crop_left", crop.sanitized().let { it.left }.toString())
    }

    @JvmStatic fun setFrameLimitEnabled(enabled: Boolean) {
        CoreOptionStore.set("use_frame_limit", enabled.toString())
    }

    @JvmStatic fun reloadPatches() = CoreRuntime.reloadCheats()

    @JvmStatic fun loadCheats(path: String) = CoreRuntime.loadCheats(path)

    @JvmStatic fun clearCheats() = CoreRuntime.clearCheats()

    @JvmStatic fun setCoreUserDirectory(path: String?) = CoreRuntime.setCoreUserDirectory(path)

    @JvmStatic fun hasDiscMedia(): Boolean = CoreRuntime.hasDiscMedia()

    @JvmStatic fun onNativeSurfaceCreated() = Unit

    @JvmStatic fun rememberSurface(surface: Surface, width: Int, height: Int) =
        CoreRuntime.rememberSurface(surface, width, height)

    @JvmStatic fun onNativeSurfaceChanged(surface: Surface, width: Int, height: Int) =
        CoreRuntime.attachSurface(surface, width, height)

    @JvmStatic fun onNativeSecondarySurfaceChanged(surface: Surface, width: Int, height: Int) =
        CoreRuntime.attachSecondarySurface(surface)

    @JvmStatic fun onNativeSecondarySurfaceDestroyed() = CoreRuntime.detachSecondarySurface()

    @JvmStatic fun hasAttachedSurface(surface: Surface, width: Int, height: Int): Boolean =
        CoreRuntime.hasAttachedSurface(surface, width, height)

    @JvmStatic fun onNativeSurfaceDestroyed() = CoreRuntime.detachSurface()

    @JvmStatic fun runVMThread(path: String): Boolean {
        currentGamePath = path
        return CoreRuntime.start(path)
    }

    @JvmStatic fun restartRenderer(renderer: Int): Boolean =
        CoreRuntime.restartWithRenderer(renderer)

    @JvmStatic fun pause() = CoreRuntime.pause()

    @JvmStatic fun resume() = CoreRuntime.resume()

    @JvmStatic fun shutdown() = CoreRuntime.shutdown()

    @JvmStatic fun requestShutdown() = CoreRuntime.requestShutdown()

    @JvmStatic fun hasValidVm(): Boolean = CoreRuntime.isRunning()

    @JvmStatic fun hasOwnedVm(): Boolean = CoreRuntime.hasSession()

    val runtimeFailure get() = CoreRuntime.failure

    @JvmStatic fun getGameSerial(): String? = extractTitleIdentity(saveStatePathSource())

    internal fun saveStatePathSource(): String =
        saveStateIdentityPath?.takeIf(String::isNotBlank) ?: currentGamePath

    @JvmStatic fun saveStateToSlot(slot: Int): Boolean = CoreRuntime.saveState(slot)

    @JvmStatic fun loadStateFromSlot(slot: Int): Boolean {
        val identityPath = getSaveStatePathForFile(saveStatePathSource(), slot)
        val available = CoreRuntime.availableSaveStateSlots()
        if (slot in available) {
            if (CoreRuntime.loadState(slot)) return true
        }
        if (identityPath != null && File(identityPath).exists() && CoreRuntime.loadState(slot)) {
            return true
        }
        return false
    }

    @JvmStatic fun getSaveStatePathForFile(path: String, slot: Int): String? {
        if (path.isBlank()) return null
        val titleId = runCatching { EmuCoreNative.getTitleId(path) }.getOrNull() ?: return null
        if (titleId == 0L) return null
        val directory = EmulatorStorage.coreStatesDir(getContext() ?: return null)
        return File(
            directory,
            "%016X.%02d.cst".format(java.util.Locale.US, titleId, slot.coerceIn(0, 10))
        ).absolutePath
    }

    @JvmStatic fun getCurrentSaveStatePath(slot: Int): String? {
        // The running loader owns the title ID used by the native CST writer.
        // Its source URI/prepared launch path may no longer be readable here.
        val titleId = runCatching { EmuCoreNative.getRunningTitleId() }.getOrDefault(0L)
        if (titleId != 0L) {
            val directory = EmulatorStorage.coreStatesDir(getContext() ?: return null)
            return File(directory, "%016X.%02d.cst".format(
                java.util.Locale.US, titleId, slot.coerceIn(0, 10)
            )).absolutePath
        }
        return getSaveStatePathForFile(saveStatePathSource(), slot)
    }

    @JvmStatic fun onPadVibration(index: Int, largeMotor: Float, smallMotor: Float) {
        GamepadManager.onPadVibration(index, largeMotor, smallMotor)
    }

    @JvmStatic fun setCrashContextString(key: String, value: String?) {
        CrashLogger.logContext(key, value)
    }

    @JvmStatic fun setCrashContextInt(key: String, value: Int) {
        CrashLogger.logContext(key, value)
    }

    @JvmStatic fun setCrashContextBool(key: String, value: Boolean) {
        CrashLogger.logContext(key, value)
    }

    @JvmStatic fun logCrashBreadcrumb(message: String) {
        Log.i(TAG, message)
        CrashLogger.logInfo("Native", message)
    }

    @JvmStatic fun openContentUri(uriString: String): Int {
        val context = getContext() ?: return -1
        return try {
            val sanitized = uriString.substringBefore('|')
            val descriptor = context.contentResolver.openFileDescriptor(sanitized.toUri(), "r")
            descriptor?.detachFd() ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /** Root directory that stores native core data such as caches and settings. */
    @JvmStatic
    fun dataRoot(context: Context): File {
        val override = dataRootOverride
        if (!override.isNullOrBlank() && File(override).canWrite()) return File(override)
        return File(context.filesDir, "azahar").apply { mkdirs() }
    }

    @JvmStatic
    fun initializeOnce(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        CoreRuntime.initialize(context.applicationContext)
    }

    @JvmStatic
    fun getContext(): Context? = contextRef?.get()

    private fun handleTimeControlHold(padIndex: Int, index: Int, pressed: Boolean) {
        val slot = when (index) {
            PAD_START -> 0
            PAD_SELECT -> 1
            PAD_FAST_FORWARD -> 2
            else -> 3
        }
        val button = timeControlButtons[padIndex][slot]
        if (button.pressed == pressed) return
        if (pressed) {
            val generation = button.press() ?: return
            if (slot < 2 && timeControlTapPulse[padIndex][slot]) {
                timeControlTapPulse[padIndex][slot] = false
                val bit = n3dsButtonBit(index) ?: return
                padButtons[padIndex] = padButtons[padIndex] or (1 shl bit)
                CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
            }
            timeControlHandler.postDelayed({
                synchronized(this) {
                    if (button.activate(generation)) {
                        updateTimeControl()
                    }
                }
            }, TIME_CONTROL_HOLD_MS)
        } else {
            val wasTap = button.release()
            val generation = button.generation
            updateTimeControl()
            if (wasTap && slot < 2) {
                val bit = n3dsButtonBit(index) ?: return
                timeControlTapPulse[padIndex][slot] = true
                padButtons[padIndex] = padButtons[padIndex] and (1 shl bit).inv()
                CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
                timeControlHandler.postDelayed({
                    synchronized(this) {
                        if (button.generation == generation) {
                            timeControlTapPulse[padIndex][slot] = false
                            padButtons[padIndex] = padButtons[padIndex] or (1 shl bit)
                            CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
                        }
                    }
                }, TIME_CONTROL_TAP_MS)
            }
        }
    }

    private fun updateTimeControl() {
        val rewind = (0..1).any { pad ->
            timeControlButtons[pad][1].active ||
                timeControlButtons[pad][3].active ||
                sourceTimeControlHeld[pad].any { it[1] }
        }
        val fastForward = (0..1).any { pad ->
            timeControlButtons[pad][0].active ||
                timeControlButtons[pad][2].active ||
                sourceTimeControlHeld[pad].any { it[0] }
        }
        val mode = if (rewind) 2 else if (fastForward) 1 else 0
        _timeControlMode.value = mode
        CoreRuntime.setTimeControl(mode)
    }

    private fun keyboardMouseSourceSlot(source: Int): Int? = when (source) {
        KeyboardMouseBindings.PAD_SOURCE_KEYBOARD -> 0
        KeyboardMouseBindings.PAD_SOURCE_MOUSE -> 1
        else -> null
    }

    /**
     * Maps Android gamepad keycodes to the shared Nintendo 3DS active-low pad
     * bitmask documented in the core bridge. Face buttons follow the physical
     * position (bottom/right/left/top), so an Xbox-style pad keeps its layout.
     */
    private fun n3dsButtonBit(index: Int): Int? = when (index) {
        109 -> 0  // Select
        104 -> 1  // L2 -> ZL
        105 -> 2  // R2 -> ZR
        108 -> 3  // Start
        19 -> 4   // Up
        22 -> 5   // Right
        20 -> 6   // Down
        21 -> 7   // Left
        106 -> 8  // L3 -> Home / swap screens
        102 -> 10 // L1 -> L
        103 -> 11 // R1 -> R
        100 -> 12 // Y (top) -> X
        97 -> 13  // B (right) -> A
        96 -> 14  // A (bottom) -> B
        99 -> 15  // X (left) -> Y
        else -> null
    }

    private fun analogHalfAxisIndex(index: Int): Int? = when (index) {
        110 -> 0 // Left stick up
        111 -> 1 // Left stick right
        112 -> 2 // Left stick down
        113 -> 3 // Left stick left
        120 -> 4 // Right stick up
        121 -> 5 // Right stick right
        122 -> 6 // Right stick down
        123 -> 7 // Right stick left
        else -> null
    }

    private fun mergeHalfAxes(negative: Int, positive: Int): Int {
        val delta = positive - negative
        return if (delta >= 0) 128 + ((delta * 127 + 127) / 255)
        else 128 - (((-delta * 128) + 127) / 255)
    }

    private fun dispatchPadAnalog(padIndex: Int) {
        val axes = combinedAnalogHalfAxes
        for (axis in 0 until axes.size) {
            var value = padAnalogHalfAxes[padIndex][axis]
            for (sourceSlot in 0 until KEYBOARD_MOUSE_SOURCE_COUNT) {
                value = maxOf(value, padSourceAnalogHalfAxes[padIndex][sourceSlot][axis])
            }
            axes[axis] = value
        }
        val lx = mergeHalfAxes(axes[3], axes[1])
        val ly = mergeHalfAxes(axes[0], axes[2])
        val rx = mergeHalfAxes(axes[7], axes[5])
        val ry = mergeHalfAxes(axes[4], axes[6])
        CoreRuntime.setPadAnalog(padIndex, lx, ly, rx, ry)
        updateDerivedDpadButtons(padIndex, lx, ly)
    }

    /**
     * Mirrors the "Use Analog Sticks for D-Pad in Digital Mode" option in the
     * app's input layer: the 3DS has a separate Circle Pad and D-pad, so the
     * left stick drives the D-pad only when the user opts in.
     */
    private fun updateDerivedDpadButtons(padIndex: Int, lx: Int, ly: Int) {
        var derived = DPAD_ALL_RELEASED
        if (isAnalogDpadInDigitalModeEnabled()) {
            when {
                ly < 128 - ANALOG_DPAD_THRESHOLD -> derived = derived and (1 shl N3DS_BUTTON_UP).inv()
                ly > 128 + ANALOG_DPAD_THRESHOLD -> derived = derived and (1 shl N3DS_BUTTON_DOWN).inv()
            }
            when {
                lx < 128 - ANALOG_DPAD_THRESHOLD -> derived = derived and (1 shl N3DS_BUTTON_LEFT).inv()
                lx > 128 + ANALOG_DPAD_THRESHOLD -> derived = derived and (1 shl N3DS_BUTTON_RIGHT).inv()
            }
        }
        if (derivedDpadButtons[padIndex] != derived) {
            derivedDpadButtons[padIndex] = derived
            CoreRuntime.setPadButtons(padIndex, effectivePadButtons(padIndex))
        }
    }

    private fun effectivePadButtons(padIndex: Int): Int {
        var buttons = padButtons[padIndex] and derivedDpadButtons[padIndex]
        for (sourceSlot in 0 until KEYBOARD_MOUSE_SOURCE_COUNT) {
            buttons = buttons and padSourceButtons[padIndex][sourceSlot]
        }
        return buttons
    }

    private fun isAnalogDpadInDigitalModeEnabled(): Boolean {
        val key = "analog_dpad_in_digital_mode"
        return CoreRuntime.coreOptionValue(key)?.toBooleanStrictOrNull() ?: false
    }

    /**
     * Save-state identity for a 3DS title: the 16 hex digit title ID from the
     * SMDH (or the filename), falling back to the CTR product code.
     */
    private fun extractTitleIdentity(value: String): String? {
        val embedded = contextRef?.get()?.let { context ->
            runCatching { N3dsGameMetadataReader.read(context, value)?.serial }.getOrNull()
        }
        val normalized = (embedded ?: value).uppercase(java.util.Locale.ROOT)
        Regex("\\b[0-9A-F]{16}\\b").find(normalized)?.let { return it.value }
        return Regex("\\bCTR-[PN]-[A-Z0-9]{4}\\b").find(normalized)?.value
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private const val KEYBOARD_MOUSE_SOURCE_COUNT = 2
private const val PAD_FAST_FORWARD = 126
private const val PAD_REWIND = 127
private const val PAD_START = 108
private const val PAD_SELECT = 109
private const val TIME_CONTROL_HOLD_MS = 450L
private const val TIME_CONTROL_TAP_MS = 60L
private const val DPAD_ALL_RELEASED = 0xFFFF
private const val ANALOG_DPAD_THRESHOLD = 64
private const val N3DS_BUTTON_UP = 4
private const val N3DS_BUTTON_RIGHT = 5
private const val N3DS_BUTTON_DOWN = 6
private const val N3DS_BUTTON_LEFT = 7
