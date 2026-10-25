package com.sbro.emucored.ui.emulation

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.TouchControlPressEffect
import com.sbro.emucored.data.TouchControlVisualStyle
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose pointer dispatch with the production routing component and an observed guest sink. */
@RunWith(AndroidJUnit4::class)
class ConsoleOverlayTouchRoutingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun classicAndDigitalConsolesDoNotForwardBlankScreenTapsButThreeDsDoes() {
        val profile = mutableStateOf(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.N3DS))
        val guest = mutableListOf<Boolean>()
        content(profile, guest)
        val group = compose.onNodeWithTag("console-touch-group")
        group.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(true, false), guest); guest.clear() }
        for (policy in listOf(
            ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic"),
            ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GBA),
            ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GAMECUBE)
        )) {
            compose.runOnIdle { profile.value = policy }
            group.performTouchInput { click(center) }
            compose.runOnIdle { assertTrue(guest.isEmpty()) }
        }
    }

    @Test fun changingToClassicReleasesAnOwnedGuestTouchOnce() {
        val profile = mutableStateOf(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "nunchuk"))
        val guest = mutableListOf<Boolean>()
        content(profile, guest)
        val group = compose.onNodeWithTag("console-touch-group")
        group.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(listOf(true), guest); profile.value = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.WII, "classic") }
        compose.waitForIdle()
        group.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(true, false), guest) }
    }

    @Test fun disablingGuestTouchPreservesActualVirtualButtonPressAndRelease() {
        val profile = mutableStateOf(ConsoleOverlayPolicy.forPlatform(ConsolePlatform.GB))
        val guest = mutableListOf<Boolean>()
        val button = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val spec = TouchButtonSpec("a", drawableRes = R.drawable.ic_controller_a_button,
            width = 200.dp, height = 200.dp, x = 0.dp, y = 0.dp, shape = CircleShape,
            pressActions = listOf(TouchButtonPressAction("a") { button.add(it) }))
        content(profile, guest, listOf(spec))
        compose.onNodeWithTag("console-touch-group").performTouchInput { click(center) }
        compose.waitUntil(timeoutMillis = 2_000) { button.size == 2 }
        compose.runOnIdle { assertTrue(guest.isEmpty()); assertEquals(listOf(true, false), button) }
    }

    private fun content(
        profile: androidx.compose.runtime.MutableState<ConsoleOverlayPolicy>,
        guest: MutableList<Boolean>,
        specs: List<TouchButtonSpec> = emptyList()
    ) {
        compose.setContent {
            MaterialTheme {
                TouchButtonGroup(specs = specs, inputWidth = 200.dp, inputHeight = 200.dp,
                    touchscreenRightStick = false, touchscreenRightStickSensitivity = 1f,
                    invertRightStick = false, invertRightStickHorizontal = false, onPadInput = { _, _, _ -> },
                    visualStyle = TouchControlVisualStyle.CLASSIC, pressEffect = TouchControlPressEffect.GROW,
                    onTouchHaptic = {}, guestTouchEnabled = profile.value.guestTouch,
                    onGuestTouch = { _, _, pressed -> guest.add(pressed) }, modifier = Modifier.testTag("console-touch-group"))
            }
        }
    }
}
