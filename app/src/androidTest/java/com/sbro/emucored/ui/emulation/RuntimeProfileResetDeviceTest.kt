package com.sbro.emucored.ui.emulation

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real session; unique ROM/profile/save identity and preferences restored in finally. */
@RunWith(AndroidJUnit4::class)
class RuntimeProfileResetDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: EmulationViewModel

    @Test(timeout = 180_000)
    fun dsResetRestoresLiveGlobalsAndLaterEditDoesNotResurrectGraphicsOverrides() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val source = args.getString("dsRomPath").orEmpty()
        assumeTrue("Supply a user-owned dsRomPath", source.isNotBlank())
        assertFalse(EmulatorBridge.isVmActive())
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val preferences = AppPreferences(application)
        val original = preferences.settingsSnapshot.first()
        val repository = PerGameSettingsRepository(application)
        val token = "profile-reset-${UUID.randomUUID()}"
        val rom = File(application.cacheDir, "$token.nds")
        val store = ViewModelStore()
        compose.setContent {
            AndroidView(factory = { context -> TextView(context).apply { text = "Runtime profile reset test"; keepScreenOn = true } })
        }
        try {
            val input = if (source.startsWith("content://")) application.contentResolver.openInputStream(Uri.parse(source))
                else File(source).inputStream()
            requireNotNull(input).use { stream -> rom.outputStream().use { stream.copyTo(it) } }
            preferences.setRenderer(12)
            preferences.setUpscaleMultiplier(5f)
            preferences.setDsRenderer(14)
            preferences.setDsUpscaleMultiplier(2f)
            repository.save(PerGameSettings(gameKey = rom.absolutePath, gameTitle = token,
                renderer = 13, upscaleMultiplier = 1f, swapScreens = true,
                providedKeys = setOf("renderer", "upscaleMultiplier", "swapScreens")))
            compose.runOnUiThread {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[EmulationViewModel::class.java]
                model.startEmulation(rom.absolutePath, bootSmokeProbe = true)
            }
            await("DS did not start") { DsSession.isActive && model.uiState.value.isRunning }
            assertEquals(13, DsSession.currentRenderer())
            assertEquals(1, DsSession.currentRenderScale())
            compose.runOnUiThread { model.toggleMenu() }
            await("Menu did not pause DS") { DsSession.isPaused && model.uiState.value.showMenu }
            Thread.sleep(150)
            assertTrue(DsSession.saveState(8))
            val guestBeforeReset = guestDigest(File(requireNotNull(DsSession.statePath(8))))
            compose.runOnUiThread { model.resetCurrentGameSettingsProfile() }
            await("Reset did not restore DS Vulkan and its global scale") {
                !model.uiState.value.gameSettingsProfileActive && model.uiState.value.renderer == 14 && model.uiState.value.upscale == 2f
            }
            assertEquals(14, DsSession.currentRenderer())
            assertEquals(2, DsSession.currentRenderScale())
            assertTrue(DsSession.isPaused)
            // The host presentation counter resets with the renderer. Compare
            // guest RAM/CPU timing/NumFrames from NDSG, not that host counter.
            assertTrue(DsSession.saveState(9))
            assertArrayEquals("Reset advanced/rebooted the paused guest", guestBeforeReset,
                guestDigest(File(requireNotNull(DsSession.statePath(9)))))
            val pausedFrames = EmuCoreDsNative.nativeGetFrameCount()
            Thread.sleep(150)
            assertEquals(pausedFrames, EmuCoreDsNative.nativeGetFrameCount())
            assertFalse(DsSession.currentSwapScreens())
            assertNull(repository.get(rom.absolutePath))
            compose.runOnUiThread { model.toggleFpsVisibility() }
            await("Later edit was not saved") { repository.get(rom.absolutePath) != null }
            val saved = requireNotNull(repository.get(rom.absolutePath))
            assertEquals(14, saved.renderer)
            assertEquals(2f, saved.upscaleMultiplier, 0f)
            assertFalse("Stale renderer override resurrected", "renderer" in saved.providedKeys.orEmpty())
            assertFalse("Stale scale override resurrected", "upscaleMultiplier" in saved.providedKeys.orEmpty())
            val globals = preferences.settingsSnapshot.first()
            assertEquals(12, globals.renderer)
            assertEquals(5f, globals.upscaleMultiplier, 0f)
        } finally {
            if (::model.isInitialized) {
                compose.runOnUiThread { model.stopEmulation() }
                await("DS teardown did not finish") { !DsSession.isActive && !EmulatorBridge.isVmActive() }
            }
            compose.runOnUiThread { store.clear() }
            repository.delete(rom.absolutePath)
            preferences.setRenderer(original.renderer)
            preferences.setUpscaleMultiplier(original.upscaleMultiplier)
            preferences.setDsRenderer(original.dsRenderer)
            preferences.setDsUpscaleMultiplier(original.dsUpscaleMultiplier)
            rom.delete()
            val dsRoot = File(application.filesDir, "ds").canonicalFile
            for (path in listOf(File(dsRoot, "saves/$token.sav"), File(dsRoot, "states/$token"))) {
                check(path.canonicalPath.startsWith(dsRoot.path + File.separator))
                path.deleteRecursively()
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun guestDigest(state: File): ByteArray {
        val bytes = state.readBytes()
        assertEquals("MELN", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("NDSG", String(bytes, 16, 4, Charsets.US_ASCII))
        val length = ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertTrue(length > 16 && length <= bytes.size - 16)
        return MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(32, 16 + length))
    }
}
