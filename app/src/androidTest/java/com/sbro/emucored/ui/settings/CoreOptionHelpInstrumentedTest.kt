package com.sbro.emucored.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.core.platform.SettingsCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same rows used by settings, per-game profiles, and the game menu. */
@RunWith(AndroidJUnit4::class)
class CoreOptionHelpInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun audioToggleChoiceAndSliderEachExposeTheirOwnHelp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                ConsoleCoreOptionsPanel(ConsolePlatform.GB, SettingsCategory.Audio,
                    CoreOptionSchema.resolve(ConsolePlatform.GB), onChange = { _, _ -> })
            }
        }
        val help = context.getString(R.string.settings_help_content_description)
        // GB audio has an enabled toggle, a high-pass choice, and an interference slider.
        compose.onAllNodesWithContentDescription(help).assertCountEquals(3)
        listOf(R.string.core_option_help_audio_enabled, R.string.core_option_help_highpass_filter,
            R.string.core_option_help_interference_volume).forEachIndexed { index, resource ->
            compose.onAllNodesWithContentDescription(help)[index].performClick()
            compose.onNodeWithText(context.getString(resource), substring = true).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.close)).performScrollTo().performClick()
        }
    }
}
