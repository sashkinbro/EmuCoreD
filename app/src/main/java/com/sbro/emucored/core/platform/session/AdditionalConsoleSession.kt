// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.platform.session

import android.view.Surface
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.ConsoleImageAccess
import com.sbro.emucored.core.CoreRuntime
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-facing owner for the additional engines while DS/3DS keep their established bridge. */
object AdditionalConsoleSession {
    private val host = CoreSessionHost()
    private val lifecycle = Mutex()
    private var image: ConsoleImageAccess.Image? = null
    private val pad = ConsolePadState()
    @Volatile private var startingJob: Job? = null
    @Volatile private var request: EngineLaunchRequest? = null
    val isActive: Boolean get() = startingJob != null || host.session != null
    val isRunning: Boolean get() = host.session?.isRunning == true
    val platform: ConsolePlatform? get() = host.session?.platform ?: request?.platform
    val session: CoreSessionHost.Session? get() = host.session

    suspend fun start(launch: EngineLaunchRequest): Boolean = lifecycle.withLock {
        if (isActive) return@withLock false
        startingJob = currentCoroutineContext()[Job]
        request = launch
        pad.reset()
        try {
            val prepared = ConsoleImageAccess.prepare(launch.context, launch.launchPath) ?: run {
                releaseImage(); return@withLock false
            }
            image = prepared
            request = launch.copy(launchPath = prepared.path, sourceDisplayName = prepared.displayName)
            val started = host.start(request!!) == EngineStartResult.Started
            if (started) {
                host.session?.let { guest -> pad.replay(guest::setButton) }
                CoreRuntime.replayAdditionalInput()
            } else if (host.session == null) releaseImage()
            started
        } catch (error: Throwable) {
            if (host.session == null) releaseImage()
            throw error
        } finally {
            startingJob = null
        }
    }
    fun attachSurface(surface: Surface?, width: Int, height: Int) = host.attachSurface(surface, width, height)
    suspend fun pause() { host.session?.pause() }
    suspend fun resume() { host.session?.resume() }
    fun requestStop() { startingJob?.cancel(); host.requestStop() }
    suspend fun stop() {
        requestStop()
        lifecycle.withLock { withContext(NonCancellable) { host.stop(); releaseImage() } }
    }
    private fun releaseImage() { image?.close(); image = null; request = null }

    fun stateFile(slot: Int): File? {
        val launch = request ?: return null
        return ConsoleSessionStorage.stateFile(launch.context.filesDir, launch.platform, launch.identityPath, slot)
    }

    suspend fun saveState(slot: Int): Boolean = withContext(Dispatchers.IO) {
        val guest = host.session ?: return@withContext false
        val launch = request ?: return@withContext false
        val file = ConsoleSessionStorage.stateFile(launch.context.filesDir, launch.platform, launch.identityPath, slot)
        ConsoleStateSlotTransaction.save(file, guest::saveState) { staged ->
            JSONObject().put("platform", launch.platform.name).put("gamePath", launch.identityPath)
                .put("title", File(launch.sourceDisplayName).nameWithoutExtension)
                .put("slot", slot).put("savedAt", System.currentTimeMillis())
                .put("core", launch.platform.core.name)
                .put("sha256", com.sbro.emucored.data.SaveStateFileIntegrity.sha256(staged))
        }
    }
    suspend fun loadState(slot: Int): Boolean {
        val guest = host.session ?: return false
        return stateFile(slot)?.let { ConsoleStateSlotTransaction.load(it, guest::loadState) } ?: false
    }

    fun setPadButtons(player: Int, activeLow: Int) {
        val guest = host.session
        pad.update(player, activeLow, guest?.isRunning == true) { p, button, pressed -> guest?.setButton(p, button, pressed) }
    }
    fun setPadAnalog(player: Int, lx: Int, ly: Int, rx: Int, ry: Int) {
        val guest = host.session ?: return
        fun axis(value: Int) = ((value - 128) / 127f).coerceIn(-1f, 1f)
        guest.setAnalog(player, axis(lx), -axis(ly))
        guest.setAnalog(player, axis(rx), -axis(ry), true)
    }
    fun setTimeControl(mode: Int, fastForwardSpeed: Float) {
        host.session?.setSpeed(if (mode == 1) fastForwardSpeed.coerceIn(0.25f, 4f) else 1f)
    }

}
