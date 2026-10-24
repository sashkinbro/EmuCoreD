package com.sbro.emucored.core.platform.session

import android.content.Context
import android.view.Surface
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreId
import java.io.File

/** Original identity never changes when SAF materializes an image into cache. */
data class EngineLaunchRequest(
    val context: Context,
    val platform: ConsolePlatform,
    val launchPath: String,
    val identityPath: String,
    val userDirectory: File,
    val options: Map<String, String> = emptyMap(),
    val startPaused: Boolean = false,
    val sourceDisplayName: String = File(launchPath).name
)

sealed interface EngineStartResult {
    data object Started : EngineStartResult
    data class Failed(val reason: String) : EngineStartResult
}

enum class EngineButton {
    A, B, X, Y, START, SELECT, L, R, L2, R2,
    UP, DOWN, LEFT, RIGHT, Z, C, HOME, PLUS, MINUS, ONE, TWO
}

/**
 * One adapter owns every worker, audio stream and native window it creates.
 * Blocking core work runs off the UI thread. State results acknowledge completed
 * I/O; accepting a queued native operation alone is not success.
 */
interface CoreSessionEngine {
    val coreId: CoreId
    val isRunning: Boolean
    val isPaused: Boolean
    suspend fun start(request: EngineLaunchRequest): EngineStartResult
    suspend fun pause()
    suspend fun resume()
    fun requestStop()
    suspend fun stop()
    fun attachSurface(surface: Surface?, width: Int, height: Int)
    fun setButton(player: Int, button: EngineButton, pressed: Boolean)
    fun setAnalog(player: Int, x: Float, y: Float, secondary: Boolean = false): Boolean = false
    fun setTouch(x: Int, y: Int, pressed: Boolean): Boolean = false
    fun setSpeed(multiplier: Float): Boolean
    suspend fun saveState(target: File): Boolean
    suspend fun loadState(source: File): Boolean
    suspend fun applyOption(key: String, value: String): Boolean
    suspend fun replaceCheats(codes: List<CoreCheatCode>): Boolean = codes.isEmpty()
    fun frameCount(): Long
}
