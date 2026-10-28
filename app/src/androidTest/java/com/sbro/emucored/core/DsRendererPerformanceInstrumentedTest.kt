// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run this class alone, with no interactive game session:
 * -e dsPerformance true -e dsRomPath <owned readable path or persisted content URI>
 * -e dsRenderer 14 -e dsScale 2 [-e dsWarmupFrames 180] [-e dsSteadyFrames 300]
 * Optional dsProbeNfsProfile=true waits through boot, touches the title and sends Start/A.
 * Verify the resulting scene PNG before attributing timings to any particular menu or race.
 * Optional dsCaptureOutputDir writes one PNG after timing, for scene verification.
 * Images are drained and closed without reading their pixel planes. Reports remain
 * in target cache/ds-performance, or the explicitly supplied dsPerformanceOutputDir.
 * Arrival intervals measure this fixed 256x386 consumer, not physical display scanout.
 */
@RunWith(AndroidJUnit4::class)
class DsRendererPerformanceInstrumentedTest {
    private var movementProbe = false
    private var lastProbeMask = 0xFFF
    @Test(timeout = 600_000)
    fun measureSteadyPresentationWithoutPixelReadback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable this optional probe with dsPerformance=true", args.getString("dsPerformance") == "true")
        val romPath = args.getString("dsRomPath").orEmpty()
        require(romPath.isNotBlank()) { "dsRomPath must identify a readable, user-owned DS ROM" }
        val renderer = args.getString("dsRenderer")?.toInt() ?: 14
        val scale = args.getString("dsScale")?.toInt() ?: 2
        require(renderer in setOf(13, 14)) { "dsRenderer must be 13 (Software) or 14 (Vulkan)" }
        require(scale in setOf(1, 2, 4, 8)) { "dsScale must be 1, 2, 4 or 8" }
        val warmup = (args.getString("dsWarmupFrames")?.toInt() ?: 180).coerceIn(180, 1800)
        val steady = (args.getString("dsSteadyFrames")?.toInt() ?: 300).coerceIn(300, 1800)
        val nfsProbe = args.getString("dsProbeNfsProfile") == "true"
        val navigation = args.getString("dsNavigationBits").orEmpty().split(',')
            .filter(String::isNotBlank).map { it.trim().toInt().also { bit -> require(bit in 0..11) } }
        val moving = args.getString("dsContraMovement") == "true"
        val checkpoint = args.getString("dsCheckpointPath")?.let(::File)
        assertTrue("DS JNI library must load", EmuCoreDsNative.isLoaded)
        assertFalse("Benchmark cannot replace an interactive DS session", DsSession.isActive)
        assertFalse("Benchmark cannot replace an interactive runtime", CoreRuntime.hasSession())
        val gpu = queryGpuDriver()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = args.getString("dsPerformanceOutputDir")?.let(::File)
            ?: File(target.cacheDir, "ds-performance")
        check(output.isDirectory || output.mkdirs()) { "Cannot create report directory: $output" }
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        val images = AtomicLong()
        val callbackFailure = AtomicReference<Throwable?>()
        val captureRequested = AtomicBoolean(false)
        val captureDone = CountDownLatch(1)
        val captureDirectory = args.getString("dsCaptureOutputDir")?.let(::File)
        val sampleLock = Any()
        val intervals = ArrayList<Long>()
        var recording = false
        var previousArrival = 0L
        val consumer = HandlerThread("DsPerformance-Images").apply { start() }
        val handler = Handler(consumer.looper)
        val reader = ImageReader.newInstance(256, 386, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ source ->
            try {
                while (true) {
                    val image = source.acquireNextImage() ?: break
                    image.use {
                        val arrival = SystemClock.elapsedRealtimeNanos()
                        images.incrementAndGet()
                        if (captureRequested.compareAndSet(true, false)) {
                            val plane = image.planes[0]
                            check(plane.pixelStride == 4) { "Unexpected RGBA pixel stride" }
                            val paddedWidth = plane.rowStride / plane.pixelStride
                            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                            try {
                                plane.buffer.rewind()
                                padded.copyPixelsFromBuffer(plane.buffer)
                                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                                try {
                                    val directory = requireNotNull(captureDirectory)
                                    check(directory.isDirectory || directory.mkdirs())
                                    File(directory, "ds-$renderer-${scale}x-scene.png").outputStream().use {
                                        check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                } finally {
                                    if (cropped !== padded) cropped.recycle()
                                }
                            } finally {
                                padded.recycle()
                                captureDone.countDown()
                            }
                        }
                        synchronized(sampleLock) {
                            if (recording) {
                                if (previousArrival != 0L) intervals += arrival - previousArrival
                                previousArrival = arrival
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                callbackFailure.compareAndSet(null, error)
            }
        }, handler)
        try {
            testStorageContext().use { context ->
                val identity = File(context.filesDir, "owned-performance-test.nds").absolutePath
                try {
                    DsSession.setLayout(DsSession.LAYOUT_STACKED)
                    DsSession.setSurface(reader.surface)
                    assertTrue("Requested DS renderer did not start", DsSession.start(
                        context, romPath, identity, renderScale = scale, renderer = renderer
                    ))
                    assertEquals("Wrong renderer selected", renderer, DsSession.currentRenderer())
                    assertEquals("Wrong render scale selected", scale, DsSession.currentRenderScale())
                    if (checkpoint != null) {
                        require(checkpoint.isFile)
                        DsSession.pause()
                        assertTrue("Cannot restore the shared measured scene", EmuCoreDsNative.nativeLoadState(checkpoint.path))
                        DsSession.resume()
                    }
                    awaitFrames(warmup, callbackFailure)
                    if (nfsProbe) {
                        // Intro screens can ignore input; boot must complete before navigation.
                        awaitFrames(600, callbackFailure)
                        EmuCoreDsNative.nativeTouch(128, 96)
                        try { awaitFrames(6, callbackFailure) }
                        finally { EmuCoreDsNative.nativeReleaseTouch() }
                        awaitFrames(180, callbackFailure)
                        pressAndRelease(3, callbackFailure) // DS KEYINPUT Start bit
                        awaitFrames(90, callbackFailure)
                        pressAndRelease(0, callbackFailure) // DS KEYINPUT A bit
                        awaitFrames(warmup, callbackFailure)
                    }
                    if (navigation.isNotEmpty()) {
                        awaitFrames(360, callbackFailure)
                        navigation.forEach { bit ->
                            pressAndRelease(bit, callbackFailure)
                            awaitFrames(90, callbackFailure)
                        }
                        awaitFrames(180, callbackFailure)
                    }
                    val nativeStart = EmuCoreDsNative.nativeGetFrameCount()
                    val imagesStart = images.get()
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    synchronized(sampleLock) { previousArrival = 0L; recording = true }
                    movementProbe = moving
                    awaitFrames(steady, callbackFailure)
                    movementProbe = false
                    EmuCoreDsNative.nativeSetKeyMask(0xFFF)
                    val endedAt = SystemClock.elapsedRealtimeNanos()
                    val nativeEnd = EmuCoreDsNative.nativeGetFrameCount()
                    val imagesEnd = images.get()
                    val samples = synchronized(sampleLock) { recording = false; intervals.toList() }
                    assertTrue("No steady presentation progress", imagesEnd - imagesStart >= 2 && samples.isNotEmpty())
                    assertTrue("Native runtime stopped during measurement", EmuCoreDsNative.nativeIsRunning())
                    val sorted = samples.sorted()
                    fun percentile(percent: Int): Double = sorted[
                        (((sorted.size - 1).toLong() * percent + 99L) / 100L).toInt()
                    ] / 1_000_000.0
                    val report = JSONObject().apply {
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("hardware", Build.HARDWARE)
                        put("fingerprint", Build.FINGERPRINT)
                        put("gpuDriver", gpu)
                        put("core", EmuCoreDsNative.nativeGetVersion())
                        put("renderer", DsSession.currentRenderer())
                        put("requestedRenderer", renderer)
                        put("navigationBits", JSONArray(navigation))
                        put("movementProbe", moving)
                        put("scale", DsSession.currentRenderScale())
                        put("surface", "256x386 ImageReader RGBA8888")
                        put("timingClock", "elapsedRealtimeNanos at ImageReader acquireNextImage")
                        put("probe", when {
                            checkpoint != null -> "shared checkpoint; frame-relative run/jump/fire=$moving"
                            moving -> "navigation attempt; frame-relative run/jump/fire"
                            nfsProbe -> "NFS touch/Start/A navigation attempt; verify scene PNG"
                            else -> "no input"
                        })
                        put("warmupNativeFrames", warmup)
                        put("elapsedMs", (endedAt - startedAt) / 1_000_000.0)
                        put("nativeFrames", nativeEnd - nativeStart)
                        put("images", imagesEnd - imagesStart)
                        put("arrivalIntervals", samples.size)
                        put("arrivalAvgMs", samples.average() / 1_000_000.0)
                        put("arrivalP95Ms", percentile(95))
                        put("arrivalP99Ms", percentile(99))
                        put("arrivalMaxMs", sorted.last() / 1_000_000.0)
                        put("arrivalIntervalsNs", JSONArray(samples))
                    }
                    val file = File(output, "ds-$renderer-${scale}x-${System.currentTimeMillis()}.json")
                    file.writeText(report.toString(2))
                    report.remove("arrivalIntervalsNs")
                    Log.i("DsPerformance", "$report report=${file.absolutePath}")
                    println("DsPerformance $report report=${file.absolutePath}")
                    if (captureDirectory != null) {
                        captureRequested.set(true)
                        assertTrue("Scene capture timed out", captureDone.await(10, TimeUnit.SECONDS))
                        callbackFailure.get()?.let { throw AssertionError("Scene capture failed", it) }
                    }
                } finally {
                    movementProbe = false
                    EmuCoreDsNative.nativeSetKeyMask(0xFFF)
                    DsSession.stop()
                    DsSession.setSurface(null)
                }
            }
        } finally {
            reader.setOnImageAvailableListener(null, null)
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            try {
                assertTrue("Image callbacks did not drain", drained.await(5, TimeUnit.SECONDS))
            } finally {
                reader.close()
                consumer.quitSafely()
                consumer.join(5_000)
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
            }
        }
    }

    private fun awaitFrames(count: Int, failure: AtomicReference<Throwable?>) {
        val first = EmuCoreDsNative.nativeGetFrameCount()
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (EmuCoreDsNative.nativeGetFrameCount() - first < count) {
            if (movementProbe) {
                // Identical guest-frame-based controls for both backends: run,
                // shoot, and alternate jump/release without timing by wall clock.
                val frame = EmuCoreDsNative.nativeGetFrameCount() - first
                val held = (1 shl 4) or (1 shl 0) or if (frame % 60 < 24) (1 shl 1) else 0
                val mask = 0xFFF and held.inv()
                if (mask != lastProbeMask) {
                    EmuCoreDsNative.nativeSetKeyMask(mask)
                    lastProbeMask = mask
                }
            }
            failure.get()?.let { throw AssertionError("Image consumer failed", it) }
            assertTrue("DS stopped while waiting for progress", EmuCoreDsNative.nativeIsRunning())
            assertTrue("Native frame progress timed out", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(10)
        }
    }

    private fun pressAndRelease(bit: Int, failure: AtomicReference<Throwable?>) {
        try {
            EmuCoreDsNative.nativeSetKeyMask(0xFFF and (1 shl bit).inv())
            awaitFrames(6, failure)
        } finally {
            EmuCoreDsNative.nativeSetKeyMask(0xFFF)
        }
    }

    private fun queryGpuDriver(): String = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = requireNotNull(configs[0])
        val surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            listOf(GLES20.GL_VENDOR, GLES20.GL_RENDERER, GLES20.GL_VERSION)
                .joinToString(" | ") { GLES20.glGetString(it).orEmpty() }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglTerminate(display)
        }
    }.getOrElse { "unavailable: ${it.javaClass.simpleName}: ${it.message}" }
}
