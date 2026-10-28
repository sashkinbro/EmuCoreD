package com.sbro.emucored.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import com.sbro.emucored.R
import com.sbro.emucored.core.GpuDriverPreferences
import com.sbro.emucored.core.GpuDriverSupport
import com.sbro.emucored.core.RendererDefaults
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverPlacementInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun globalRowShowsTheSelectedDriverAndUpdatesAfterSelection() {
        assumeTrue("Needs a supported Vulkan-driver device", GpuDriverSupport.deviceSupported())
        testStorageContext().use { context ->
            val preferences = GpuDriverPreferences(context)
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            var clicked = false
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                    MaterialTheme { GpuDriverSettingsItem(ConsolePlatform.N3DS) { clicked = true } }
                }
            }
            compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_system)).assertIsDisplayed()
            val folder = File(context.filesDir, "driver/Turnip").apply { mkdirs() }
            val library = File(folder, "libvulkan.so")
            library.writeText("invalid")
            File(folder, "driver_name.txt").writeText("libvulkan.so")
            compose.runOnUiThread { preferences.setGlobal(ConsolePlatform.N3DS, "Turnip") }
            compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_system)).assertIsDisplayed()
            val header = ByteArray(120)
            header[0] = 0x7f; header[1] = 0x45; header[2] = 0x4c; header[3] = 0x46
            header[4] = 2; header[5] = 1; header[6] = 1; header[16] = 3; header[18] = 0xb7.toByte()
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 1)
            }
            library.writeBytes(header)
            compose.runOnUiThread {
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            compose.onNodeWithText("Turnip").assertIsDisplayed()
            File(folder, "meta.json").writeText("{\"minApi\":999}")
            compose.runOnUiThread {
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_system)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_manager_title)).performClick()
            compose.runOnIdle { assertTrue(clicked) }
        }
    }

    @Test fun gameManagerPlacesTheTranslatedActionAfterTheRenderer() {
        assumeTrue("Needs a supported Vulkan-driver device", GpuDriverSupport.deviceSupported())
        testStorageContext().use { context ->
            val game = File(context.filesDir, "sample.3ds").path
            PerGameSettingsRepository(context).save(PerGameSettings(
                gameKey = game, gameTitle = "GPU placement fixture", gamePlatform = GamePlatform.N3DS,
                renderer = RendererDefaults.VULKAN
            ))
            var clicked = false
            compose.setContent {
                val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner) {
                    MaterialTheme {
                        PerGameSettingsManagerScreen(initialGamePath = game, onOpenControlsLayoutEditor = {},
                            onBackClick = {}, onOpenGpuDriver = { clicked = true })
                    }
                }
            }
            val action = context.getString(R.string.settings_gpu_driver_manage)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
            val renderer = compose.onNodeWithText(context.getString(R.string.settings_renderer)).fetchSemanticsNode()
            val button = compose.onNodeWithText(action).fetchSemanticsNode()
            assertTrue("Driver management should follow the renderer", renderer.boundsInRoot.top < button.boundsInRoot.top)
            if (InstrumentationRegistry.getArguments().getString("captureDriverPlacement") == "true") {
                val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "instrumentation/gpu-driver-placement.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText(action).performClick()
            compose.waitUntil(10_000) { clicked }
        }
    }
}
