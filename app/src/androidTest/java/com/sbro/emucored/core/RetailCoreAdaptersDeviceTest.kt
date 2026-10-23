// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.gb.SameBoySessionEngine
import com.sbro.emucored.core.gba.MgbaSessionEngine
import com.sbro.emucored.core.nes.NestopiaSessionEngine
import com.sbro.emucored.core.snes.BsnesSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in owned retail ROMs, never bundled. All saves are in a disposable test namespace. */
@RunWith(AndroidJUnit4::class)
class RetailCoreAdaptersDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 180_000) fun gameCube() = exercise(ConsolePlatform.GAMECUBE)
    @Test(timeout = 180_000) fun wii() = exercise(ConsolePlatform.WII)
    @Test(timeout = 180_000) fun gameBoyAdvance() = exercise(ConsolePlatform.GBA)
    @Test(timeout = 180_000) fun gameBoy() = exercise(ConsolePlatform.GB)
    @Test(timeout = 180_000) fun gameBoyColor() = exercise(ConsolePlatform.GBC)
    @Test(timeout = 180_000) fun nes() = exercise(ConsolePlatform.NES)
    @Test(timeout = 180_000) fun snes() = exercise(ConsolePlatform.SNES)

    private fun exercise(platform: ConsolePlatform) = runBlocking {
        val directory = InstrumentationRegistry.getArguments().getString("retailCoreRomDir").orEmpty()
        assumeTrue("Supply retailCoreRomDir containing owned checksummed samples", directory.isNotBlank())
        val extension = when (platform) {
            ConsolePlatform.GAMECUBE, ConsolePlatform.WII -> "rvz"
            ConsolePlatform.SNES -> "sfc"
            else -> platform.id
        }
        val source = File(directory, "${platform.id}.$extension")
        assertTrue("Missing owned $platform sample", source.isFile && source.length() > 0)
        if (platform.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN)
            assertEquals("Actual RVZ header disagrees with the selected console", platform, DolphinSessionEngine.probePlatform(source.path))
        val core: CoreSessionEngine = when (platform) {
            ConsolePlatform.GAMECUBE, ConsolePlatform.WII -> DolphinSessionEngine()
            ConsolePlatform.GBA -> MgbaSessionEngine()
            ConsolePlatform.GB, ConsolePlatform.GBC -> SameBoySessionEngine()
            ConsolePlatform.NES -> NestopiaSessionEngine()
            ConsolePlatform.SNES -> BsnesSessionEngine()
            else -> error("Unsupported retail fixture")
        }
        testStorageContext().use { context ->
            val ready = CountDownLatch(1)
            lateinit var view: SurfaceView
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                    SurfaceView(activity).apply {
                        keepScreenOn = true
                        view = this
                        holder.setFixedSize(640, 480)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = Unit
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) { ready.countDown() }
                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                        })
                    }
                })
            }
            assertTrue("$platform presentation window unavailable", ready.await(10, TimeUnit.SECONDS))
            val options = if (platform.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN)
                mapOf("audio_enabled" to "false", "graphics_backend" to "vulkan") else mapOf("audio_enabled" to "false")
            val request = EngineLaunchRequest(context, platform, source.path, "retail-${source.name}", context.filesDir,
                options, startPaused = true)
            try {
                core.attachSurface(view.holder.surface, 640, 480)
                assertEquals("$platform retail boot failed", EngineStartResult.Started, core.start(request))
                assertTrue(core.isPaused)
                core.resume()
                val warmup = if (platform in setOf(ConsolePlatform.GB, ConsolePlatform.GBC)) 400 else 180
                withTimeout(45_000) { while (core.frameCount() < warmup) delay(25) }
                withTimeout(15_000) { while (!hasVisibleGameImage(view)) delay(100) }
                core.setButton(0, EngineButton.START, true)
                delay(120)
                core.setButton(0, EngineButton.START, false)
                core.setButton(0, EngineButton.A, true)
                delay(120)
                core.setButton(0, EngineButton.A, false)
                core.pause()
                delay(100)
                val paused = core.frameCount()
                delay(100)
                assertEquals("$platform guest kept presenting while paused", paused, core.frameCount())
                val state = File(context.cacheDir, "retail-${platform.id}.state")
                assertTrue("$platform state did not complete", core.saveState(state))
                assertTrue(state.isFile && state.length() > 128)
                assertTrue("$platform valid state rejected", core.loadState(state))
                val invalid = File(context.cacheDir, "invalid.state").apply { writeBytes(ByteArray(256)) }
                assertFalse("$platform malformed state accepted", core.loadState(invalid))
                assertTrue("$platform could not recover after malformed state", core.loadState(state))
                core.stop()
                assertFalse(core.isRunning)
                core.attachSurface(view.holder.surface, 640, 480)
                assertEquals("$platform second retail boot failed", EngineStartResult.Started, core.start(request))
                assertTrue("$platform saved state did not survive recreation", core.loadState(state))
                core.resume()
                withTimeout(15_000) { while (!hasVisibleGameImage(view)) delay(100) }
                println("RetailCoreAdapter PASS $platform boot/video/input/pause/state/corrupt-reject/reopen")
            } finally { core.stop(); core.attachSurface(null, 0, 0) }
        }
    }

    private fun hasVisibleGameImage(view: SurfaceView): Boolean {
        val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val done = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(view, image, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("Retail frame readback timed out", done.await(5, TimeUnit.SECONDS))
            if (result != PixelCopy.SUCCESS) return false
            val colors = mutableSetOf<Int>()
            for (y in 80 until 400 step 20) for (x in 80 until 560 step 20) {
                val color = image.getPixel(x, y)
                if (Color.red(color) + Color.green(color) + Color.blue(color) > 40) colors += color
            }
            return colors.size > 1
        } finally { image.recycle() }
    }
}
