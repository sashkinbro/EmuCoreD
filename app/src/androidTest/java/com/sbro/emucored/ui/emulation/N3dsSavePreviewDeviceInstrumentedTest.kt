package com.sbro.emucored.ui.emulation

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.CoreRuntime
import com.sbro.emucored.core.CoreConfig
import com.sbro.emucored.core.EmuCoreNative
import com.sbro.emucored.core.NativeApp
import com.sbro.emucored.core.RendererDefaults
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.DisplayCrop
import com.sbro.emucored.data.SaveStateRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e 3dsRomPath <readable user-owned 3DS ROM path or persisted content URI>. */
@RunWith(AndroidJUnit4::class)
class N3dsSavePreviewDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 180_000)
    fun pausedNativeSaveCompletesWithoutGuestFramesAndProducesRealSurfacePreview() {
        val romPath = InstrumentationRegistry.getArguments().getString("3dsRomPath").orEmpty()
        assumeTrue("Supply 3dsRomPath to run the optional real3DS regression", romPath.isNotBlank())
        assertTrue("Bundled 3DS JNI library must load", EmuCoreNative.isLoaded)
        assertFalse("Test must own the only 3DS session", CoreRuntime.hasSession())
        val previousContext = NativeApp.getContext()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testStorageContext().use { context ->
            val surfaceReady = CountDownLatch(1)
            lateinit var gameSurface: SurfaceView
            compose.setContent {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { activity ->
                        SurfaceView(activity).apply {
                            gameSurface = this
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    CoreRuntime.rememberSurface(holder.surface, width, height)
                                    surfaceReady.countDown()
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            })
                        }
                    }
                )
            }
            assertTrue("3DS SurfaceView did not become ready", surfaceReady.await(10, TimeUnit.SECONDS))
            try {
                NativeApp.initializeOnce(context)
                // Exercise the frontend's actual config translation instead of the
                // upstream default INI (which may choose a different graphics API).
                val renderer = InstrumentationRegistry.getArguments().getString("3dsRenderer")
                    ?.toIntOrNull() ?: RendererDefaults.VULKAN
                CoreConfig.apply(CoreConfig.Values(
                    renderer = renderer, resolutionFactor = 1f, aspectRatio = 1,
                    displayCrop = DisplayCrop.None, audioVolume = 100,
                    audioFastForwardVolume = 100, audioMuted = true,
                    audioOutputLatencyMs = 60, audioMinimalOutputLatency = false,
                    frameSkip = 0, frameLimitEnabled = true, vSyncEnabled = false,
                    fastForwardSpeed = 2f, customTextures = false, cheatsEnabled = false,
                    performanceOverlay = false, mediatekAngleOpenGl = false
                ))
                val launchPath = requireNotNull(DocumentPathResolver.prepareGameLaunchPath(context, romPath))
                assertTrue("3DS boot failed: ${CoreRuntime.failure.value}", CoreRuntime.start(launchPath))
                await("3DS core did not produce guest video frames", 30_000) {
                    EmuCoreNative.getPerfStats().getOrNull(1)?.let { it > 0.0 } == true
                }
                val titleId = EmuCoreNative.getRunningTitleId()
                assertTrue("Use a save-capable title with a native program ID", titleId != 0L)
                val state = File(context.filesDir, "azahar/states/%016X.01.cst".format(java.util.Locale.US, titleId))
                assertFalse("State fixture must start empty", state.exists())
                CoreRuntime.pause()
                // Wait for the current video frame to settle, then reset the native performance window.
                await("3DS did not pause", 10_000) {
                    Thread.sleep(100)
                    EmuCoreNative.getPerfStats().getOrNull(1) == 0.0
                }
                assertTrue("JNI did not accept the paused save request", NativeApp.saveStateToSlot(1))
                await("Paused 3DS save never completed (JNI save is asynchronous)", 20_000) {
                    state.isFile && state.length() > 256L
                }
                val savedSize = state.length()
                Thread.sleep(150)
                assertEquals("State was still being written", savedSize, state.length())
                assertFalse("Published state retained a partial write", File("${state.absolutePath}.pending").exists())
                val userSlotContents = state.readBytes()
                assertEquals("Save advanced the paused guest", 0.0, EmuCoreNative.getPerfStats()[1], 0.0)
                assertTrue("Paused session lost ownership", CoreRuntime.hasSession())

                // A prepared/source identity need not be readable by the native loader after boot.
                // Current-slot paths must follow the running title that actually wrote the CST.
                NativeApp.setSaveStateIdentityPath(File(context.cacheDir, "unavailable-source.3ds").absolutePath)
                assertEquals(state.absolutePath, NativeApp.getCurrentSaveStatePath(1))
                val captured = runBlocking {
                    withContext(Dispatchers.Main) { captureSaveStatePreview(gameSurface, state.absolutePath) }
                }
                assertTrue("Production PixelCopy preview capture failed", captured)
                val preview = SaveStateRepository(context).getPreviewImagePath(state)
                assertNotNull("Repository did not discover 3DS preview sidecar", preview)
                val bitmap = BitmapFactory.decodeFile(preview)
                assertNotNull("3DS preview sidecar is not a decodable image", bitmap)
                requireNotNull(bitmap).let {
                    try {
                        assertTrue("Preview dimensions are invalid", it.width > 0 && it.height > 0)
                        assertTrue("Preview was not scaled for slot UI", it.width <= 512)
                    } finally { it.recycle() }
                }
                assertTrue("Paused load was not scheduled", NativeApp.loadStateFromSlot(1))
                Thread.sleep(250)
                assertEquals("Load advanced the paused guest", 0.0, EmuCoreNative.getPerfStats()[1], 0.0)
                val checkpoint = File(context.cacheDir, "owned-renderer-before.cst")
                val restored = File(context.cacheDir, "owned-renderer-after.cst")
                assertTrue("Paused checkpoint failed", EmuCoreNative.saveTemporaryState(checkpoint.absolutePath))
                val malformed = File(context.cacheDir, "owned-invalid-recovery.cst")
                malformed.writeBytes(checkpoint.readBytes().also { it[148] = (it[148].toInt() xor 1).toByte() })
                assertFalse("Recovery accepted a different build", EmuCoreNative.loadTemporaryState(malformed.absolutePath))
                malformed.writeBytes(byteArrayOf(1, 2, 3))
                assertFalse("Recovery accepted a truncated state", EmuCoreNative.loadTemporaryState(malformed.absolutePath))
                assertFalse("Recovery wrote into an unavailable directory", EmuCoreNative.saveTemporaryState(
                    File(context.cacheDir, "absent/recovery.cst").absolutePath))
                val alternateRenderer = if (renderer == 14) 12 else 14
                assertTrue("3DS renderer transaction failed", CoreRuntime.restartWithRenderer(alternateRenderer))
                assertEquals("Wrong native backend after restart", alternateRenderer, EmuCoreNative.getActiveRenderer())
                assertTrue("Renderer restart resumed the paused guest", CoreRuntime.isPaused())
                assertTrue("Restored checkpoint failed", EmuCoreNative.saveTemporaryState(restored.absolutePath))
                fun guestFingerprint(file: File): ByteArray {
                    val bytes = file.readBytes()
                    assertEquals("ECDREC01", String(bytes, 0, 8, Charsets.US_ASCII))
                    return bytes.copyOfRange(8, 136)
                }
                assertArrayEquals("Renderer restart changed guest RAM/VRAM/registers/timing", guestFingerprint(checkpoint), guestFingerprint(restored))
                assertTrue("Renderer checkpoint replaced a user slot", state.isFile)
                assertArrayEquals("Recovery changed the user's slot", userSlotContents, state.readBytes())
                CoreRuntime.resume()
                await("3DS did not resume after state load", 30_000) {
                    EmuCoreNative.getPerfStats().getOrNull(1)?.let { it > 0.0 } == true
                }
                assertTrue("Running renderer switch failed", CoreRuntime.restartWithRenderer(renderer))
                assertEquals(renderer, EmuCoreNative.getActiveRenderer())
                assertFalse("Running switch left the guest paused", CoreRuntime.isPaused())
                await("Guest did not continue after running switch", 30_000) {
                    EmuCoreNative.getPerfStats().getOrNull(1)?.let { it > 0.0 } == true
                }
                // Production exit requests stop before the later worker join.
                // Reuse this same window with the other API after that path.
                assertTrue("Stop request did not release UI graphics ownership", CoreRuntime.requestShutdown())
                CoreRuntime.shutdown()
                assertFalse(CoreRuntime.hasSession())
                CoreConfig.apply(requireNotNull(CoreConfig.currentValues()).copy(renderer = alternateRenderer))
                assertTrue("Fresh launch after exit failed", CoreRuntime.start(launchPath))
                assertEquals(alternateRenderer, EmuCoreNative.getActiveRenderer())
                CoreRuntime.pause()
                await("Fresh launch did not pause", 10_000) {
                    Thread.sleep(100)
                    EmuCoreNative.getPerfStats().getOrNull(1) == 0.0
                }
                // A directory at the reserved staging path forces a real open
                // failure. The previously completed user slot must survive.
                val blockedWrite = File("${state.absolutePath}.pending")
                assertTrue(blockedWrite.mkdir())
                try {
                    assertTrue(NativeApp.saveStateToSlot(1))
                    await("Failed save was not reported", 20_000) {
                        CoreRuntime.failure.value?.detail?.contains("Could not open file") == true
                    }
                    assertArrayEquals("Failed write replaced the last complete slot", userSlotContents, state.readBytes())
                } finally {
                    assertTrue(blockedWrite.delete())
                }
            } finally {
                CoreRuntime.shutdown()
                CoreRuntime.detachSurface()
                NativeApp.setSaveStateIdentityPath(null)
                NativeApp.initializeOnce(previousContext ?: targetContext)
            }
        }
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(message, false)
    }
}
