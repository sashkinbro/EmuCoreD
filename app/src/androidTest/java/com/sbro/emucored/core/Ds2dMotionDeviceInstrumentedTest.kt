package com.sbro.emucored.core

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
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
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Opt in: -e dsMotion true -e dsRomPath <user-owned Contra4 path/URI>.
 * Optional dsMotionFrames=180, dsMotionScale=2, dsMotionBootFrames=360,
 * dsMotionOutputDir=<writable directory>. Verify checkpoint-scene.png before calling it gameplay.
 * The diagnostic reads RGBA planes; it is not a no-readback performance benchmark.
 */
@RunWith(AndroidJUnit4::class)
class Ds2dMotionDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 600_000)
    fun captureRightJumpAndFireFromTheSameCheckpointOnSoftwareAndVulkan() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable this optional diagnostic with dsMotion=true", args.getString("dsMotion") == "true")
        val rom = args.getString("dsRomPath").orEmpty()
        require(rom.isNotBlank()) { "dsRomPath must identify a readable user-owned Contra4 ROM" }
        val count = (args.getString("dsMotionFrames")?.toInt() ?: 180).coerceIn(30, 240)
        val scale = args.getString("dsMotionScale")?.toInt() ?: 2
        require(scale in setOf(1, 2, 4, 8))
        val bootFrames = (args.getString("dsMotionBootFrames")?.toInt() ?: 360).coerceIn(180, 1800)
        val trace = args.getString("dsMotionTrace") == "true"
        val windowCapture = args.getString("dsMotionSurface", "window") == "window"
        assertTrue(EmuCoreDsNative.isLoaded)
        assertFalse("Cannot replace a user's active DS session", DsSession.isActive)
        assertFalse("Cannot replace a user's active 3DS session", CoreRuntime.hasSession())
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = args.getString("dsMotionOutputDir")?.let(::File)
            ?: File(target.cacheDir, "ds-2d-motion/${System.currentTimeMillis()}")
        check(output.isDirectory || output.mkdirs())
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        val window = if (windowCapture) createGameWindow() else null
        testStorageContext().use { context ->
            val identity = File(context.filesDir, "owned-contra-motion.nds").absolutePath
            lateinit var checkpoint: File
            var checkpointSha = ""
            try {
                if (trace) setTraceMode(0)
                for (renderer in listOf(13, 14)) {
                    FrameConsumer(window).use { consumer ->
                        DsSession.setLayout(DsSession.LAYOUT_STACKED)
                        DsSession.setSwapScreens(false)
                        DsSession.setSurface(consumer.surface)
                        assertTrue("Renderer $renderer start failed", DsSession.start(
                            context, rom, identity, renderScale = scale, renderer = renderer,
                            startPaused = renderer == 14
                        ))
                        try {
                            if (renderer == 13) {
                                awaitFrames(bootFrames, consumer)
                                press(3, consumer) // Start: title screen.
                                awaitFrames(120, consumer)
                                press(3, consumer) // Start: character/menu confirmation.
                                awaitFrames(120, consumer)
                                press(0, consumer) // A: enter the level.
                                awaitFrames(180, consumer)
                                // Initial boot inputs can precede the title's input-ready state.
                                // Confirm only after its completed animation, then choose game/difficulty.
                                press(3, consumer)
                                awaitFrames(90, consumer)
                                press(0, consumer)
                                awaitFrames(90, consumer)
                                press(0, consumer)
                                awaitFrames(180, consumer)
                                captureNavigationScene(consumer, "difficulty")
                                repeat(3) { confirmation ->
                                    press(0, consumer) // Confirm difficulty/remaining prompts; A in-game is harmless.
                                    awaitFrames(180, consumer)
                                    captureNavigationScene(consumer, "confirm-${confirmation + 1}")
                                }
                                val scene = consumer.begin(1, 0, 0)
                                consumer.await(scene)
                                DsSession.pause()
                                assertTrue("Cannot create common guest checkpoint", DsSession.saveState(7))
                                checkpoint = File(requireNotNull(DsSession.statePath(7)))
                                checkpointSha = sha256(checkpoint.readBytes())
                                // Reuse this test-owned, visually checked scene in timing probes.
                                checkpoint.copyTo(File(output, "checkpoint.dss"), overwrite = true)
                                consumer.end(scene)
                                // PNG compression is deferred until the native session is stopped.
                                sceneFrame = scene.snapshot().single()
                            } else {
                                assertTrue("Vulkan did not start paused", DsSession.isPaused)
                            }
                            assertTrue("Common savepoint is missing", checkpoint.isFile)
                            assertEquals(checkpointSha, sha256(checkpoint.readBytes()))
                            assertTrue("Renderer $renderer cannot restore common savepoint",
                                EmuCoreDsNative.nativeLoadState(checkpoint.absolutePath))
                            consumer.drain()
                            val frames = captureMotion(consumer, count, renderer, trace && renderer == 14)
                            DsSession.stop()
                            val directory = File(output, "renderer-$renderer-${scale}x").apply {
                                check(isDirectory || mkdirs())
                            }
                            if (renderer == 13) {
                                writePng(requireNotNull(sceneFrame), File(output, "checkpoint-scene.png"))
                                navigationScenes.forEach { (name, frame) -> writePng(frame, File(output, "navigation-$name.png")) }
                            }
                            writeSequence(frames, directory, renderer, scale, checkpointSha)
                        } finally {
                            EmuCoreDsNative.nativeSetKeyMask(NEUTRAL)
                            DsSession.stop()
                            // Reusing a SurfaceView must release the preceding API's
                            // native-window connection before the next renderer attaches.
                            EmuCoreDsNative.nativeSurfaceDestroyed()
                            DsSession.setSurface(null)
                        }
                    }
                }
                val summary = JSONObject().put("checkpointSha256", checkpointSha)
                    .put("framesPerRenderer", count).put("scale", scale)
                    .put("captureSurface", if (windowCapture) "SurfaceView PixelCopy" else "ImageReader")
                    .put("sceneVerification", "Inspect checkpoint-scene.png; menu navigation is an attempt, not an asserted game state")
                    .put("input", "Hold Right(bit4)+Y/fire(bit11); press B/jump(bit1) for12 of each36 guest frames; warmup360 before capture")
                    .put("comparison", "Same native checkpoint and native-frame-relative input schedule; frame counters at image arrival are observational")
                File(output, "summary.json").writeText(summary.toString(2))
                Log.i(TAG, "COMPLETE output=${output.absolutePath}")
                println("$TAG COMPLETE output=${output.absolutePath}")
            } finally {
                if (trace) setTraceMode(0)
                sceneFrame = null
                navigationScenes.clear()
                DsSession.stop()
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
            }
        }
    }

    private var sceneFrame: CapturedFrame? = null
    private val navigationScenes = mutableListOf<Pair<String, CapturedFrame>>()

    private fun createGameWindow(): SurfaceView {
        val ready = CountDownLatch(1)
        lateinit var surface: SurfaceView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                SurfaceView(activity).apply {
                    surface = this
                    holder.setFixedSize(WIDTH, HEIGHT)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            ready.countDown()
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                }
            })
        }
        assertTrue("Game SurfaceView did not become ready", ready.await(10, TimeUnit.SECONDS))
        return surface
    }

    private fun captureNavigationScene(consumer: FrameConsumer, name: String) {
        val batch = consumer.begin(1, 0, System.nanoTime())
        consumer.await(batch)
        consumer.end(batch)
        navigationScenes += name to batch.snapshot().single()
    }

    private fun captureMotion(consumer: FrameConsumer, count: Int, renderer: Int, trace: Boolean): List<CapturedFrame> {
        val first = EmuCoreDsNative.nativeGetFrameCount()
        val finished = AtomicBoolean(false)
        val inputFailure = AtomicReference<Throwable?>()
        fun apply(logicalFrame: Long) {
            val pressed = (1 shl 4) or (1 shl 11) or if (logicalFrame % 36 < 12) (1 shl 1) else 0
            val mask = NEUTRAL and pressed.inv()
            EmuCoreDsNative.nativeSetKeyMask(mask)
            consumer.input.set(InputState(mask, logicalFrame, EmuCoreDsNative.nativeGetFrameCount(), SystemClock.elapsedRealtimeNanos()))
        }
        apply(0)

        val inputThread = Thread({
            try {
                var last = -1L
                while (!finished.get()) {
                    val relative = (EmuCoreDsNative.nativeGetFrameCount() - first).coerceAtLeast(0)
                    if (relative != last) { apply(relative); last = relative }
                    SystemClock.sleep(1)
                }
            } catch (error: Throwable) { inputFailure.set(error) }
        }, "ContraMotion-Input").apply { start() }
        try {
            // Contra's helicopter entry temporarily locks movement despite a visible level.
            // Replay the same active input warmup on both backends before recording or tracing.
            DsSession.resume()
            awaitFrames(360, consumer)
            DsSession.pause()
            consumer.drain()
            val captureFirst = EmuCoreDsNative.nativeGetFrameCount()
            val batch = consumer.begin(count, captureFirst, System.nanoTime())
            Log.i(TAG, "CAPTURE renderer=$renderer nativeStart=$captureFirst inputStart=$first warmup=360 count=$count")
            if (trace) setTraceMode(2)
            DsSession.resume()
            consumer.await(batch)
            inputFailure.get()?.let { throw AssertionError("Motion input failed", it) }
            DsSession.pause()
            consumer.end(batch)
            return batch.snapshot()
        } finally {
            finished.set(true)
            inputThread.join(2_000)
            assertFalse("Motion input worker did not stop", inputThread.isAlive)
            EmuCoreDsNative.nativeSetKeyMask(NEUTRAL)
            if (trace) setTraceMode(0)
        }
    }

    private fun setTraceMode(mode: Int) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("setprop debug.emucored.ds_trace $mode")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.bufferedReader().readText() }
        check(output.isBlank()) { "Cannot set DS diagnostic trace mode: $output" }
    }

    private fun press(bit: Int, consumer: FrameConsumer) {
        try {
            EmuCoreDsNative.nativeSetKeyMask(NEUTRAL and (1 shl bit).inv())
            awaitFrames(6, consumer)
        } finally { EmuCoreDsNative.nativeSetKeyMask(NEUTRAL) }
    }

    private fun awaitFrames(count: Int, consumer: FrameConsumer) {
        val first = EmuCoreDsNative.nativeGetFrameCount()
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (EmuCoreDsNative.nativeGetFrameCount() - first < count) {
            consumer.checkFailure()
            assertTrue("DS stopped during navigation", EmuCoreDsNative.nativeIsRunning())
            assertTrue("Guest navigation progress timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(5)
        }
    }

    private fun writeSequence(frames: List<CapturedFrame>, directory: File, renderer: Int, scale: Int, checkpointSha: String) {
        val metadata = JSONArray()
        frames.forEachIndexed { index, frame ->
            val name = "frame-${index.toString().padStart(3, '0')}.png"
            writePng(frame, File(directory, name))
            metadata.put(JSONObject().put("file", name).put("captureIndex", index)
                .put("nativeFrameAtArrival", frame.nativeFrame).put("nativeFrameRelative", frame.relativeFrame)
                .put("arrivalElapsedRealtimeNs", frame.arrivalNs).put("imageTimestampNs", frame.imageNs)
                .put("pixelCopyRequestedElapsedRealtimeNs", frame.requestNs)
                .put("keyMaskActiveLow", frame.input.mask).put("inputLogicalFrame", frame.input.logicalFrame)
                .put("inputIssuedAtNativeFrame", frame.input.nativeFrame).put("inputIssuedElapsedRealtimeNs", frame.input.issuedNs))
        }
        File(directory, "frames.json").writeText(JSONObject().put("renderer", renderer)
            .put("scale", scale).put("surface", if (frames.first().requestNs != 0L) "256x386 SurfaceView PixelCopy" else "256x386 ImageReader RGBA8888")
            .put("sampling", "PixelCopy samples may repeat/skip presented frames; native counters are observed at completion, not exact pixel guest identity")
            .put("checkpointSha256", checkpointSha).put("pngEncoding", "After native stop, outside capture")
            .put("frames", metadata).toString(2))
    }

    private fun writePng(frame: CapturedFrame, file: File) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class InputState(val mask: Int, val logicalFrame: Long, val nativeFrame: Long, val issuedNs: Long)
    private data class CapturedFrame(val rgba: ByteArray, val nativeFrame: Long, val relativeFrame: Long,
        val arrivalNs: Long, val imageNs: Long, val input: InputState, val requestNs: Long = 0L)

    private class Batch(val count: Int, val first: Long, val notBeforeImageNs: Long) {
        val done = CountDownLatch(1)
        private val frames = ArrayList<CapturedFrame>(count)
        @Synchronized fun isFull() = frames.size >= count
        @Synchronized fun add(frame: CapturedFrame) { if (frames.size < count) frames.add(frame); if (frames.size == count) done.countDown() }
        @Synchronized fun snapshot(): List<CapturedFrame> = frames.toList()
    }

    private class FrameConsumer(private val window: SurfaceView?) : Closeable {
        private val thread = HandlerThread("ContraMotion-Images").apply { start() }
        private val handler = Handler(thread.looper)
        private val reader = if (window == null) ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3) else null
        val surface: Surface get() = window?.holder?.surface ?: requireNotNull(reader).surface
        val input = AtomicReference(InputState(NEUTRAL, 0, 0, 0))
        private val recording = AtomicReference<Batch?>()
        private val failure = AtomicReference<Throwable?>()
        init {
            reader?.setOnImageAvailableListener({ source ->
                try {
                    while (true) {
                        val image = source.acquireNextImage() ?: break
                        image.use {
                            val batch = recording.get()
                            if (batch != null && !batch.isFull() &&
                                (image.timestamp == 0L || image.timestamp >= batch.notBeforeImageNs)) {
                                val arrival = SystemClock.elapsedRealtimeNanos()
                                val native = EmuCoreDsNative.nativeGetFrameCount()
                                val keys = input.get()
                                val plane = image.planes[0]
                                check(plane.pixelStride == 4)
                                val rgba = ByteArray(WIDTH * HEIGHT * 4)
                                val buffer = plane.buffer
                                for (y in 0 until HEIGHT) {
                                    buffer.position(y * plane.rowStride)
                                    buffer.get(rgba, y * WIDTH * 4, WIDTH * 4)
                                }
                                batch.add(CapturedFrame(rgba, native, native - batch.first, arrival, image.timestamp, keys))
                            }
                        }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }, handler)
        }
        fun begin(count: Int, first: Long, notBeforeNs: Long): Batch = Batch(count, first, notBeforeNs).also {
            recording.set(it)
            if (window != null) handler.post { copyWindowFrame(it) }
        }
        private fun copyWindowFrame(batch: Batch) {
            if (recording.get() !== batch || batch.isFull()) return
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val requested = SystemClock.elapsedRealtimeNanos()
            try {
                PixelCopy.request(requireNotNull(window), bitmap, { result ->
                    try {
                        if (result == PixelCopy.SUCCESS) {
                            val arrival = SystemClock.elapsedRealtimeNanos()
                            val native = EmuCoreDsNative.nativeGetFrameCount()
                            val rgba = ByteArray(WIDTH * HEIGHT * 4)
                            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
                            batch.add(CapturedFrame(rgba, native, native - batch.first, arrival, 0, input.get(), requested))
                        } else if (result != PixelCopy.ERROR_SOURCE_NO_DATA) {
                            throw AssertionError("SurfaceView PixelCopy failed: $result")
                        }
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                    finally { bitmap.recycle() }
                    if (recording.get() === batch && !batch.isFull()) handler.postDelayed({ copyWindowFrame(batch) }, 16)
                }, handler)
            } catch (error: Throwable) {
                bitmap.recycle()
                failure.compareAndSet(null, error)
            }
        }
        fun end(batch: Batch) { recording.compareAndSet(batch, null); drain() }
        fun checkFailure() { failure.get()?.let { throw AssertionError("RGBA image consumer failed", it) } }
        fun await(batch: Batch) {
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (!batch.done.await(25, TimeUnit.MILLISECONDS)) {
                checkFailure()
                assertTrue("Motion image sequence timed out", SystemClock.elapsedRealtime() < deadline)
                assertTrue("DS stopped during capture", EmuCoreDsNative.nativeIsRunning())
            }
            checkFailure()
            assertEquals(batch.count, batch.snapshot().size)
        }
        fun drain() {
            val done = CountDownLatch(1)
            handler.post { done.countDown() }
            assertTrue("Image consumer did not drain", done.await(5, TimeUnit.SECONDS))
            checkFailure()
        }
        override fun close() {
            recording.set(null)
            reader?.setOnImageAvailableListener(null, null)
            try { drain() } finally { reader?.close(); thread.quitSafely(); thread.join(5_000) }
        }
    }

    private companion object {
        const val TAG = "Ds2dMotion"
        const val NEUTRAL = 0xFFF
        const val WIDTH = 256
        const val HEIGHT = 386
    }
}
