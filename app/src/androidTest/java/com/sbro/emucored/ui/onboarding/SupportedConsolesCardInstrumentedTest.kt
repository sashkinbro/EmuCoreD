package com.sbro.emucored.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportedConsolesCardInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardShowsConsoleFormatsAndArchiveSupport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { SupportedConsolesCard() } }
        compose.onNodeWithText(context.getString(R.string.onboarding_supported_consoles)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.formats_title)).performClick()
        compose.onNodeWithText(".sfc", substring = true).assertExists()
        compose.onNodeWithText(".nds", substring = true).assertExists()
        compose.onAllNodesWithText(".zip", substring = true).assertCountEquals(5)
        if (InstrumentationRegistry.getArguments().getString("captureCard") == "true") {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(context.cacheDir, "codex-supported-consoles-card.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
