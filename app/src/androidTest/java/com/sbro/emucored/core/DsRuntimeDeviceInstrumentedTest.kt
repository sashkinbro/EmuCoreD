// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e dsRomPath <readable user-owned DS ROM path or persisted content URI>. */
@RunWith(AndroidJUnit4::class)
class DsRuntimeDeviceInstrumentedTest {
    @Test(timeout = 180_000)
    fun realRomPresentsPausesSavesLoadsAndRestartsAcrossBothRenderers() {
        val romPath = InstrumentationRegistry.getArguments().getString("dsRomPath").orEmpty()
        assumeTrue("Supply dsRomPath to run the optional real-ROM regression", romPath.isNotBlank())
        assertTrue("DS JNI library must load", EmuCoreDsNative.isLoaded)
        assertFalse("Test must own the only DS session", DsSession.isActive)
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        testStorageContext().use { context ->
            val identity = File(context.filesDir, "owned-runtime-test.nds").absolutePath
            val consumer = HandlerThread("DsRuntimeTest-Images").apply { start() }
            val handler = Handler(consumer.looper)
            val images = AtomicLong()
            val reader = ImageReader.newInstance(256, 384, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { images.incrementAndGet() }
            }, handler)
            try {
                DsSession.setLayout(DsSession.LAYOUT_STACKED)
                DsSession.setSurface(reader.surface)
                assertTrue("Software start failed", DsSession.start(context, romPath, identity, renderer = 13))
                exerciseRunningSession("Software", context, identity, images)
                // A replacement launch must not steal ownership from the existing native worker.
                assertFalse("Second launch replaced an owned session", DsSession.start(context, romPath, identity, renderer = 14))
                assertTrue(DsSession.isActive)
                assertTrue(EmuCoreDsNative.nativeIsRunning())

                DsSession.pause()
                val guestFrames = awaitSettledFrame("Before renderer switch")
                assertTrue("Test must preserve a progressed guest", guestFrames > 0)
                assertTrue(DsSession.saveState(8))
                val guestBeforeSwitch = guestStateDigest(File(requireNotNull(DsSession.statePath(8))))
                DsSession.setSwapScreens(true)
                assertTrue("Switch to Vulkan failed", DsSession.setRenderer(14))
                assertEquals(14, DsSession.currentRenderer())
                assertTrue("Renderer switch resumed a paused menu", DsSession.isPaused)
                assertTrue("Renderer switch lost screen swap", DsSession.currentSwapScreens())
                assertFrameStable("Paused renderer switch", 0, 200)
                assertTrue(DsSession.saveState(9))
                assertArrayEquals("Renderer switch reset or advanced guest RAM/timeline",
                    guestBeforeSwitch, guestStateDigest(File(requireNotNull(DsSession.statePath(9)))))
                assertTrue("Successful switch retained a temporary recovery state",
                    context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("ds-renderer-") })
                DsSession.setSwapScreens(false)
                DsSession.resume()
                exerciseRunningSession("Vulkan", context, identity, images)
                stopAndAssertReleased()

                // Reuse after a complete stop catches retained threads, surface and native-state ownership.
                DsSession.setSurface(reader.surface)
                assertTrue("Software restart failed", DsSession.start(context, romPath, identity, renderer = 13))
                awaitProgress("Software restart", images)
                stopAndAssertReleased()
            } finally {
                DsSession.stop()
                DsSession.setSurface(null)
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
    }

    /** NDSG contains guest RAM, CPU timing and NumFrames; renderer caches live in other sections. */
    private fun guestStateDigest(state: File): ByteArray {
        val bytes = state.readBytes()
        assertTrue("Missing melonDS state header", bytes.size > 32)
        assertEquals("MELN", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("NDSG", String(bytes, 16, 4, Charsets.US_ASCII))
        val length = ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertTrue("Invalid NDSG section length", length > 16 && length <= bytes.size - 16)
        return MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(32, 16 + length))
    }

    private fun exerciseRunningSession(label: String, context: TestStorageContext, identity: String, images: AtomicLong) {
        awaitProgress(label, images)
        DsSession.pause()
        val pausedFrame = awaitSettledFrame(label)
        assertFrameStable("$label pause", pausedFrame, 200)
        assertTrue("$label paused session lost ownership", DsSession.isActive)
        assertTrue("$label save failed", DsSession.saveState(1))
        val state = File(requireNotNull(DsSession.statePath(1)))
        assertTrue("State escaped isolated storage", state.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertTrue("$label state is empty", state.isFile && state.length() > 0)
        val metadata = JSONObject(File("${state.absolutePath}.json").readText())
        assertEquals(identity, metadata.getString("gamePath"))
        assertEquals("NDS", metadata.getString("platform"))
        assertEquals(1, metadata.getInt("slot"))
        assertTrue("$label load failed", DsSession.loadState(1))
        assertFrameStable("$label paused load", pausedFrame, 100)
        DsSession.resume()
        awaitProgress("$label resume after load", images)
    }

    private fun awaitProgress(label: String, images: AtomicLong) {
        val initialFrames = EmuCoreDsNative.nativeGetFrameCount()
        val initialImages = images.get()
        await("$label did not advance and present frames", 30_000) {
            EmuCoreDsNative.nativeGetFrameCount() >= initialFrames + 4 && images.get() >= initialImages + 2
        }
        assertTrue("$label native runtime stopped", EmuCoreDsNative.nativeIsRunning())
    }

    private fun awaitSettledFrame(label: String): Long {
        var lastFrame = EmuCoreDsNative.nativeGetFrameCount()
        var stableSince = SystemClock.uptimeMillis()
        await("$label did not finish its in-flight frame after pause", 10_000) {
            val currentFrame = EmuCoreDsNative.nativeGetFrameCount()
            if (currentFrame != lastFrame) {
                lastFrame = currentFrame
                stableSince = SystemClock.uptimeMillis()
            }
            SystemClock.uptimeMillis() - stableSince >= 150
        }
        return lastFrame
    }

    private fun assertFrameStable(label: String, expected: Long, durationMs: Long) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals("$label advanced the emulated guest", expected, EmuCoreDsNative.nativeGetFrameCount())
            Thread.sleep(10)
        }
    }

    private fun stopAndAssertReleased() {
        DsSession.stop()
        DsSession.stop()
        assertFalse(DsSession.isActive)
        assertFalse(EmuCoreDsNative.nativeIsRunning())
        assertFalse(DsSession.saveState(1))
        assertFalse(DsSession.loadState(1))
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
        assertTrue(message, condition())
    }
}
