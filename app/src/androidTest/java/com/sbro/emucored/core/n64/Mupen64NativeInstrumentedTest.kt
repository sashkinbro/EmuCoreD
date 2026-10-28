// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.n64

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mupen64NativeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 120_000) fun rendersOwnedGameAndRestoresStateOnARealSurface() {
        val path = InstrumentationRegistry.getArguments().getString("n64RomPath").orEmpty()
        assumeTrue("Provide an owned N64 game via n64RomPath", path.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "n64-native-test-${System.nanoTime()}").apply { check(mkdirs()) }
        val ready = CountDownLatch(1)
        lateinit var view: SurfaceView
        var surface: Surface? = null
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                SurfaceView(it).also { created ->
                    view = created
                    created.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface; ready.countDown() }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                }
            })
        }
        assertTrue("Test surface unavailable", ready.await(15, TimeUnit.SECONDS))
        assertEquals(1, Mupen64Native.apiVersion())
        val handle = Mupen64Native.nativeCreate(path, directory.path)
        assertNotEquals("N64 guest failed to start", 0L, handle)
        try {
            Mupen64Native.nativeSurface(handle, surface)
            repeat(390) { assertTrue("N64 guest stopped at frame $it", Mupen64Native.nativeFrame(handle)) }
            val state = File(directory, "state.bin")
            assertTrue("N64 state save failed", Mupen64Native.nativeStateFile(handle, state.path, true))
            assertTrue("N64 state load failed", Mupen64Native.nativeStateFile(handle, state.path, false))
            repeat(30) { assertTrue(Mupen64Native.nativeFrame(handle)) }
            val callbackThread = HandlerThread("n64-pixel-copy").apply { start() }
            try {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var result = PixelCopy.ERROR_UNKNOWN
                PixelCopy.request(view, bitmap, { result = it; copied.countDown() }, Handler(callbackThread.looper))
                assertTrue("PixelCopy timed out", copied.await(10, TimeUnit.SECONDS))
                assertEquals("PixelCopy failed", PixelCopy.SUCCESS, result)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("N64 surface is black", pixels.any { it and 0x00ffffff != 0 })
            } finally { callbackThread.quitSafely() }
        } finally {
            Mupen64Native.nativeSurface(handle, null)
            Mupen64Native.nativeDestroy(handle)
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 120_000) fun restartsTheOwnedGameInTheSameProcess() {
        val path = InstrumentationRegistry.getArguments().getString("n64RomPath").orEmpty()
        assumeTrue("Provide an owned N64 game via n64RomPath", path.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "n64-restart-test-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            repeat(2) {
                val handle = Mupen64Native.nativeCreate(path, directory.path)
                assertNotEquals("N64 restart failed", 0L, handle)
                try { repeat(120) { assertTrue(Mupen64Native.nativeFrame(handle)) } }
                finally { Mupen64Native.nativeDestroy(handle) }
            }
            assertTrue("Battery data was not persisted", File(directory, "battery.srm").isFile)
        } finally { directory.deleteRecursively() }
    }
}
