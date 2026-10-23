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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.gb.SameBoySessionEngine
import com.sbro.emucored.core.gba.MgbaSessionEngine
import com.sbro.emucored.core.nes.NestopiaSessionEngine
import com.sbro.emucored.core.snes.BsnesSessionEngine
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Kotlin->JNI adapters, real Android windows, isolated original programs. */
@RunWith(AndroidJUnit4::class)
class FrameCoreAdaptersDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val windowVersion = mutableIntStateOf(0)
    private var ready = CountDownLatch(1)
    private lateinit var view: SurfaceView

    private fun engine(platform: ConsolePlatform): CoreSessionEngine = when (platform) {
        ConsolePlatform.GAMECUBE, ConsolePlatform.WII -> DolphinSessionEngine()
        ConsolePlatform.GBA -> MgbaSessionEngine()
        ConsolePlatform.GB, ConsolePlatform.GBC -> SameBoySessionEngine()
        ConsolePlatform.NES -> NestopiaSessionEngine()
        ConsolePlatform.SNES -> BsnesSessionEngine()
        else -> error("Unsupported fixture $platform")
    }

    @Test(timeout = 240_000)
    fun allFiveConsoleAdaptersRenderRestoreRebindAndReopenInOneProcess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frameCoreAdapters") == "true")
        testStorageContext().use { context ->
            compose.setContent {
                key(windowVersion.intValue) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                        SurfaceView(activity).apply {
                            keepScreenOn = true
                            view = this
                            holder.setFixedSize(640, 480)
                            val latch = ready
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) = Unit
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) { latch.countDown() }
                                override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                            })
                        }
                    })
                }
            }
            val consoles = listOf(ConsolePlatform.GBA, ConsolePlatform.GB, ConsolePlatform.GBC,
                ConsolePlatform.NES, ConsolePlatform.SNES)
            for (platform in consoles + consoles.reversed()) {
                assertTrue("$platform window unavailable", ready.await(10, TimeUnit.SECONDS))
                val rom = File(context.cacheDir, "original.${platform.id}").apply {
                    writeBytes(OriginalConsolePrograms.forPlatform(platform))
                }
                val request = EngineLaunchRequest(context, platform, rom.path, "stable-${platform.id}", context.filesDir,
                    options = mapOf("audio_enabled" to "false"), startPaused = true)
                val core = engine(platform)
                try {
                    core.attachSurface(view.holder.surface, 640, 480)
                    assertEquals("$platform start", EngineStartResult.Started, core.start(request))
                    assertTrue(core.isPaused)
                    assertEquals(0L, core.frameCount())
                    assertTrue(core.setSpeed(4f))
                    assertFalse(core.setSpeed(Float.NaN))
                    core.resume()
                    val warmup = if (platform == ConsolePlatform.GB || platform == ConsolePlatform.GBC) 360 else 10
                    withTimeout(20_000) { while (core.frameCount() < warmup) delay(10) }
                    core.pause()
                    val paused = core.frameCount()
                    delay(60)
                    assertEquals("$platform paused guest advanced", paused, core.frameCount())
                    assertPixel(platform)
                    assertFalse(core.applyOption("unsupported", "true"))
                    assertTrue(core.applyOption("video_filter", "linear"))
                    val state = File(context.cacheDir, "${platform.id}.state")
                    assertTrue("$platform completed state", core.saveState(state))
                    assertTrue(state.length() > 128)
                    assertTrue("$platform restore", core.loadState(state))
                    val corrupt = File(context.cacheDir, "${platform.id}-bad.state").apply {
                        writeBytes(state.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 0x80).toByte() })
                    }
                    assertFalse("$platform accepted corrupt state", core.loadState(corrupt))
                    core.setButton(0, EngineButton.A, true)
                    core.setButton(0, EngineButton.A, false)
                    if (platform == ConsolePlatform.SNES) {
                        val other = engine(platform)
                        try {
                            assertTrue("Concurrent bsnes must reject a second owner", other.start(request) is EngineStartResult.Failed)
                            assertTrue("Rejected owner stopped the first guest", core.isRunning)
                            assertTrue(core.saveState(state))
                        } finally { other.stop() }
                    }
                    core.attachSurface(null, 0, 0)
                    assertTrue(core.applyOption("video_filter", "nearest")) // drains old surface release
                    ready = CountDownLatch(1)
                    compose.runOnIdle { windowVersion.intValue++ }
                    compose.waitForIdle()
                    assertTrue("$platform replacement window unavailable", ready.await(10, TimeUnit.SECONDS))
                    core.attachSurface(view.holder.surface, 640, 480)
                    core.resume()
                    withTimeout(10_000) { while (core.frameCount() < paused + 3) delay(10) }
                    core.pause()
                    assertPixel(platform)
                    assertTrue("$platform state after window recreation", core.loadState(state))
                } finally {
                    core.stop()
                    core.stop()
                    assertFalse("$platform owner remained after stop", core.isRunning)
                }
                println("FrameCoreAdapters PASS $platform frames/state/pixels/rebind/recreation")
            }
        }
    }

    @Test(timeout = 120_000)
    fun gameCubeAndWiiUseTheAppAdapterAndIgnoreRetiredSessionCommands() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frameCoreAdapters") == "true")
        testStorageContext().use { context ->
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
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val retired = mutableListOf<CoreSessionEngine>()
            for (backend in listOf("vulkan", "opengl")) {
                for (platform in listOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII)) {
                    val rom = File(context.cacheDir, "original-${platform.id}.dol").apply { writeBytes(OriginalConsolePrograms.forPlatform(platform)) }
                    val core = engine(platform)
                    try {
                        core.attachSurface(view.holder.surface, 640, 480)
                        val request = EngineLaunchRequest(context, platform, rom.path, "dolphin-${platform.id}",
                            File(context.filesDir, "${platform.id}-$backend"), mapOf("graphics_backend" to backend), true)
                        assertEquals("$platform/$backend start", EngineStartResult.Started, core.start(request))
                        assertTrue(core.isPaused)
                        for (old in retired) {
                            old.requestStop()
                            old.attachSurface(null, 0, 0)
                            old.setButton(0, EngineButton.A, true)
                            assertFalse(old.setSpeed(2f))
                            assertFalse(old.applyOption("resolution", "2"))
                            assertFalse(old.saveState(File(context.cacheDir, "stale.state")))
                            old.stop()
                            assertTrue("Retired session touched the new owner", core.isRunning)
                        }
                        core.resume()
                        withTimeout(20_000) { while (core.frameCount() < 1) delay(10) }
                        core.pause()
                        assertPixel(platform)
                        val state = File(context.cacheDir, "${platform.id}-$backend.state")
                        assertTrue(core.saveState(state))
                        assertTrue(state.length() > 128)
                        assertTrue(core.loadState(state))
                        core.attachSurface(null, 0, 0)
                        core.resume()
                        assertTrue(core.isPaused)
                        core.attachSurface(view.holder.surface, 640, 480)
                        core.resume()
                        assertFalse(core.isPaused)
                        core.pause()
                        assertPixel(platform)
                    } finally { core.stop(); retired += core }
                    println("DolphinAppAdapter PASS $platform/$backend retired=${retired.size}")
                }
            }
            val entered = CountDownLatch(1)
            val continueStart = CountDownLatch(1)
            val blockedDirectory = object : File(context.filesDir, "cancelled-start") {
                override fun mkdirs(): Boolean {
                    entered.countDown()
                    check(continueStart.await(5, TimeUnit.SECONDS))
                    return super.mkdirs()
                }
            }
            val rom = File(context.cacheDir, "cancelled.dol").apply { writeBytes(OriginalConsolePrograms.forPlatform(ConsolePlatform.GAMECUBE)) }
            val core = DolphinSessionEngine()
            core.attachSurface(view.holder.surface, 640, 480)
            val request = EngineLaunchRequest(context, ConsolePlatform.GAMECUBE, rom.path, "cancelled-start", blockedDirectory, startPaused = true)
            val start = async(Dispatchers.Default) { core.start(request) }
            try {
                assertTrue("Did not reach the claimed startup", entered.await(5, TimeUnit.SECONDS))
                start.cancel()
                continueStart.countDown()
                withTimeout(15_000) { start.join() }
                assertFalse("Cancelled startup retained native ownership", core.isRunning)
                val replacement = DolphinSessionEngine()
                try {
                    replacement.attachSurface(view.holder.surface, 640, 480)
                    assertEquals(EngineStartResult.Started,
                        replacement.start(request.copy(userDirectory = File(context.filesDir, "after-cancelled-start"))))
                } finally { replacement.stop() }
            } finally {
                continueStart.countDown()
                start.cancel()
                core.stop()
            }
        }
    }

    @Test(timeout = 60_000)
    fun immediatelyStoppedPausedCoresReleaseNativeOwnershipBeforeReopening() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frameCoreAdapters") == "true")
        testStorageContext().use { context ->
            for (platform in listOf(ConsolePlatform.GBA, ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES, ConsolePlatform.SNES)) {
                val rom = File(context.cacheDir, "original.${platform.id}").apply { writeBytes(OriginalConsolePrograms.forPlatform(platform)) }
                repeat(3) {
                    val core = engine(platform)
                    try {
                        assertEquals("$platform reopen $it", EngineStartResult.Started,
                            core.start(EngineLaunchRequest(context, platform, rom.path, "immediate-${platform.id}", context.filesDir, startPaused = true)))
                        core.requestStop()
                    } finally { core.stop() }
                    assertFalse(core.isRunning)
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun unsupportedBootOptionsFailAndReleaseTheSessionForRetry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("frameCoreAdapters") == "true")
        testStorageContext().use { context ->
            val rom = File(context.cacheDir, "original.gba").apply { writeBytes(OriginalConsolePrograms.forPlatform(ConsolePlatform.GBA)) }
            val core = MgbaSessionEngine()
            val request = EngineLaunchRequest(context, ConsolePlatform.GBA, rom.path, "bad-options", context.filesDir, startPaused = true)
            try {
                assertTrue("Unsupported settings were silently accepted", core.start(request.copy(options = mapOf("unsupported" to "true"))) is EngineStartResult.Failed)
                assertFalse(core.isRunning)
                assertEquals(EngineStartResult.Started, core.start(request))
            } finally { core.stop() }
        }
    }

    private fun assertPixel(platform: ConsolePlatform) {
        val pixels = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1)
            val result = AtomicInteger(-1)
            PixelCopy.request(view, pixels, { result.set(it); latch.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("$platform PixelCopy timed out", latch.await(5, TimeUnit.SECONDS))
            assertEquals("$platform PixelCopy", PixelCopy.SUCCESS, result.get())
            val color = pixels.getPixel(320, 240)
            when (platform) {
                ConsolePlatform.GAMECUBE, ConsolePlatform.WII -> {
                    val red = pixels.getPixel(100, 240)
                    val blue = pixels.getPixel(540, 240)
                    assertTrue("$platform left bar red", Color.red(red) > Color.green(red) + 70)
                    assertTrue("$platform middle bar green", Color.green(color) > Color.red(color) + 70)
                    assertTrue("$platform right bar blue", Color.blue(blue) > Color.green(blue) + 70)
                }
                ConsolePlatform.GBA, ConsolePlatform.SNES -> assertTrue("$platform must show red, got ${Integer.toHexString(color)}", Color.red(color) > 200 && Color.green(color) < 60 && Color.blue(color) < 60)
                ConsolePlatform.NES -> assertTrue("NES must show blue, got ${Integer.toHexString(color)}", Color.blue(color) > Color.red(color) + 60)
                ConsolePlatform.GB, ConsolePlatform.GBC -> assertTrue("$platform must show white, got ${Integer.toHexString(color)}", Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) > 180)
                else -> error("No pixel contract")
            }
        } finally { pixels.recycle() }
    }
}
