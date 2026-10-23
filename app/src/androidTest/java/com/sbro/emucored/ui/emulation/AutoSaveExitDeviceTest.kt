package com.sbro.emucored.ui.emulation

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
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
import com.sbro.emucored.core.createDsScreenSentinelRom
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Own homebrew, unique save identity; never touches user save slots. */
@RunWith(AndroidJUnit4::class)
class AutoSaveExitDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 120_000)
    fun exitWaitsForAutoSaveAndActualSurfacePreview() = runBlocking {
        assertFalse(EmulatorBridge.isVmActive())
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val repository = PerGameSettingsRepository(application)
        val preferences = AppPreferences(application)
        val originalAutoSave = preferences.autoSaveEnabled.first()
        val token = "exit-preview-${UUID.randomUUID()}"
        val rom = File(application.cacheDir, "$token.nds")
        val store = ViewModelStore()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var model: EmulationViewModel
        lateinit var surface: SurfaceView
        val ready = CountDownLatch(1)
        var stopped = false
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                SurfaceView(context).apply {
                    surface = this
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            DsSession.setSurface(holder.surface)
                            ready.countDown()
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    })
                }
            })
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            DsSession.setLayout(DsSession.LAYOUT_STACKED)
            rom.writeBytes(createDsScreenSentinelRom())
            preferences.setAutoSaveEnabled(true)
            repository.save(PerGameSettings(gameKey = rom.absolutePath, gameTitle = token,
                renderer = 14, upscaleMultiplier = 1f, autoSaveOnExit = true, swapScreens = false,
                providedKeys = setOf("renderer", "upscaleMultiplier", "autoSaveOnExit", "swapScreens")))
            compose.runOnUiThread {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[EmulationViewModel::class.java]
                model.startEmulation(rom.absolutePath, bootSmokeProbe = true)
            }
            await("Homebrew did not produce frames") {
                model.uiState.value.isRunning && EmuCoreDsNative.nativeGetFrameCount() > 3
            }
            assertTrue(model.uiState.value.autoSaveOnExit)
            await("Autosave was not enabled") { model.uiState.value.autoSaveEnabled }
            val state = File(requireNotNull(DsSession.statePath(0)))
            val preview = File("${state.absolutePath}.png")
            val captured = CompletableDeferred<Boolean>()
            val exit = CompletableDeferred<Boolean>()
            collectorScope.launch {
                model.saveStatePreviewRequests.collect { request ->
                    var success = false
                    try {
                        success = captureSaveStatePreview(surface, request.savePath, request.dualScreenPortrait)
                        captured.complete(success)
                    } finally { request.complete(success) }
                }
            }
            compose.waitForIdle()
            compose.runOnUiThread {
                model.stopEmulation(onExit = {
                    exit.complete(captured.isCompleted && preview.isFile)
                })
            }
            assertTrue("Navigation happened before the autosave preview completed", withTimeout(30_000) { exit.await() })
            assertTrue(withTimeout(5_000) { captured.await() })
            assertTrue("Autosave was not persisted", state.length() > 32)
            val image = BitmapFactory.decodeFile(preview.absolutePath)
            assertNotNull("Autosave preview is not a decodable image", image)
            requireNotNull(image).let {
                try {
                    val pixel = it.getPixel(it.width / 2, it.height / 2)
                    assertTrue("Preview did not contain the homebrew's cyan top screen: ${Integer.toHexString(pixel)}",
                        Color.red(pixel) < 40 && Color.green(pixel) > 200 && Color.blue(pixel) > 200)
                } finally { it.recycle() }
            }
            await("Exit retained a live session") { !DsSession.isActive && !EmulatorBridge.isVmActive() }
            stopped = true
        } finally {
            if (!stopped && store.keys().isNotEmpty()) {
                compose.runOnUiThread { model.stopEmulation() }
                await("Test teardown retained a live session") { !DsSession.isActive && !EmulatorBridge.isVmActive() }
            }
            collectorScope.cancel()
            compose.runOnUiThread { store.clear() }
            DsSession.setSurface(null)
            repository.delete(rom.absolutePath)
            preferences.setAutoSaveEnabled(originalAutoSave)
            rom.delete()
            val root = File(application.filesDir, "ds").canonicalFile
            for (path in listOf(File(root, "saves/$token.sav"), File(root, "states/$token"))) {
                check(path.canonicalPath.startsWith(root.path + File.separator))
                path.deleteRecursively()
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail(message)
    }
}
