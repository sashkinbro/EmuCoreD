package com.sbro.emucored.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogScreenShowsDriverControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { GpuDriverScreen(onBackClick = {}, platform = ConsolePlatform.N3DS) } }
        compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_manager_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_install_from_file)).assertIsDisplayed()
    }
}
