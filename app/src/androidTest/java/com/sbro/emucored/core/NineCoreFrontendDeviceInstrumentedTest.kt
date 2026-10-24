// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.core.platform.session.CoreSessionHost
import com.sbro.emucored.core.platform.session.EngineButton
import com.sbro.emucored.data.GamePlatform
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in only: nineCoreFrontend=true, retailCoreRomDir, dsRomPath and 3dsRomPath.
 * The seven additional-core samples use the existing retail fixture convention:
 * gamecube.rvz, wii.rvz, gba.gba, gb.gb, gbc.gbc, nes.nes, snes.sfc.
 * Optional <platform.id>RomPath overrides any sample (3dsRomPath/ndsRomPath included).
 * Owned source files are opened read-only; no ROM or private path is bundled.
 *
 * Exercises EmulatorBridge, its actual presentation callbacks and normal slot API,
 * rather than launching raw engines. Two tours reopen every console in one PID.
 * Timeouts are deadlock guards, not performance requirements; all timings are logged.
 */
@RunWith(AndroidJUnit4::class)
class NineCoreFrontendDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val timings = JSONArray()
    private var phase = "setup"
    private var phaseStarted = SystemClock.elapsedRealtime()

    @Test(timeout = 2_700_000)
    fun allNineConsolesFailRecoverSaveLoadStopAndReopenThroughTheFrontend() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with nineCoreFrontend=true and owned ROM arguments", args.getString("nineCoreFrontend") == "true")
        assertFalse("Cannot replace a user's active game", EmulatorBridge.isVmActive())
        assertTrue("Packaged 3DS/DS libraries unavailable", EmuCoreNative.isLoaded && EmuCoreDsNative.isLoaded)
        val target = instrumentation.targetContext
        val previousContext = EmulatorBridge.getContext() ?: NativeApp.getContext() ?: target
        val previousSurface = Presentation.capture()
        val previousRoot = privateValue(NativeApp, "dataRootOverride") as String?
        val previousStateIdentity = privateValue(NativeApp, "saveStateIdentityPath") as String?
        val previousDsRenderer = DsSession.currentRenderer()
        val previousDsScale = DsSession.currentRenderScale()
        val previousDsSwap = DsSession.currentSwapScreens()
        val callback = HandlerThread("NineCoreFrontend-PixelCopy").apply { start() }
        val handler = Handler(callback.looper)
        val pid = Process.myPid()
        val oldHandles = mutableListOf<CoreSessionHost.Session>()
        val states = mutableMapOf<GamePlatform, File>()
        val sourceStats = mutableMapOf<File, Pair<Long, Long>>()
        testStorageContext().use { isolated ->
            // TestStorageContext isolates files/cache/preferences. Also isolate
            // Dolphin's immutable Sys installation, which uses noBackupFilesDir.
            val context = object : ContextWrapper(isolated) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = File(isolated.cacheDir, "no-backup").apply { mkdirs() }
            }
            try {
                NativeApp.reloadDataRoot(File(context.filesDir, "azahar").apply { mkdirs() }.path)
                EmulatorBridge.initializeOnce(context)
                val games = GamePlatform.entries.associateWith { platform ->
                    val id = platform.console.id
                    val extension = when (platform) {
                        GamePlatform.GAMECUBE, GamePlatform.WII -> "rvz"
                        GamePlatform.N3DS -> "3ds"
                        GamePlatform.SNES -> "sfc"
                        else -> id
                    }
                    val explicit = args.getString("${id}RomPath")
                        ?: if (platform == GamePlatform.NDS) args.getString("dsRomPath") else null
                    val file = explicit?.let(::File)
                        ?: File(args.getString("retailCoreRomDir").orEmpty(), "$id.$extension")
                    assertTrue("Missing owned $platform sample; supply ${id}RomPath or retailCoreRomDir", file.isFile && file.length() > 0)
                    sourceStats[file] = file.length() to file.lastModified()
                    // Compressed discs use Dolphin's volume parser, including GC/Wii
                    // distinction. Cartridge identity follows the frontend scanner.
                    assertEquals("Source discovery selected the wrong console for $file", platform,
                        ConsoleGameDiscovery.identify(context, file.path, file.name))
                    Game(platform, file, File(context.filesDir, "identities/$id.$extension").path)
                }
                val view = createWindow()
                attach(view)
                val firstTour = listOf(GamePlatform.N3DS, GamePlatform.NDS, GamePlatform.GBA,
                    GamePlatform.GB, GamePlatform.GBC, GamePlatform.NES, GamePlatform.SNES,
                    GamePlatform.GAMECUBE, GamePlatform.WII)
                // A second ordering covers reverse and shared-engine handoffs,
                // without claiming that this is all 72 possible directed edges.
                val secondTour = listOf(GamePlatform.GAMECUBE, GamePlatform.WII, GamePlatform.SNES,
                    GamePlatform.GBC, GamePlatform.GB, GamePlatform.NES, GamePlatform.GBA,
                    GamePlatform.NDS, GamePlatform.N3DS)
                (firstTour + secondTour).forEachIndexed { step, platform ->
                    val game = games.getValue(platform)
                    phase = "step${step + 1}/$platform"
                    assertEquals(diagnostic("App process restarted"), pid, Process.myPid())
                    assertReleased()
                    attach(view)
                    // Fail through the real selected adapter, then recover through
                    // the same facade in this process with the genuine source.
                    val missing = File(context.cacheDir, "missing-${platform.name}.${game.source.extension}")
                    assertFalse(missing.exists())
                    val rejected = measured("failed-boot", 180_000) {
                        EmulatorBridge.startEmulation(missing.path, game.identity + ".missing", platformOverride = platform)
                    }
                    assertFalse(diagnostic("Missing $platform image was accepted"), rejected)
                    measured("failed-boot-teardown", 90_000) { EmulatorBridge.shutdown() }
                    assertReleased()
                    assertNoSourceDescriptors(sourceStats.keys)
                    // Config changes target only the disposable context. They do
                    // not write app settings or the user's preference namespace.
                    measured("runtime-config", 30_000) {
                        EmulatorBridge.applyRuntimeConfig(renderer = RendererDefaults.VULKAN, upscaleMultiplier = 1f,
                            audioVolume = 0, audioMuted = true, textureReplacementsEnabled = false,
                            enableCheats = false, layoutOption = 0, swapScreens = false)
                    }
                    attach(view)
                    val launched = measured("valid-boot", 180_000) {
                        EmulatorBridge.startEmulation(game.source.path, game.identity,
                            dsRenderScale = 1, dsRenderer = 14, dsSwapScreens = false, platformOverride = platform)
                    }
                    assertTrue(diagnostic("Valid $platform boot failed: ${EmulatorBridge.runtimeFailure.value}"), launched)
                    assertOwnership(platform)
                    val newHandle = AdditionalConsoleSession.session
                    // Delayed references from every previously retired additional
                    // console must not stop, write state, or control this guest.
                    for (old in oldHandles) {
                        assertFalse(diagnostic("Retired handle is still running"), old.isRunning)
                        assertFalse(diagnostic("Retired handle accepted speed"), old.setSpeed(1.25f))
                        old.setButton(0, EngineButton.A, true)
                        old.requestStop()
                        measured("retired-stop", 30_000) { old.stop() }
                        val rogue = File(context.cacheDir, "retired-$step.state")
                        assertFalse(diagnostic("Retired handle wrote a state"), old.saveState(rogue))
                        assertFalse(diagnostic("Retired state appeared"), rogue.exists())
                    }
                    assertOwnership(platform)
                    awaitProgress(platform)
                    assertVisible(view, handler)
                    EmulatorBridge.setPadButton(0, 97, 255, true)
                    EmulatorBridge.setPadButton(0, 97, 255, false)
                    EmulatorBridge.resetKeyStatus()
                    assertEquals(diagnostic("Time-control state leaked across games"), 0, NativeApp.timeControlMode.value)
                    measured("pause", 30_000) { EmulatorBridge.pause() }
                    val state = when (platform) {
                        GamePlatform.N3DS -> NativeApp.getCurrentSaveStatePath(SLOT)?.let(::File)
                        GamePlatform.NDS -> DsSession.statePath(SLOT)?.let(::File)
                        else -> AdditionalConsoleSession.stateFile(SLOT)
                    }
                    assertNotNull(diagnostic("Missing frontend state location"), state)
                    val stateFile = requireNotNull(state)
                    assertTrue(diagnostic("State escaped test storage"),
                        stateFile.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                    states[platform]?.let { oldState ->
                        assertEquals(diagnostic("Reboot changed state identity"), oldState.path, stateFile.path)
                        assertTrue(diagnostic("Reboot lost completed state"), stateFile.isFile)
                        assertTrue(diagnostic("Cold-reboot state load failed"),
                            measured("reboot-load", 45_000) { EmulatorBridge.loadState(SLOT) })
                        measured("pause-after-reboot-load", 30_000) { EmulatorBridge.pause() }
                    }
                    if (stateFile.exists()) assertTrue(diagnostic("Cannot clear test-owned slot"), stateFile.delete())
                    assertTrue(diagnostic("Frontend state save rejected"),
                        measured("save", 45_000) { EmulatorBridge.saveState(SLOT) })
                    awaitState(stateFile)
                    assertTrue(diagnostic("Frontend state load rejected"),
                        measured("load", 45_000) { EmulatorBridge.loadState(SLOT) })
                    states[platform] = stateFile
                    measured("resume", 30_000) { EmulatorBridge.resume() }
                    awaitProgress(platform)
                    assertVisible(view, handler)
                    newHandle?.let(oldHandles::add)
                    EmulatorBridge.resetKeyStatus()
                    // Match the normal Exit path: request, await the asynchronous
                    // release, then allow the following launch to acquire ownership.
                    EmulatorBridge.requestShutdown()
                    measured("stop-await", 90_000) { EmulatorBridge.shutdownAsync().join() }
                    measured("idempotent-stop", 90_000) { EmulatorBridge.shutdown() }
                    assertReleased()
                    assertNoSourceDescriptors(sourceStats.keys)
                    sourceStats.forEach { (source, before) ->
                        assertEquals(diagnostic("ROM byte length changed: $source"), before.first, source.length())
                        assertEquals(diagnostic("ROM modified: $source"), before.second, source.lastModified())
                    }
                    println("NineCoreFrontend PASS $phase pid=$pid stateBytes=${stateFile.length()} timings=$timings")
                }
                assertEquals(diagnostic("Not every console completed state/reboot coverage"), GamePlatform.entries.toSet(), states.keys)
                assertEquals(diagnostic("Different consoles shared a state file"), states.size, states.values.map { it.path }.distinct().size)
            } finally {
                withContext(NonCancellable) {
                    try { measured("final-teardown", 120_000) { EmulatorBridge.shutdown() } }
                    finally {
                        instrumentation.runOnMainSync {
                            EmulatorBridge.onSurfaceDestroyed(EmulatorBridge.presentationSurfaceGeneration.value)
                        }
                        NativeApp.setSaveStateIdentityPath(previousStateIdentity)
                        NativeApp.reloadDataRoot(previousRoot.orEmpty())
                        DsSession.setRenderer(previousDsRenderer)
                        DsSession.setRenderScale(previousDsScale)
                        DsSession.setSwapScreens(previousDsSwap)
                        EmulatorBridge.initializeOnce(previousContext)
                        previousSurface.restore()
                        callback.quitSafely()
                        callback.join(5_000)
                        println("NineCoreFrontend timings=$timings")
                    }
                }
            }
        }
    }

    private fun createWindow(): SurfaceView {
        val ready = CountDownLatch(1)
        lateinit var view: SurfaceView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                SurfaceView(activity).apply {
                    view = this
                    keepScreenOn = true
                    holder.setFixedSize(WIDTH, HEIGHT)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ready.countDown() }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                }
            })
        }
        assertTrue(diagnostic("Presentation surface unavailable"), ready.await(15, TimeUnit.SECONDS))
        return view
    }

    private fun attach(view: SurfaceView) = instrumentation.runOnMainSync {
        val generation = EmulatorBridge.presentationSurfaceGeneration.value
        EmulatorBridge.onSurfaceCreated(generation)
        EmulatorBridge.onSurfaceChanged(view.holder.surface, WIDTH, HEIGHT, generation)
    }

    private fun assertOwnership(platform: GamePlatform) {
        assertTrue(diagnostic("No app-facing ownership"), EmulatorBridge.isVmActive())
        assertEquals(diagnostic("Wrong DS owner"), platform == GamePlatform.NDS, DsSession.isActive)
        assertEquals(diagnostic("Wrong 3DS owner"), platform == GamePlatform.N3DS, CoreRuntime.hasSession())
        val additional = platform !in setOf(GamePlatform.N3DS, GamePlatform.NDS)
        assertEquals(diagnostic("Wrong additional-core owner"), additional, AdditionalConsoleSession.isActive)
        if (additional) assertEquals(diagnostic("Wrong actual additional platform"), platform.console, AdditionalConsoleSession.platform)
        if (platform == GamePlatform.N3DS) assertTrue(diagnostic("Invalid running 3DS title"), EmuCoreNative.getRunningTitleId() != 0L)
    }

    private fun assertReleased() {
        assertFalse(diagnostic("Bridge retained a VM"), EmulatorBridge.isVmActive())
        assertFalse(diagnostic("Additional engine retained ownership"), AdditionalConsoleSession.isActive)
        assertNull(diagnostic("Additional session survived stop"), AdditionalConsoleSession.session)
        assertNull(diagnostic("Additional state identity survived stop"), AdditionalConsoleSession.stateFile(SLOT))
        assertFalse(diagnostic("DS retained ownership"), DsSession.isActive)
        assertFalse(diagnostic("DS native worker survived stop"), EmuCoreDsNative.nativeIsRunning())
        assertNull(diagnostic("DS state identity survived stop"), DsSession.statePath(SLOT))
        assertFalse(diagnostic("3DS retained session"), CoreRuntime.hasSession())
        assertFalse(diagnostic("3DS retained VM"), NativeApp.hasOwnedVm())
        assertFalse(diagnostic("Backup gate retained ownership"), BackupSessionGate.gameBusy)
    }

    private suspend fun awaitProgress(platform: GamePlatform) {
        val initial = when (platform) {
            GamePlatform.NDS -> EmuCoreDsNative.nativeGetFrameCount()
            else -> AdditionalConsoleSession.session?.frameCount() ?: 0L
        }
        measured("frame-progress", 90_000) {
            while (when (platform) {
                GamePlatform.N3DS -> EmuCoreNative.getPerfStats().getOrNull(1)?.let { it > 0.0 } != true
                GamePlatform.NDS -> EmuCoreDsNative.nativeGetFrameCount() - initial < 6
                else -> (AdditionalConsoleSession.session?.frameCount() ?: 0L) - initial < 6
            }) delay(25)
        }
    }

    private suspend fun assertVisible(view: SurfaceView, handler: Handler) = measured("real-window-pixels", 90_000) {
        while (true) {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(view, bitmap, { result = it; done.countDown() }, handler)
            assertTrue(diagnostic("PixelCopy callback timed out"), done.await(10, TimeUnit.SECONDS))
            val visible = try {
                if (result != PixelCopy.SUCCESS) false else {
                    val colors = mutableSetOf<Int>()
                    for (y in 30 until HEIGHT - 30 step 20) for (x in 30 until WIDTH - 30 step 20) {
                        val rgb = bitmap.getPixel(x, y) and 0xffffff
                        if (rgb != 0) colors += rgb
                    }
                    colors.size > 3
                }
            } finally { bitmap.recycle() }
            if (visible) break
            delay(100)
        }
    }

    private suspend fun awaitState(file: File) = measured("state-publication", 60_000) {
        var previous = -1L
        var stableSince = SystemClock.elapsedRealtime()
        while (true) {
            val size = if (file.isFile) file.length() else 0L
            if (size != previous) { previous = size; stableSince = SystemClock.elapsedRealtime() }
            if (size > 128 && SystemClock.elapsedRealtime() - stableSince >= 250) break
            delay(25)
        }
    }

    private fun assertNoSourceDescriptors(sources: Set<File>) {
        val paths = sources.map { it.canonicalPath }.toSet()
        val retained = File("/proc/self/fd").listFiles().orEmpty().mapNotNull { descriptor ->
            runCatching { Os.readlink(descriptor.path).removeSuffix(" (deleted)") }.getOrNull()
        }.filter { it in paths }
        assertTrue(diagnostic("Stopped core retained source descriptors: $retained"), retained.isEmpty())
    }

    private suspend fun <T> measured(name: String, timeoutMs: Long, operation: suspend () -> T): T {
        phaseStarted = SystemClock.elapsedRealtime()
        val began = phaseStarted
        try { return withTimeout(timeoutMs) { operation() } }
        catch (error: Throwable) {
            throw AssertionError(diagnostic("$name failed after ${SystemClock.elapsedRealtime() - began} ms (guard=$timeoutMs ms)"), error)
        } finally {
            timings.put(JSONObject().put("phase", phase).put("operation", name)
                .put("elapsedMs", SystemClock.elapsedRealtime() - began))
        }
    }

    private fun diagnostic(message: String) = "$phase: $message; phaseElapsedMs=${SystemClock.elapsedRealtime() - phaseStarted}; completedTimings=$timings"
    private data class Game(val platform: GamePlatform, val source: File, val identity: String)
    private data class Presentation(val surface: Surface?, val width: Int, val height: Int) {
        fun restore() {
            if (surface?.isValid == true && width > 0 && height > 0)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val generation = EmulatorBridge.presentationSurfaceGeneration.value
                    EmulatorBridge.onSurfaceCreated(generation)
                    EmulatorBridge.onSurfaceChanged(surface, width, height, generation)
                }
        }
        companion object {
            fun capture() = Presentation(privateValue(EmulatorBridge, "lastSurface") as Surface?,
                privateValue(EmulatorBridge, "lastSurfaceWidth") as Int,
                privateValue(EmulatorBridge, "lastSurfaceHeight") as Int)
        }
    }
    companion object {
        private const val SLOT = 8
        private const val WIDTH = 640
        private const val HEIGHT = 480
        // Read-only snapshots of existing private presentation/storage choices;
        // production APIs perform every write and restore operation.
        private fun privateValue(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
    }
}
