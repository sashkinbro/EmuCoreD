// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin

import android.content.Context
import android.view.Surface
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreId
import com.sbro.emucored.core.platform.session.CoreSessionEngine
import com.sbro.emucored.core.platform.session.CoreCheatCode
import com.sbro.emucored.core.platform.session.CoreCheatFormats
import com.sbro.emucored.core.platform.session.EngineButton
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import com.sbro.emucored.core.platform.session.EngineStartResult
import com.sbro.emucored.core.platform.session.NativeSessionOwnership
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One Dolphin process runtime serves either GameCube or Wii in EmuCoreD's own UI. */
class DolphinSessionEngine : CoreSessionEngine {
    data class GameIdentity(val platform:ConsolePlatform,val gameId:String,val revision:Int?,val titleId:String?)
    override val coreId = CoreId.DOLPHIN
    private val lifecycle = Mutex()
    @Volatile private var ownsRuntime = false
    @Volatile private var starting = false
    @Volatile private var sessionLease: NativeSessionOwnership.Lease? = null
    @Volatile private var wii = false
    @Volatile private var profile = "nunchuk"
    @Volatile private var surface: Surface? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    override val isRunning: Boolean get() = owned(false) { DolphinNative.status() != 0 }
    override val isPaused: Boolean get() = owned(false) { DolphinNative.status() == 2 }

    private fun <T> owned(unavailable: T, action: () -> T): T =
        ownership.call(sessionLease, unavailable) { if (ownsRuntime) action() else unavailable }

    private suspend fun <T> ownedIo(unavailable: T, action: () -> T): T {
        val lease = sessionLease
        return withContext(Dispatchers.IO) {
            ownership.call(lease, unavailable) { if (ownsRuntime) action() else unavailable }
        }
    }

    override suspend fun start(request: EngineLaunchRequest): EngineStartResult {
        var attemptedLease: NativeSessionOwnership.Lease? = null
        try {
            return startOwned(request) { attemptedLease = it }
        } catch (cancelled: CancellationException) {
            // Cancellation can be delivered only when a blocking native boot
            // returns to its caller. Release that attempt, never a later guest.
            stopLease(attemptedLease)
            throw cancelled
        }
    }

