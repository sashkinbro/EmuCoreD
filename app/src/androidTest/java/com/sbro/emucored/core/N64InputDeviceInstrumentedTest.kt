package com.sbro.emucored.core

import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.data.GamePlatform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class N64InputDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun externalLaunchersCanResolveN64Images() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (extension in listOf("n64", "N64", "z64", "Z64", "v64", "V64")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("file:///storage/emulated/0/game.$extension"))
                .addCategory(Intent.CATEGORY_DEFAULT)
            assertTrue("No activity handles .$extension", context.packageManager
                .queryIntentActivities(intent, 0).any { it.activityInfo.packageName == context.packageName })
        }
    }

    @Test(timeout = 120_000) fun holdingStartReachesN64UntilReleased() = runBlocking {
        assumeTrue("Run only against the isolated UI test package",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".uiqa"))
        val path = InstrumentationRegistry.getArguments().getString("n64RomPath").orEmpty()
        assumeTrue("Provide an owned N64 game via n64RomPath", path.isNotBlank())
        assertFalse(EmulatorBridge.isVmActive())
        val ready = CountDownLatch(1)
        lateinit var holder: SurfaceHolder
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                SurfaceView(context).also { view ->
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(value: SurfaceHolder) { holder = value; ready.countDown() }
                        override fun surfaceChanged(value: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(value: SurfaceHolder) = Unit
                    })
                }
            })
        }
        assertTrue("N64 surface unavailable", ready.await(15, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val generation = EmulatorBridge.presentationSurfaceGeneration.value
            EmulatorBridge.onSurfaceCreated(generation)
            EmulatorBridge.onSurfaceChanged(holder.surface, 640, 480, generation)
        }
        assertTrue("N64 game failed to start", EmulatorBridge.startEmulation(path, path, platformOverride = GamePlatform.N64))
        val field = NativeApp::class.java.getDeclaredField("padButtons").apply { isAccessible = true }
        val buttons = field.get(NativeApp) as IntArray
        try {
            EmulatorBridge.setPadButton(0, 108, 255, true)
            assertEquals("Start must press immediately", 0, buttons[0] and (1 shl 3))
            SystemClock.sleep(600)
            assertEquals("Start must stay pressed while held", 0, buttons[0] and (1 shl 3))
            assertEquals("Start must not activate fast forward", 0, NativeApp.timeControlMode.value)
            EmulatorBridge.setPadButton(0, 108, 0, false)
            assertNotEquals("Start must release", 0, buttons[0] and (1 shl 3))
        } finally {
            EmulatorBridge.shutdown()
        }
    }
}
