package com.sbro.emucored.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.toggleLibraryPlatform
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPlatformTabsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keepsSeveralChipsSelectedAndReturnsToAllUsingTheExistingAppearance() {
        val selected = mutableStateOf<Set<GamePlatform>>(emptySet())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val all = context.getString(R.string.achievement_filter_all)
        val ds = context.getString(R.string.profile_platform_ds)
        val threeDs = context.getString(R.string.profile_platform_3ds)
        compose.setContent {
            MaterialTheme {
                LibraryPlatformTabs(GamePlatform.entries, selected.value, { selected.value = toggleLibraryPlatform(selected.value, it) }, 16.dp)
            }
        }
        compose.onNodeWithText(all).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(ds).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(setOf(GamePlatform.NDS), selected.value) }
        compose.onNodeWithText(threeDs).performClick().assertIsSelected()
        compose.onNodeWithText(ds).assertIsSelected()
        compose.onNodeWithText(all).assertIsNotSelected()
        compose.runOnIdle { assertEquals(setOf(GamePlatform.NDS, GamePlatform.N3DS), selected.value) }
        compose.onNodeWithText(ds).performClick().assertIsNotSelected()
        compose.onNodeWithText(threeDs).assertIsSelected()
        compose.runOnIdle { assertEquals(setOf(GamePlatform.N3DS), selected.value) }
        compose.onNodeWithText(all).performClick().assertIsSelected()
        compose.onNodeWithText(threeDs).assertIsNotSelected()
        compose.runOnIdle { assertEquals(emptySet<GamePlatform>(), selected.value) }
    }

    @Test fun changingAvailableConsolesDoesNotDispatchAnUnrequestedSelection() {
        val available = mutableStateOf(GamePlatform.entries.toList())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var callbackCount = 0
        compose.setContent {
            MaterialTheme {
                LibraryPlatformTabs(available.value, emptySet(), { callbackCount++ }, 16.dp)
            }
        }
        compose.runOnIdle { available.value = listOf(GamePlatform.NDS) }
        compose.onNodeWithText(context.getString(R.string.profile_platform_3ds)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.profile_platform_ds)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.achievement_filter_all)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, callbackCount) }
    }
}
