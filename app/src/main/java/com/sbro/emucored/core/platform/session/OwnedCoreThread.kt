package com.sbro.emucored.core.platform.session

import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native fibers and EGL contexts must stay on the thread that created them. */
internal class OwnedCoreThread(
    private val name: String,
    private val runFrame: () -> Boolean,
    private val frameRate: () -> Double,
    private val close: () -> Unit
) {
    private val lifecycle = Mutex()
    private var dispatcher: ExecutorCoroutineDispatcher? = null
    private var scope: CoroutineScope? = null
    @Volatile private var frameJob: Job? = null
    @Volatile private var stopping = false
    // Confined to the owned lane. A cancelled launch may never enter its body.
    private var closeRequired = false
    @Volatile var isRunning = false; private set
    @Volatile var isPaused = false; private set
    @Volatile var speed = 1f
    @Volatile var frames = 0L; private set

    suspend fun start(open: () -> Boolean, startPaused: Boolean): Boolean = lifecycle.withLock {
        if (isRunning) return@withLock false
        stopLocked()
        val lane = Executors.newSingleThreadExecutor { Thread(it, name) }.asCoroutineDispatcher()
        dispatcher = lane
        val owner = CoroutineScope(SupervisorJob() + lane)
        scope = owner
        stopping = false
        try {
            val opened = withContext(lane) { closeRequired = true; open() }
            if (!opened || stopping) {
                withContext(NonCancellable + lane) { closeOwned() }
                releaseLane()
                return@withLock false
            }
            frames = 0
            isPaused = startPaused
            isRunning = true
            frameJob = owner.launch {
                try {
                    var nextFrameAt = System.nanoTime()
                    while (isActive && !stopping) {
                        if (isPaused) {
                            delay(5)
                            nextFrameAt = System.nanoTime()
                            continue
                        }
                        if (!runFrame()) break
                        frames++
                        val rate = frameRate().takeIf { it.isFinite() && it > 0 } ?: 60.0
                        val period = (1_000_000_000.0 / (rate * speed)).toLong().coerceAtLeast(1L)
                        nextFrameAt += period
                        val now = System.nanoTime()
                        if (now - nextFrameAt > period * 4) nextFrameAt = now
                        // delay() is millisecond-granular and rounds up, which
                        // capped 59.73 Hz cores at ~93%. Sleep most of the
                        // frame cooperatively (so pause/stop commands run), then
                        // park/spin only the final ~2 ms against an absolute
                        // deadline. This keeps 100% speed without stalling the
                        // owned command lane.
                        val remaining = nextFrameAt - System.nanoTime()
                        val sleepMs = remaining / 1_000_000L - 2L
                        if (sleepMs > 0L) delay(sleepMs)
                        while (nextFrameAt > System.nanoTime() && isActive && !stopping) {
                            val wait = nextFrameAt - System.nanoTime()
                            if (wait <= 0L) break
                            LockSupport.parkNanos(wait)
                        }
                    }
                } finally {
                    closeOwned()
                    isRunning = false
                    isPaused = false
                }
            }
            true
        } catch (error: Throwable) {
            // Open may allocate native resources before returning or throwing.
            if (frameJob == null) withContext(NonCancellable + lane) { closeOwned() }
            releaseLane()
            if (error is CancellationException) throw error
            false
        }
    }

    suspend fun <T> call(unavailable: T, action: () -> T): T = lifecycle.withLock {
        val lane = dispatcher ?: return@withLock unavailable
        if (!isRunning || stopping) return@withLock unavailable
        withContext(lane) {
            if (dispatcher !== lane || !isRunning || stopping) unavailable else action()
        }
    }

    fun post(action: () -> Unit) {
        val lane = dispatcher ?: return
        scope?.launch {
            if (dispatcher === lane && isRunning && !stopping) action()
        }
    }

    suspend fun pause(action: () -> Unit) = call(Unit) { isPaused = true; action() }
    suspend fun resume(action: () -> Unit) = call(Unit) { action(); isPaused = false }
    fun requestStop() { stopping = true; frameJob?.cancel() }
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    private suspend fun stopLocked() = withContext(NonCancellable) {
        requestStop()
        frameJob?.cancelAndJoin()
        frameJob = null
        dispatcher?.let { lane -> withContext(lane) { closeOwned() } }
        isRunning = false
        isPaused = false
        releaseLane()
    }

    private fun closeOwned() {
        if (!closeRequired) return
        closeRequired = false
        runCatching(close)
    }

    private fun releaseLane() {
        scope?.cancel()
        scope = null
        dispatcher?.close()
        dispatcher = null
    }
}
