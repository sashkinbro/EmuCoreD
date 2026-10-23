package com.sbro.emucored.core

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e dsSwapSentinel true. Uses an actual window surface, independently of ImageReader. */
@RunWith(AndroidJUnit4::class)
class DsScreenSwapSurfaceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 120_000)
    fun physicalScreenColorsAndPausedSwapOnWindowSurface() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsSwapSentinel") == "true")
        assertTrue(EmuCoreDsNative.isLoaded)
        assertFalse(DsSession.isActive)
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        val ready = CountDownLatch(1)
        lateinit var view: SurfaceView
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                SurfaceView(activity).apply {
                    view = this
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
        assertTrue("Window surface was not created", ready.await(10, TimeUnit.SECONDS))
        testStorageContext().use { context ->
            val rom = File(context.filesDir, "original-window-sentinel.nds").apply { writeBytes(createDsScreenSentinelRom()) }
            try {
                // Reuse one window in both directions, so an old EGL/Vulkan
                // connection cannot silently force the next backend into fallback.
                for (renderer in listOf(13, 14, 13)) for (scale in listOf(1, 2)) {
                    DsSession.setLayout(DsSession.LAYOUT_STACKED)
                    DsSession.setSurface(view.holder.surface)
                    assertTrue("Window start failed renderer=$renderer scale=$scale", DsSession.start(
                        context, rom.absolutePath, renderScale = scale, renderer = renderer
                    ))
                    awaitColors(view, false, "Default renderer=$renderer scale=$scale")
                    DsSession.pause()
                    Thread.sleep(200)
                    val pausedFrame = EmuCoreDsNative.nativeGetFrameCount()
                    DsSession.setSwapScreens(true)
                    awaitColors(view, true, "Swapped renderer=$renderer scale=$scale")
                    assertEquals("Window swap advanced the paused guest", pausedFrame, EmuCoreDsNative.nativeGetFrameCount())
                    DsSession.setSwapScreens(false)
                    awaitColors(view, false, "Restored renderer=$renderer scale=$scale")
                    assertEquals("Window restore advanced the paused guest", pausedFrame, EmuCoreDsNative.nativeGetFrameCount())
                    DsSession.stop()
                }
            } finally {
                DsSession.stop()
                DsSession.setSurface(null)
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
            }
        }
    }

    private fun awaitColors(view: SurfaceView, swapped: Boolean, label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var last = "no copied pixels"
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1)
                var result = PixelCopy.ERROR_UNKNOWN
                PixelCopy.request(view, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue("PixelCopy callback did not complete", done.await(5, TimeUnit.SECONDS))
                if (result == PixelCopy.SUCCESS) {
                    val contentHeight = minOf(view.height.toFloat(), view.width * 386f / 256f)
                    val contentTop = (view.height - contentHeight) / 2f
                    val upper = bitmap.getPixel(view.width / 2, (contentTop + contentHeight / 4f).toInt())
                    val lower = bitmap.getPixel(view.width / 2, (contentTop + contentHeight * 3f / 4f).toInt())
                    fun red(color: Int) = Color.red(color) > 200 && Color.green(color) < 40 && Color.blue(color) < 40
                    fun cyan(color: Int) = Color.red(color) < 40 && Color.green(color) > 200 && Color.blue(color) > 200
                    last = "upper=%08X lower=%08X".format(upper, lower)
                    if (if (swapped) red(upper) && cyan(lower) else cyan(upper) && red(lower)) return
                } else last = "PixelCopy result=$result"
            } finally {
                bitmap.recycle()
            }
            Thread.sleep(30)
        }
        throw AssertionError("$label: $last")
    }
}
