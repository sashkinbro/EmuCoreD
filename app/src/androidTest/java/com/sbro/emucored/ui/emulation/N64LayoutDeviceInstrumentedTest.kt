package com.sbro.emucored.ui.emulation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.NativeApp
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.ConsoleControlsDefaults
import com.sbro.emucored.data.ConsoleControlsScope
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class N64LayoutDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 120_000) fun capturesFactoryControlsOverAnN64Game(): Unit = runBlocking {
        assumeTrue("Run only against the isolated UI test package",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".uiqa"))
        val path = InstrumentationRegistry.getArguments().getString("n64RomPath").orEmpty()
        assumeTrue("Provide an owned N64 game via n64RomPath", path.isNotBlank())
        assertFalse(EmulatorBridge.isVmActive())
        val ready = CountDownLatch(1)
        lateinit var holder: SurfaceHolder
        val events = ConcurrentLinkedQueue<Triple<Int, Int, Boolean>>()
        val profile = ConsoleControlsDefaults.layout(ConsoleControlsScope(ConsolePlatform.N64))
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    SurfaceView(context).also { view -> view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(value: SurfaceHolder) { holder = value; ready.countDown() }
                        override fun surfaceChanged(value: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(value: SurfaceHolder) = Unit
                    }) }
                })
                OnScreenControls(
                    modifier = Modifier.fillMaxSize(), scaleFactor = 1f,
                    dpadOffset = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
                    lstickOffset = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
                    rstickOffset = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
                    actionOffset = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
                    lbtnOffset = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
                    rbtnOffset = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
                    centerOffset = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
                    controlLayouts = profile.controlLayouts, racingMode = false,
                    onToggleLeftInputMode = {}, onPadInput = { key, range, pressed ->
                        events.add(Triple(key, range, pressed))
                        EmulatorBridge.setPadButton(0, key, range, pressed)
                    }, consolePolicy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N64)
                )
            }
        }
        assertTrue("N64 surface unavailable", ready.await(15, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val generation = EmulatorBridge.presentationSurfaceGeneration.value
            EmulatorBridge.onSurfaceCreated(generation)
            EmulatorBridge.onSurfaceChanged(holder.surface, 640, 480, generation)
        }
        assertTrue("N64 game failed to start", EmulatorBridge.startEmulation(path, path, platformOverride = GamePlatform.N64))
        try {
            SystemClock.sleep(9000)
            // ZUI may ask once whether this disposable test app can read its installed-app list.
            // Deny that unrelated access so the system dialog does not cover the screenshot.
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                if (root.findAccessibilityNodeInfosByText("Trying Read installed app").isNotEmpty()) {
                    root.findAccessibilityNodeInfosByText("Deny").firstOrNull()
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            compose.waitForIdle()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val out = File(instrumentation.targetContext.getExternalFilesDir(null), "n64-layout.png")
            out.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val directions = listOf(
                100 to (0.5f to 0.1f), 99 to (0.5f to 0.9f),
                123 to (0.1f to 0.5f), 105 to (0.9f to 0.5f)
            )
            directions.forEach { (key, fraction) ->
                events.clear()
                compose.onNodeWithTag("n64_c_buttons").performTouchInput {
                    down(Offset(center.x * 2f * fraction.first, center.y * 2f * fraction.second))
                }
                compose.waitForIdle()
                assertTrue("C direction $key did not press", events.contains(Triple(key, 255, true)))
                compose.onNodeWithTag("n64_c_buttons").performTouchInput { up() }
                compose.waitForIdle()
                assertTrue("C direction $key did not release", events.contains(Triple(key, 0, false)))
            }
            events.clear()
            val cButtons = compose.onNodeWithTag("n64_c_buttons")
            cButtons.performTouchInput {
                down(0, Offset(center.x, center.y * 0.2f))
                down(1, Offset(center.x * 1.8f, center.y))
            }
            compose.waitForIdle()
            assertTrue("C-up must respond to the first finger", events.contains(Triple(100, 255, true)))
            assertTrue("C-right must respond to the second finger", events.contains(Triple(105, 255, true)))
            cButtons.performTouchInput { up(0) }
            compose.waitForIdle()
            assertTrue("C-up must release independently", events.contains(Triple(100, 0, false)))
            assertFalse("C-right must remain held", events.contains(Triple(105, 0, false)))
            cButtons.performTouchInput { up(1) }
            compose.waitForIdle()
            assertTrue("C-right must release independently", events.contains(Triple(105, 0, false)))
            events.clear()
            cButtons.performTouchInput {
                down(0, Offset(center.x, center.y * 0.2f))
                down(1, Offset(center.x, center.y * 1.8f))
            }
            compose.waitForIdle()
            assertTrue(events.contains(Triple(100, 255, true)))
            assertTrue(events.contains(Triple(99, 255, true)))
            val buttonField = NativeApp::class.java.getDeclaredField("padButtons").apply { isAccessible = true }
            val buttonMask = (buttonField.get(NativeApp) as IntArray)[0]
            assertEquals("Opposite C buttons must both reach the input bridge", 0, buttonMask and ((1 shl 12) or (1 shl 15)))
            cButtons.performTouchInput { up(0); up(1) }
            compose.waitForIdle()
            events.clear()
            cButtons.performTouchInput { down(0, Offset(center.x, center.y * 0.2f)) }
            compose.waitForIdle()
            cButtons.performTouchInput { moveTo(0, Offset(center.x * 1.8f, center.y)) }
            compose.waitForIdle()
            assertTrue("Dragging away must release C-up", events.contains(Triple(100, 0, false)))
            assertTrue("Dragging onto C-right must press it", events.contains(Triple(105, 255, true)))
            cButtons.performTouchInput { up(0) }
        } finally {
            EmulatorBridge.shutdown()
        }
    }
}
