package com.sbro.emucored.core.platform.session

import android.view.Surface
import java.io.File
import java.security.MessageDigest

/** Common lifecycle for framebuffer cores; all native operations use one owner. */
abstract class ThreadedFrameSessionEngine : CoreSessionEngine {
    protected var nativeHandle = 0L; private set
    @Volatile private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private val worker = OwnedCoreThread("EmuCore-frame", { nativeRunFrame(nativeHandle) },
        { nativeFrameRate(nativeHandle) }, {
            if (nativeHandle != 0L) nativeClose(nativeHandle)
            nativeHandle = 0L
        })
    final override val isRunning: Boolean get() = worker.isRunning
    final override val isPaused: Boolean get() = worker.isPaused

    final override suspend fun start(request: EngineLaunchRequest): EngineStartResult {
        if (request.platform.core != coreId) return EngineStartResult.Failed("unsupported_platform")
        var failureReason = "core_load_failed"
        val started = worker.start({
            val identity = MessageDigest.getInstance("SHA-256").digest(request.identityPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(request.userDirectory, "${request.platform.id}/$identity")
            check(directory.isDirectory || directory.mkdirs())
            nativeHandle = nativeOpen(request, directory)
            if (nativeHandle == 0L) false else {
                if (!request.options.all { (key, value) -> nativeApplyOption(nativeHandle, key, value) }) {
                    failureReason = "core_option_unsupported"
                    return@start false
                }
                nativeAttachSurface(nativeHandle, surface, width, height)
                nativeSetPaused(nativeHandle, request.startPaused)
                true
            }
        }, request.startPaused)
        return if (started) EngineStartResult.Started else EngineStartResult.Failed(failureReason)
    }
    final override suspend fun pause() = worker.pause { nativeSetPaused(nativeHandle, true) }
    final override suspend fun resume() = worker.resume { nativeSetPaused(nativeHandle, false) }
    final override fun requestStop() = worker.requestStop()
    final override suspend fun stop() = worker.stop()
    final override fun attachSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface; this.width = width; this.height = height
        worker.post { nativeAttachSurface(nativeHandle, surface, width, height) }
    }
    final override fun setButton(player: Int, button: EngineButton, pressed: Boolean) {
        worker.post { nativeSetButton(nativeHandle, player, button, pressed) }
    }
    final override fun setSpeed(multiplier: Float): Boolean {
        if (!multiplier.isFinite() || multiplier !in 0.25f..4f) return false
        worker.speed = multiplier
        return true
    }
    final override suspend fun saveState(target: File): Boolean = worker.call(false) { nativeState(nativeHandle, target, true) }
    final override suspend fun loadState(source: File): Boolean = worker.call(false) { nativeState(nativeHandle, source, false) }
    final override suspend fun applyOption(key: String, value: String): Boolean = worker.call(false) { nativeApplyOption(nativeHandle, key, value) }
    final override fun frameCount(): Long = worker.frames

    protected abstract fun nativeOpen(request: EngineLaunchRequest, directory: File): Long
    protected abstract fun nativeClose(handle: Long)
    protected abstract fun nativeRunFrame(handle: Long): Boolean
    protected abstract fun nativeFrameRate(handle: Long): Double
    protected abstract fun nativeAttachSurface(handle: Long, surface: Surface?, width: Int, height: Int)
    protected abstract fun nativeSetPaused(handle: Long, paused: Boolean)
    protected abstract fun nativeSetButton(handle: Long, player: Int, button: EngineButton, pressed: Boolean)
    protected abstract fun nativeState(handle: Long, path: File, save: Boolean): Boolean
    protected abstract fun nativeApplyOption(handle: Long, key: String, value: String): Boolean
}
