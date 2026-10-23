package com.sbro.emucored.ui.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reads real preferences, but changes only the screen-local console/category selection. */
@RunWith(AndroidJUnit4::class)
class ConsoleSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun label(id: Int) = application.getString(id)

    private fun openDsGraphics(restoration: StateRestorationTester? = null) {
        lateinit var viewModel: SettingsViewModel
        compose.runOnUiThread {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[SettingsViewModel::class.java]
        }
        if (restoration == null) {
            compose.setContent { MaterialTheme { SettingsScreen(initialTab = "ds", viewModel = viewModel) } }
        } else {
            restoration.setContent { MaterialTheme { SettingsScreen(initialTab = "ds", viewModel = viewModel) } }
        }
    }

    @After fun clearViewModel() { compose.runOnUiThread { store.clear() } }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureConsoleUi") != "true") return
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(application.getExternalFilesDir(null), "instrumentation/$name.png")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun legacyDsLinkSelectsDsGraphicsAndSwitchingConsoleChangesRendererChoices() {
        openDsGraphics()
        compose.onNode(hasText(label(R.string.profile_platform_ds)) and hasClickAction()).assertIsSelected()
        compose.onNodeWithText(label(R.string.settings_renderer_opengl)).assertDoesNotExist()
        compose.onAllNodesWithText(label(R.string.settings_renderer_vulkan))[0].assertIsDisplayed()
        capture("settings-ds-graphics")
        compose.onNode(hasText(label(R.string.profile_platform_3ds)) and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.settings_renderer_opengl)).assertIsDisplayed()
        capture("settings-3ds-graphics")
        compose.onNode(hasText(label(R.string.profile_platform_ds)) and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.settings_renderer_opengl)).assertDoesNotExist()
    }

    @Test fun dsControlsExcludeAnalogGyroAndRawAzaharInputOptions() {
        openDsGraphics()
        compose.onNodeWithTag("settings-category-tabs").performScrollToNode(hasText(label(R.string.settings_controls_tab)))
        compose.onNode(hasText(label(R.string.settings_controls_tab)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(R.string.settings_gyro_mode)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_touchscreen_right_stick)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_core_input)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_gamepad_stick_deadzone)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_gamepad_left_stick_sensitivity)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_overlay_scale)).assertIsDisplayed()
        capture("settings-ds-controls")
    }

    @Test fun searchCannotExposeAnotherConsolesGyroSettings() {
        openDsGraphics()
        compose.onNodeWithContentDescription(label(R.string.settings_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(label(R.string.settings_gyro_mode))
        compose.onNodeWithText(label(R.string.settings_search_no_results)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.profile_platform_3ds)) and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(label(R.string.settings_gyro_mode))
        compose.onNode(hasText(label(R.string.settings_gyro_mode)) and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText(label(R.string.settings_gyro_mode)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(label(R.string.settings_controls_tab)) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText(label(R.string.profile_platform_3ds)) and hasClickAction()).assertIsSelected()
    }

    @Test fun consoleAndCategorySurviveSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(compose)
        openDsGraphics(restoration)
        compose.onNode(hasText(label(R.string.profile_platform_3ds)) and hasClickAction()).performClick()
        compose.onNodeWithTag("settings-category-tabs").performScrollToNode(hasText(label(R.string.settings_audio_tab)))
        compose.onNode(hasText(label(R.string.settings_audio_tab)) and hasClickAction()).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText(label(R.string.profile_platform_3ds)) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText(label(R.string.settings_audio_tab)) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText(label(R.string.profile_platform_ds)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(R.string.settings_general_tab)) and hasClickAction()).assertIsSelected()
    }
}
