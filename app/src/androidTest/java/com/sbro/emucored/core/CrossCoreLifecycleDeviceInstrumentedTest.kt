package com.sbro.emucored.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in: crossCoreLifecycle=true, dsRomPath, dsRomPathB, 3dsRomPath, 3dsRomPathB.
 * Supply two different owned games for each platform. The whole matrix runs in one app PID,
 * using the app-facing bridge and real SurfaceViews; ROMs and user saves are never modified.
 * This covers bridge lifecycle, not library navigation or an acoustic audio assertion.
 */
@RunWith(AndroidJUnit4::class)
class CrossCoreLifecycleDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val windowToken = mutableIntStateOf(0)
    private val ready = AtomicReference(CountDownLatch(1))
    private val currentWindow = AtomicReference<SurfaceView>()

    @Test(timeout = 900_000)
    fun differentGamesAndBothCoresCanBeReopenedRepeatedlyWithoutProcessRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable crossCoreLifecycle=true with four owned ROM arguments", args.getString("crossCoreLifecycle") == "true")
        val games = listOf(
            Game("DS-A", args.getString("dsRomPath").orEmpty(), true),
            Game("DS-B", args.getString("dsRomPathB").orEmpty(), true),
            Game("3DS-A", args.getString("3dsRomPath").orEmpty(), false),
            Game("3DS-B", args.getString("3dsRomPathB").orEmpty(), false)
        )
        require(games.all { it.path.isNotBlank() }) { "Supply dsRomPath, dsRomPathB, 3dsRomPath and 3dsRomPathB" }
        require(games[0].path != games[1].path && games[2].path != games[3].path) { "A and B must be distinct games" }
        val repeats = (args.getString("crossCoreRepeats")?.toInt() ?: 2).coerceIn(1, 3)
        val renderer = args.getString("crossCoreDsRenderer")?.toInt() ?: 14
        require(renderer == 13 || renderer == 14)
        val pid = Process.myPid()
        assertFalse("Cannot replace a user's active game", EmulatorBridge.isVmActive())
        assertTrue(EmuCoreNative.isLoaded && EmuCoreDsNative.isLoaded)
        val previousContext = EmulatorBridge.getContext() ?: NativeApp.getContext()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val previousDsRenderer = DsSession.currentRenderer()
        val previousDsScale = DsSession.currentRenderScale()
        val previousDsSwap = DsSession.currentSwapScreens()
        val callbackThread = HandlerThread("CrossCore-PixelCopy").apply { start() }
        val handler = Handler(callbackThread.looper)
        val observations = JSONArray()
        val statePaths = mutableMapOf<String, String>()
        val titleIds = mutableMapOf<String, Long>()
        val output = args.getString("crossCoreOutputDir")?.let(::File)
            ?: File(target.cacheDir, "cross-core-lifecycle/${System.currentTimeMillis()}")
        check(output.isDirectory || output.mkdirs())
        testStorageContext().use { context ->
            try {
                EmulatorBridge.initializeOnce(context)
                compose.setContent {
                    key(windowToken.intValue) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                            SurfaceView(activity).apply {
                                currentWindow.set(this)
                                holder.setFixedSize(400, 480)
                                val latch = ready.get()
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { latch.countDown() }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                                })
                            }
                        })
                    }
                }
                // Every required directed edge occurs in every repetition.
                val sequence = listOf(games[0], games[1], games[2], games[3], games[0], games[2], games[1])
                repeat(repeats) { repetition ->
                    sequence.forEachIndexed { step, game ->
                        val label = "round${repetition + 1}/step${step + 1}/${game.name}"
                        assertEquals("App process changed during matrix", pid, Process.myPid())
                        assertReleased(label)
                        assertTrue("$label surface creation timed out", ready.get().await(10, TimeUnit.SECONDS))
                        val window = requireNotNull(currentWindow.get())
                        val generation = EmulatorBridge.presentationSurfaceGeneration.value
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            EmulatorBridge.onSurfaceCreated(generation)
                            EmulatorBridge.onSurfaceChanged(window.holder.surface, 400, 480, generation)
                        }
                        val volume = if (step % 2 == 0) 37 else 62
                        withTimeout(20_000) {
                            EmulatorBridge.applyRuntimeConfig(
                                renderer = RendererDefaults.VULKAN, upscaleMultiplier = 1f,
                                audioVolume = volume, audioMuted = true,
                                textureReplacementsEnabled = false, enableCheats = false,
                                layoutOption = 0, swapScreens = false
                            )
                        }
                        val config = File(context.filesDir, "azahar/config/config.ini").readText()
                        assertTrue("$label lost new audio configuration", Regex("(?m)^volume\\s*=\\s*$volume\\s*$").containsMatchIn(config))
                        val identity = File(context.filesDir, "identities/${game.name}.${if (game.ds) "nds" else "3ds"}").absolutePath
                        assertTrue("$label failed to launch: ${EmulatorBridge.runtimeFailure.value}", withTimeout(60_000) {
                            EmulatorBridge.startEmulation(game.path, identity, dsRenderScale = 1, dsRenderer = renderer, dsSwapScreens = false)
                        })
                        assertTrue("$label no app-facing ownership", EmulatorBridge.isVmActive())
                        assertEquals("$label wrong DS ownership", game.ds, DsSession.isActive)
                        assertEquals("$label wrong 3DS ownership", !game.ds, CoreRuntime.hasSession())
                        awaitProgress(label, game.ds)
                        assertRealPixels(label, window, handler)
                        // A is routed through the same facade used by touchscreen/gamepad controls.
                        EmulatorBridge.setPadButton(0, 97, 255, true)
                        EmulatorBridge.setPadButton(0, 97, 255, false)
                        EmulatorBridge.resetKeyStatus()
                        assertEquals("$label inherited fast-forward/rewind", 0, NativeApp.timeControlMode.value)
                        if (!game.ds) NativeApp.setAudioOutputGain(volume, true)
                        withTimeout(15_000) { EmulatorBridge.pause() }
                        awaitPaused(label, game.ds)
                        val state = File(requireNotNull(if (game.ds) DsSession.statePath(6) else NativeApp.getCurrentSaveStatePath(6)))
                        assertTrue("$label save escaped isolated test storage", state.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                        statePaths[game.name]?.let { assertEquals("$label state identity changed", it, state.absolutePath) }
                        statePaths[game.name] = state.absolutePath
                        if (game.ds) {
                            assertEquals(renderer, DsSession.currentRenderer())
                            assertEquals(1, DsSession.currentRenderScale())
                            assertFalse(DsSession.currentSwapScreens())
                        } else {
                            val id = EmuCoreNative.getRunningTitleId()
                            assertTrue("$label invalid running title identity", id != 0L)
                            titleIds[game.name]?.let { assertEquals("$label booted a different title", it, id) }
                            titleIds[game.name] = id
                        }
                        // Remove only this test-owned slot, so an old file cannot satisfy completion.
                        if (state.exists()) assertTrue("$label cannot clear isolated old slot", state.delete())
                        assertTrue("$label rejected paused save", withTimeout(15_000) { EmulatorBridge.saveState(6) })
                        await("$label paused save did not finish", 30_000) { state.isFile && state.length() > 256 }
                        var savedLength = state.length()
                        var stableSince = SystemClock.elapsedRealtime()
                        await("$label state file never settled", 10_000) {
                            val length = state.length()
                            if (length != savedLength) { savedLength = length; stableSince = SystemClock.elapsedRealtime() }
                            SystemClock.elapsedRealtime() - stableSince >= 200
                        }
                        awaitPaused("$label after save", game.ds)
                        if (game.ds) {
                            val metadata = JSONObject(File("${state.absolutePath}.json").readText())
                            assertEquals("$label retained another game's save identity", identity, metadata.getString("gamePath"))
                        }
                        assertTrue("$label load failed", withTimeout(15_000) { EmulatorBridge.loadState(6) })
                        withTimeout(15_000) { EmulatorBridge.resume() }
                        awaitProgress("$label resumed", game.ds)
                        observations.put(JSONObject().put("step", label).put("pid", pid).put("statePath", state.absolutePath)
                            .put("stateBytes", state.length()).put("runningTitleId", titleIds[game.name] ?: 0L)
                            .put("dsRenderer", if (game.ds) renderer else JSONObject.NULL))
                        // This is the in-game Exit lifecycle; the following game starts in the same process.
                        EmulatorBridge.resetKeyStatus()
                        withTimeout(30_000) { EmulatorBridge.shutdown() }
                        assertReleased("$label after Exit")
                        InstrumentationRegistry.getInstrumentation().runOnMainSync { EmulatorBridge.onSurfaceDestroyed(generation) }
                        assertNull("$label DS state ownership survived Exit", DsSession.statePath(6))
                        ready.set(CountDownLatch(1))
                        compose.runOnIdle { windowToken.intValue++ }
                        // Compose's test clock advances recomposition during synchronization.
                        // Do not block on the native holder before dispatching this replacement.
                        compose.waitForIdle()
                        File(output, "matrix.json").writeText(JSONObject().put("pid", pid)
                            .put("complete", false).put("steps", observations).toString(2))
                        println("CrossCoreLifecycle PASS $label pid=$pid")
                    }
                }
                assertNotEquals("DS games shared a state directory", statePaths.getValue("DS-A"), statePaths.getValue("DS-B"))
                assertNotEquals("3DS arguments identify the same game", titleIds.getValue("3DS-A"), titleIds.getValue("3DS-B"))
                File(output, "matrix.json").writeText(JSONObject().put("pid", pid).put("complete", true).put("steps", observations)
                    .put("scope", "Actual frontend bridge and SurfaceView lifecycle; audio configuration exercised, no acoustic assertion; library navigation not automated").toString(2))
                println("CrossCoreLifecycle output=${output.absolutePath}")
            } finally {
                withTimeout(30_000) { EmulatorBridge.shutdown() }
                EmulatorBridge.onSurfaceDestroyed(EmulatorBridge.presentationSurfaceGeneration.value)
                NativeApp.setSaveStateIdentityPath(null)
                DsSession.setRenderer(previousDsRenderer)
                DsSession.setRenderScale(previousDsScale)
                DsSession.setSwapScreens(previousDsSwap)
                EmulatorBridge.initializeOnce(previousContext ?: target)
                callbackThread.quitSafely()
                callbackThread.join(5_000)
            }
        }
    }

    private fun assertReleased(label: String) {
        assertFalse("$label retained app-facing session", EmulatorBridge.isVmActive())
        assertFalse("$label retained DS session", DsSession.isActive)
        assertFalse("$label retained DS native worker", EmuCoreDsNative.nativeIsRunning())
        assertFalse("$label retained 3DS session", CoreRuntime.hasSession())
        assertFalse("$label retained 3DS VM", NativeApp.hasOwnedVm())
    }

    private fun awaitProgress(label: String, ds: Boolean) {
        val first = if (ds) EmuCoreDsNative.nativeGetFrameCount() else 0
        await("$label did not progress", 45_000) {
            if (ds) EmuCoreDsNative.nativeGetFrameCount() - first >= 12
            else EmuCoreNative.getPerfStats().getOrNull(1)?.let { it > 0.0 } == true
        }
    }

    private fun awaitPaused(label: String, ds: Boolean) {
        if (ds) {
            var frame = EmuCoreDsNative.nativeGetFrameCount()
            var stableSince = SystemClock.elapsedRealtime()
            await("$label did not pause", 10_000) {
                val current = EmuCoreDsNative.nativeGetFrameCount()
                if (frame != current) { frame = current; stableSince = SystemClock.elapsedRealtime() }
                DsSession.isPaused && SystemClock.elapsedRealtime() - stableSince >= 100
            }
        } else await("$label did not pause", 10_000) { EmuCoreNative.getPerfStats().getOrNull(1) == 0.0 }
    }

    private fun assertRealPixels(label: String, window: SurfaceView, handler: Handler) {
        await("$label did not present real window pixels", 30_000) {
            val bitmap = Bitmap.createBitmap(400, 480, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            PixelCopy.request(window, bitmap, { result = it; done.countDown() }, handler)
            if (!done.await(5, TimeUnit.SECONDS)) throw AssertionError("$label PixelCopy callback timed out")
            try {
                if (result != PixelCopy.SUCCESS) false else {
                    val pixels = IntArray(400 * 480)
                    bitmap.getPixels(pixels, 0, 400, 0, 0, 400, 480)
                    val first = pixels[0] and 0xffffff
                    pixels.any { it and 0xffffff != first }
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    private data class Game(val name: String, val path: String, val ds: Boolean)
}