    private suspend fun startOwned(request: EngineLaunchRequest, claimed: (NativeSessionOwnership.Lease) -> Unit): EngineStartResult = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (request.platform != ConsolePlatform.GAMECUBE && request.platform != ConsolePlatform.WII)
                return@withLock EngineStartResult.Failed("dolphin_platform_unsupported")
            if (!DolphinNative.available) return@withLock EngineStartResult.Failed("dolphin_native_unavailable")
            if (!File(request.launchPath).isFile) return@withLock EngineStartResult.Failed("dolphin_file_unreadable")
            val lease = ownership.claim() ?: return@withLock EngineStartResult.Failed("dolphin_session_busy")
            claimed(lease)
            sessionLease = lease
            starting = true
            try {
                request.userDirectory.mkdirs()
                val sys = installSystemData(request.context.applicationContext)
                DolphinServices.configure(request.context.applicationContext)
                wii = request.platform == ConsolePlatform.WII
                profile = request.options["wii_profile"] ?: request.options["dolphin.wii_profile"] ?: "nunchuk"
                // The shared launch layer materializes SAF URIs. Disc identity is
                // checked by Dolphin's actual volume parser, including RVZ/WIA.
                val detected = DolphinNative.probe(request.launchPath)
                if ((detected == 1 && wii) || (detected == 2 && !wii)) {
                    return@withLock EngineStartResult.Failed("dolphin_platform_mismatch")
                }
                val options = request.options.toSortedMap()
                DolphinNative.surface(surface, width, height)
                val result = DolphinNative.start(request.launchPath, request.userDirectory.absolutePath,
                    sys.absolutePath, wii, request.startPaused, options.keys.toTypedArray(), options.values.toTypedArray())
                ownsRuntime = result == 0
                if (ownsRuntime) EngineStartResult.Started else {
                    DolphinNative.stop()
                    EngineStartResult.Failed(when (result) {
                        2 -> "dolphin_session_busy"
                        5 -> "dolphin_start_cancelled"
                        6 -> "dolphin_option_unsupported"
                        else -> "dolphin_boot_failed"
                    })
                }
            } catch (_: Exception) {
                DolphinNative.requestStop()
                DolphinNative.stop()
                EngineStartResult.Failed("dolphin_initialization_failed")
            } finally {
                starting = false
                if (!ownsRuntime) {
                    ownership.release(lease) {}
                    sessionLease = null
                }
            }
        }
    }
    override suspend fun pause() { ownedIo(false) { DolphinNative.pause(true) } }
    override suspend fun resume() { ownedIo(false) { DolphinNative.pause(false) } }
    override fun requestStop() {
        ownership.call(sessionLease, Unit) { if (ownsRuntime || starting) DolphinNative.requestStop() }
    }
    override suspend fun stop() {
        val lease = sessionLease
        stopLease(lease)
    }

    private suspend fun stopLease(lease: NativeSessionOwnership.Lease?) {
        withContext(NonCancellable + Dispatchers.IO) {
            ownership.call(lease, Unit) { if (ownsRuntime || starting) DolphinNative.requestStop() }
            lifecycle.withLock {
                ownership.release(lease) {
                    if (ownsRuntime) DolphinNative.stop()
                    ownsRuntime = false
                }
                if (sessionLease === lease) sessionLease = null
            }
        }
    }
    override fun attachSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        ownership.call(sessionLease, Unit) {
            if ((ownsRuntime || starting) && DolphinNative.available)
                DolphinNative.surface(surface, this.width, this.height)
        }
    }
    override fun setButton(player: Int, button: EngineButton, pressed: Boolean) {
        owned(Unit) { setOwnedButton(player, button, pressed) }
    }
    private fun setOwnedButton(player: Int, button: EngineButton, pressed: Boolean) {
        if (player !in 0..3) return
        val value = if (pressed) 1.0 else 0.0
        val control = if (wii) wiiButton(button, profile == "classic") else gameCubeButton(button)
        if (control >= 0) DolphinNative.control(player, control, value)
        // Digital shoulder presses also fully depress the corresponding analog trigger.
        if (!wii && button in arrayOf(EngineButton.L, EngineButton.R, EngineButton.L2, EngineButton.R2))
            DolphinNative.control(player, if (button == EngineButton.L || button == EngineButton.L2) 12 else 13, value)
        if (wii && profile == "classic" && (button == EngineButton.L || button == EngineButton.R))
            DolphinNative.control(player, if (button == EngineButton.L) 50 else 51, value)
    }
    override fun setAnalog(player: Int, x: Float, y: Float, secondary: Boolean): Boolean = owned(false) {
        setOwnedAnalog(player, x, y, secondary)
    }
    private fun setOwnedAnalog(player: Int, x: Float, y: Float, secondary: Boolean): Boolean {
        if (player !in 0..3 || !x.isFinite() || !y.isFinite()) return false
        val control = when {
            !wii -> if (secondary) 16 else 14
            profile == "classic" -> if (secondary) 54 else 52
            secondary -> 29 // Wii remote pointer; primary stick is the Nunchuk.
            profile == "nunchuk" -> 33
            else -> return false
        }
        DolphinNative.control(player, control, x.coerceIn(-1f, 1f).toDouble())
        DolphinNative.control(player, control + 1, -y.coerceIn(-1f, 1f).toDouble())
        return true
    }
    override fun setTouch(x: Int, y: Int, pressed: Boolean): Boolean = owned(false) {
        setOwnedTouch(x, y, pressed)
    }
    private fun setOwnedTouch(x: Int, y: Int, pressed: Boolean): Boolean {
        if (!wii || width <= 0 || height <= 0) return false
        DolphinNative.control(0, 29, (2.0 * x / width - 1.0).coerceIn(-1.0, 1.0))
        DolphinNative.control(0, 30, (1.0 - 2.0 * y / height).coerceIn(-1.0, 1.0))
        DolphinNative.control(0, 18, if (pressed) 1.0 else 0.0)
        return true
    }
    override fun setSpeed(multiplier: Float): Boolean = owned(false) { DolphinNative.speed(multiplier) }
    override suspend fun saveState(target: File): Boolean = ownedIo(false) {
        target.parentFile?.mkdirs()
        DolphinNative.state(target.absolutePath, true)
    }
    override suspend fun loadState(source: File): Boolean = ownedIo(false) {
        source.isFile && DolphinNative.state(source.absolutePath, false)
    }
    override suspend fun applyOption(key: String, value: String): Boolean = ownedIo(false) {
        DolphinNative.option(key, value)
    }
    override fun frameCount(): Long = owned(0L) { DolphinNative.frames() }
    override suspend fun replaceCheats(codes:List<CoreCheatCode>):Boolean {
        if(codes.size>4096 || codes.any{!CoreCheatFormats.accepts(coreId,it)})return false
        return ownedIo(false){DolphinNative.cheats(codes.map{it.format}.toTypedArray(),codes.map{it.code}.toTypedArray())}
    }

    companion object {
        private val ownership = NativeSessionOwnership()
        private val systemDataLock = Any()
        private fun installSystemData(context: Context): File = synchronized(systemDataLock) {
            val root = File(context.noBackupFilesDir, "dolphin-sys-e1d35f666")
            val marker = File(root, ".ready")
            if (!marker.isFile) {
                fun copy(path: String, target: File) {
                    val children = context.assets.list(path).orEmpty()
                    if (children.isEmpty()) {
                        target.parentFile?.mkdirs()
                        context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
                    } else {
                        target.mkdirs()
                        children.forEach { copy("$path/$it", File(target, it)) }
                    }
                }
                copy("Sys", root)
                marker.writeText("e1d35f666")
            }
            root
        }
        /** Uses Dolphin's decompressor and disc headers, never the game filename. */
        fun probePlatform(path: String): ConsolePlatform? = if (!DolphinNative.available) null else when (DolphinNative.probe(path)) {
            1 -> ConsolePlatform.GAMECUBE
            2 -> ConsolePlatform.WII
            else -> null
        }
        fun probeGameIdentity(path:String):GameIdentity? {
            if(!DolphinNative.available)return null
            val values=DolphinNative.probeIdentity(path)?:return null
            if(values.size!=4)return null
            val platform=runCatching{ConsolePlatform.valueOf(values[0])}.getOrNull()?:return null
            return GameIdentity(platform,values[1],values[2].toIntOrNull(),values[3].takeIf{it.isNotBlank()})
        }
        internal fun gameCubeButton(button: EngineButton): Int = when (button) {
            EngineButton.A -> 0; EngineButton.B -> 1; EngineButton.X -> 2; EngineButton.Y -> 3
            EngineButton.Z, EngineButton.SELECT -> 4; EngineButton.START -> 5
            EngineButton.UP -> 6; EngineButton.DOWN -> 7; EngineButton.LEFT -> 8; EngineButton.RIGHT -> 9
            EngineButton.L, EngineButton.L2 -> 10; EngineButton.R, EngineButton.R2 -> 11
            else -> -1
        }
        internal fun wiiButton(button: EngineButton, classic: Boolean): Int = if (classic) when (button) {
            EngineButton.A -> 35; EngineButton.B -> 36; EngineButton.X -> 37; EngineButton.Y -> 38
            EngineButton.L2 -> 39; EngineButton.R2, EngineButton.Z -> 40
            EngineButton.START, EngineButton.PLUS -> 41; EngineButton.SELECT, EngineButton.MINUS -> 42
            EngineButton.HOME -> 43; EngineButton.UP -> 44; EngineButton.DOWN -> 45
            EngineButton.LEFT -> 46; EngineButton.RIGHT -> 47; EngineButton.L -> 48; EngineButton.R -> 49
            else -> -1
        } else when (button) {
            EngineButton.A -> 18; EngineButton.B -> 19; EngineButton.ONE, EngineButton.X -> 20
            EngineButton.TWO, EngineButton.Y -> 21; EngineButton.START, EngineButton.PLUS -> 22
            EngineButton.SELECT, EngineButton.MINUS -> 23; EngineButton.HOME -> 24
            EngineButton.UP -> 25; EngineButton.DOWN -> 26; EngineButton.LEFT -> 27; EngineButton.RIGHT -> 28
            EngineButton.C, EngineButton.L -> 31; EngineButton.Z, EngineButton.R, EngineButton.L2, EngineButton.R2 -> 32
            else -> -1
        }
    }
}
