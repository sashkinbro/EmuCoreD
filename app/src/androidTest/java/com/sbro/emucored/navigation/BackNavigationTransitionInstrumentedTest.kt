package com.sbro.emucored.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import com.sbro.emucored.ui.common.ScreenTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackNavigationTransitionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topBarBackKeepsTheOutgoingScreenDuringTheReturnAnimation() {
        lateinit var openDetails: () -> Unit
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                openDetails = { nav.navigate("details") }
                NavHost(nav, startDestination = "home",
                    popEnterTransition = { appScreenPopEnterTransition() },
                    popExitTransition = { appScreenPopExitTransition() }) {
                    composable("home") { Text("home", Modifier.testTag("home")) }
                    composable("details") {
                        ScreenTopBar(title = "details", onBackClick = { nav.popBackStack() },
                            modifier = Modifier.testTag("details"))
                    }
                }
            }
        }
        compose.runOnIdle { openDetails() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val back = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.back)
        compose.onNodeWithContentDescription(back).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("details").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("details").assertDoesNotExist()
        compose.onNodeWithTag("home").assertExists()
        compose.mainClock.autoAdvance = true
    }
}
