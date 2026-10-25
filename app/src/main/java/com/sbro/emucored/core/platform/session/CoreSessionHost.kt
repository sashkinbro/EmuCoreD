// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.platform.session

import android.view.Surface
import com.sbro.emucored.core.platform.ConsolePlatform
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Chooses a fresh engine; unavailable consoles never fall through to another core. */
fun interface CoreEngineFactory {
    fun create(platform: ConsolePlatform): CoreSessionEngine?
}

/**
 * The frontend owns one guest at a time. A replacement waits for accepted state
 * I/O and complete teardown. UI callbacks keep a Session for their own lifetime,
 * so delayed input, state requests and stop calls cannot target the next game.
 */
class CoreSessionHost(private val factory: CoreEngineFactory = AndroidCoreEngineFactory) {
    private val lifecycle = Mutex()
    private val presentationLock = Any()
    @Volatile private var current: Session? = null
    @Volatile private var presentation = Presentation(null, 0, 0)
    val session: Session? get() = current

    suspend fun start(request: EngineLaunchRequest): EngineStartResult =
        start(request.platform) { it.start(request) }

    /** Launch preparation can use the selected engine without publishing it to UI callers. */
    suspend fun start(
        platform: ConsolePlatform,
        launch: suspend (CoreSessionEngine) -> EngineStartResult
    ): EngineStartResult = lifecycle.withLock {
        val engine = factory.create(platform) ?: return@withLock EngineStartResult.Failed("core_unavailable")
        if (engine.coreId != platform.core) return@withLock EngineStartResult.Failed("core_platform_mismatch")
        if (current?.engine === engine) return@withLock EngineStartResult.Failed("core_session_busy")
        stopLocked()
        val next = Session(platform, engine)
        current = next
        val result = try {
            synchronized(presentationLock) {
                val window = presentation
                engine.attachSurface(window.surface, window.width, window.height)
            }
            launch(engine).also { currentCoroutineContext().ensureActive() }
        } catch (error: Throwable) {
            withContext(NonCancellable) { stopLocked() }
            throw error
        }
        if (result == EngineStartResult.Started && !next.closing) {
            next.ready = true
            result
        } else {
            withContext(NonCancellable) { stopLocked() }
            if (result == EngineStartResult.Started) EngineStartResult.Failed("core_start_stopped") else result
        }
    }

    /** Remember the latest window even between games, before an engine is created. */
    fun attachSurface(surface: Surface?, width: Int, height: Int) {
        synchronized(presentationLock) {
            presentation = Presentation(surface, width, height)
            current?.attachSurface(surface, width, height)
        }
    }

    fun requestStop() { current?.requestStop() }

    suspend fun stop() = lifecycle.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val old = current ?: return
        old.closing = true
        withContext(NonCancellable) {
            old.engine.requestStop()
            old.engine.stop()
            old.engine.attachSurface(null, 0, 0)
            // A throwing teardown retains ownership and blocks the next boot.
            if (current === old) current = null
        }
    }

    private data class Presentation(val surface: Surface?, val width: Int, val height: Int)

    inner class Session internal constructor(val platform: ConsolePlatform, internal val engine: CoreSessionEngine) {
        @Volatile internal var closing = false
        @Volatile internal var ready = false
        private val ownsGuest: Boolean get() = current === this && !closing
        val isRunning: Boolean get() = ownsGuest && ready && engine.isRunning
        val isPaused: Boolean get() = ownsGuest && ready && engine.isPaused

        fun attachSurface(surface: Surface?, width: Int, height: Int) {
            if (ownsGuest) engine.attachSurface(surface, width, height)
        }
        fun setButton(player: Int, button: EngineButton, pressed: Boolean) {
            if (ownsGuest && ready) engine.setButton(player, button, pressed)
        }
        fun setAnalog(player: Int, x: Float, y: Float, secondary: Boolean = false): Boolean =
            ownsGuest && ready && engine.setAnalog(player, x, y, secondary)
        fun setTouch(x: Int, y: Int, pressed: Boolean): Boolean =
            ownsGuest && ready && engine.setTouch(x, y, pressed)
        fun setSpeed(multiplier: Float): Boolean = ownsGuest && ready && engine.setSpeed(multiplier)
        fun frameCount(): Long = if (ownsGuest && ready) engine.frameCount() else 0L
        fun performanceSnapshot(nowMillis: Long): CorePerformanceSnapshot? =
            if (ownsGuest && ready) engine.performanceSnapshot(nowMillis) else null
        fun requestStop() {
            if (!ownsGuest) return
            closing = true
            engine.requestStop()
        }
        suspend fun pause() = command(Unit) { engine.pause() }
        suspend fun resume() = command(Unit) { engine.resume() }
        suspend fun saveState(target: File): Boolean = command(false) { engine.saveState(target) }
        suspend fun loadState(source: File): Boolean = command(false) { engine.loadState(source) }
        suspend fun applyOption(key: String, value: String): Boolean = command(false) { engine.applyOption(key, value) }
        suspend fun replaceCheats(codes: List<CoreCheatCode>): Boolean = command(false) { engine.replaceCheats(codes) }
        suspend fun stop() = lifecycle.withLock { if (current === this) stopLocked() }

        private suspend fun <T> command(unavailable: T, action: suspend () -> T): T =
            lifecycle.withLock { if (ownsGuest && ready) action() else unavailable }
    }
}
