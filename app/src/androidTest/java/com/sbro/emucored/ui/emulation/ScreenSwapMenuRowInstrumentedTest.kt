package com.sbro.emucored.ui.emulation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.data.GamePlatform
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenSwapMenuRowInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dsGraphicsSwitchShowsActualStateAndTogglesOncePerTap() {
        val swapped = mutableStateOf(false)
        var callbacks = 0
        compose.setContent {
            MaterialTheme {
                ScreenSwapMenuRow(GamePlatform.NDS, swapped.value, onSwapScreens = {
                    callbacks++
                    swapped.value = !swapped.value
                })
            }
        }
        val row = compose.onNodeWithTag("game-menu-screen-swap")
        row.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, callbacks) }
        row.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, callbacks) }
        compose.runOnIdle { swapped.value = true }
        row.assertIsOn()
    }

    @Test fun singleScreenConsolesNeverOfferScreenSwap() {
        val platform = mutableStateOf(GamePlatform.NDS)
        compose.setContent { MaterialTheme { ScreenSwapMenuRow(platform.value, false, onSwapScreens = {}) } }
        compose.onNodeWithTag("game-menu-screen-swap").assertIsOff()
        for (next in GamePlatform.entries.filterNot { it in setOf(GamePlatform.NDS, GamePlatform.N3DS) }) {
            compose.runOnIdle { platform.value = next }
            compose.onNodeWithTag("game-menu-screen-swap").assertDoesNotExist()
        }
    }

    @Test fun stateChangingActionsDisableTheSwitch() {
        compose.setContent {
            MaterialTheme { ScreenSwapMenuRow(GamePlatform.NDS, true, enabled = false, onSwapScreens = { error("Disabled action executed") }) }
        }
        compose.onNodeWithTag("game-menu-screen-swap").assertIsOn().assertIsNotEnabled()
    }
}
