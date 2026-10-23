package com.sbro.emucored.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Opt in with -e dsPhaseSentinel true. No external ROM or frame-time performance assumptions. */
@RunWith(AndroidJUnit4::class)
class DsVramPhaseInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test(timeout = 120_000)
    fun softwareKeepsCorrelatedVramAndScrollInTheSameFrame() = exercise(13)

    @Test(timeout = 120_000)
    fun vulkanKeepsCorrelatedVramAndScrollInTheSameFrame() = exercise(14)

    private fun exercise(renderer: Int) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsPhaseSentinel") == "true")
        assertTrue(EmuCoreDsNative.isLoaded)
        assertFalse(DsSession.isActive)
        // Keep a resumed window for this headless ImageReader check. Otherwise
        // OEM background policies can kill the process between renderer cases.
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                TextView(context).apply {
                    keepScreenOn = true
                    gravity = Gravity.CENTER
                    textSize = 18f
                    text = "DS frame synchronization test\n${if (renderer == 14) "Vulkan" else "Software"} · 1× / 2×"
                }
            })
        }
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        testStorageContext().use { context ->
            val rom = File(context.filesDir, "original-correlated-phase.nds").apply {
                writeBytes(createDsScreenSentinelRom(animatedPhase = true))
            }
            val consumer = HandlerThread("DsVramPhase").apply { start() }
            val sampling = AtomicReference<CountDownLatch?>()
            val failures = AtomicInteger()
            val lastFailure = AtomicReference<String>()
            val reader = ImageReader.newInstance(256, 386, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val latch = sampling.get() ?: return@use
                    val plane = image.planes[0]
                    fun pixel(x: Int): Int {
                        val offset = 226 * plane.rowStride + x * plane.pixelStride
                        return ((plane.buffer.get(offset).toInt() and 255) shl 16) or
                            ((plane.buffer.get(offset + 1).toInt() and 255) shl 8) or
                            (plane.buffer.get(offset + 2).toInt() and 255)
                    }
                    val left = pixel(64)
                    val right = pixel(192)
                    val red = (left shr 16 and 255) > 200 && (left shr 8 and 255) < 40 && (left and 255) < 40
                    val cyan = (right shr 16 and 255) < 40 && (right shr 8 and 255) > 200 && (right and 255) > 200
                    if (!red || !cyan) {
                        failures.incrementAndGet()
                        lastFailure.set("left=%06X right=%06X".format(left, right))
                    }
                    latch.countDown()
                }
            }, Handler(consumer.looper))
            try {
                for (scale in listOf(1, 2)) {
                    DsSession.setLayout(DsSession.LAYOUT_STACKED)
                    DsSession.setSurface(reader.surface)
                    assertTrue(DsSession.start(context, rom.absolutePath, renderer = renderer, renderScale = scale))
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    while (EmuCoreDsNative.nativeGetFrameCount() < 60 && SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
                    assertTrue("Guest did not warm up", EmuCoreDsNative.nativeGetFrameCount() >= 60)
                    failures.set(0)
                    lastFailure.set(null)
                    val sampled = CountDownLatch(48)
                    sampling.set(sampled)
                    assertTrue("No 48 real rendered frames", sampled.await(30, TimeUnit.SECONDS))
                    sampling.set(null)
                    assertEquals("Mismatched VRAM/register phase renderer=$renderer scale=$scale ${lastFailure.get()}", 0, failures.get())
                    DsSession.stop()
                }
            } finally {
                sampling.set(null)
                DsSession.stop()
                DsSession.setSurface(null)
                reader.setOnImageAvailableListener(null, null)
                consumer.quitSafely()
                consumer.join(5_000)
                reader.close()
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
            }
        }
    }
}
