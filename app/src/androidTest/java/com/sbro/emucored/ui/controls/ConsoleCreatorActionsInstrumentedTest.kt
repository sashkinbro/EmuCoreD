package com.sbro.emucored.ui.controls

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ActionSelector
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Rule
import org.junit.Test

class ConsoleCreatorActionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nesDoesNotOfferUnsupportedThreeDsActions() {
        show(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NES))
        compose.onNodeWithTag("actions_a").assertExists()
        for (id in listOf("x", "y", "l", "r", "zl", "zr", "home", "touch", "swap_screen"))
            compose.onNodeWithTag("actions_$id").assertDoesNotExist()
    }

    @Test fun gameCubeOffersZWithoutThreeDsZlAndZr() {
        show(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GAMECUBE))
        compose.onNodeWithTag("actions_select").assertTextContains("Z")
        compose.onNodeWithTag("actions_l").assertExists()
        compose.onNodeWithTag("actions_zl").assertDoesNotExist()
        compose.onNodeWithTag("actions_zr").assertDoesNotExist()
    }

    @Test fun wiiRemoteOffersOneAndTwoWithoutNunchukTriggers() {
        show(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "remote"))
        compose.onNodeWithTag("actions_x").assertTextContains("1")
        compose.onNodeWithTag("actions_y").assertTextContains("2")
        compose.onNodeWithTag("actions_l").assertDoesNotExist()
        compose.onNodeWithTag("actions_r").assertDoesNotExist()
    }

    @Test fun wiiClassicOffersBothDigitalTriggersAndHome() {
        show(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic"))
        for (id in listOf("zl", "zr", "home", "l", "r")) compose.onNodeWithTag("actions_$id").assertExists()
        compose.onNodeWithTag("actions_x").assertTextContains("X")
    }

    private fun show(policy: ConsoleOverlayPolicy) {
        compose.setContent {
            MaterialTheme {
                ActionSelector(selectedActionId = "a", testTagPrefix = "actions", onSelect = {},
                    actionSupported = policy::supportsAction, labelOverride = policy::buttonLabel,
                    includeExtendedActions = true)
            }
        }
    }
}
