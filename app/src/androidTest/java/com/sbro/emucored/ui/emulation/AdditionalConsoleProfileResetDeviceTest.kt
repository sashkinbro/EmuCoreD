package com.sbro.emucored.ui.emulation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.OriginalConsolePrograms
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdditionalConsoleProfileResetDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: EmulationViewModel

    @Test(timeout = 60_000) fun changingACoreOptionCreatesAResettableProfile() = exercise(false)
    @Test(timeout = 60_000) fun resetRestoresGlobalsAndPreservesThePausedGuest() = exercise(true)

    @Test(timeout = 30_000)
    fun preferenceUpdatePreservesAPlatformChangeThatHappensDuringReduction() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        compose.runOnUiThread {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EmulationViewModel::class.java]
        }
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        try {
            @Suppress("UNCHECKED_CAST")
            val state = EmulationViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(model) as MutableStateFlow<EmulationUiState>
            val reduce = EmulationViewModel::class.java.getDeclaredMethod(
                "applyGlobalRuntimePreferenceUpdate", kotlin.jvm.functions.Function1::class.java
            ).apply { isAccessible = true }
            val change: (EmulationUiState) -> EmulationUiState = { previous ->
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                previous.copy(frameSkip = 2)
            }
            val future = executor.submit { reduce.invoke(model, change) }
            assertTrue("Preference reduction did not start", entered.await(10, TimeUnit.SECONDS))
            state.value = state.value.copy(gamePlatform = GamePlatform.GBA)
            release.countDown()
            future.get(10, TimeUnit.SECONDS)
            assertEquals("A preference update must retain a concurrent game identity", GamePlatform.GBA, state.value.gamePlatform)
            assertEquals(2, state.value.frameSkip)
        } finally {
            release.countDown()
            executor.shutdownNow()
            compose.runOnUiThread { store.clear() }
        }
    }

    private fun exercise(preseedProfile: Boolean) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val previous = requireNotNull(EmulatorBridge.getContext())
        val rom = File(app.cacheDir, "gba-profile-${UUID.randomUUID()}.gba").apply {
            writeBytes(OriginalConsolePrograms.forPlatform(ConsolePlatform.GBA))
        }
        val profiles = PerGameSettingsRepository(app)
        val options = CoreOptionPreferences(app)
        val globalFilter = options.effectiveOptions(ConsolePlatform.GBA)["video_filter"]!!
        val customFilter = if (globalFilter == "nearest") "linear" else "nearest"
        val store = ViewModelStore()
        if (preseedProfile) {
            options.setGameOverride(ConsolePlatform.GBA, rom.path, "video_filter", customFilter)
            profiles.save(PerGameSettings(gameKey = rom.path, gameTitle = "Original GBA program", gamePlatform = GamePlatform.GBA,
                coreOptions = mapOf("video_filter" to customFilter), providedKeys = setOf("coreOptions")))
        }
        testStorageContext().use { isolated ->
            // Keep runtime files isolated; the real ViewModel and bridge share
            // only a unique game's option keys in the production preference store.
            val runtimeContext = object : ContextWrapper(isolated) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(name, mode)
            }
            EmulatorBridge.initializeOnce(runtimeContext)
            val ready = CountDownLatch(1)
            compose.setContent {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                    SurfaceView(activity).apply {
                        keepScreenOn = true
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = Unit
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {
                                EmulatorBridge.onSurfaceChanged(h.surface, w, height, EmulatorBridge.presentationSurfaceGeneration.value)
                                ready.countDown()
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                        })
                    }
                })
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                compose.runOnUiThread {
                    model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EmulationViewModel::class.java]
                    model.startEmulation(rom.path, bootSmokeProbe = true)
                }
                withTimeout(15_000) { while (!AdditionalConsoleSession.isRunning) delay(25) }
                assertEquals(GamePlatform.GBA, model.uiState.value.gamePlatform)
                compose.runOnUiThread { model.toggleMenu() }
                withTimeout(5_000) { while (AdditionalConsoleSession.session?.isPaused != true) delay(10) }
                compose.runOnUiThread { model.setCoreOption("video_filter", customFilter) }
                withTimeout(5_000) { while (options.gameOverrides(ConsolePlatform.GBA, rom.path)["video_filter"] != customFilter) delay(10) }
                assertTrue("Changing a core option must enable profile reset", model.uiState.value.gameSettingsProfileActive)
                assertNotNull(profiles.get(rom.path))
                val frames = AdditionalConsoleSession.session!!.frameCount()
                compose.runOnUiThread { model.resetCurrentGameSettingsProfile() }
                withTimeout(5_000) { while (model.uiState.value.gameSettingsProfileActive ||
                    options.gameOverrides(ConsolePlatform.GBA, rom.path).isNotEmpty()) delay(10) }
                assertNull(profiles.get(rom.path))
                assertEquals(13, model.uiState.value.renderer)
                assertEquals(globalFilter, options.effectiveOptions(ConsolePlatform.GBA, rom.path)["video_filter"])
                assertTrue(AdditionalConsoleSession.session!!.isPaused)
                assertEquals(frames, AdditionalConsoleSession.session!!.frameCount())
            } finally {
                if (::model.isInitialized) {
                    compose.runOnUiThread { model.stopEmulation() }
                    withTimeout(10_000) { while (EmulatorBridge.isVmActive()) delay(10) }
                }
                compose.runOnUiThread { store.clear() }
                options.resetGame(ConsolePlatform.GBA, rom.path)
                profiles.delete(rom.path)
                rom.delete()
                EmulatorBridge.initializeOnce(previous)
            }
        }
    }
}
