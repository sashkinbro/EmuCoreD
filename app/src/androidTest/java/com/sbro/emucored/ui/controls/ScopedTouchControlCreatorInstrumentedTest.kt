package com.sbro.emucored.ui.controls

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScopedTouchControlCreatorInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletingGameCubeComboChangesOnlyGameCubeLibraryAndKeepsGeometry() =
        exercise(ConsoleControlsScope(ConsolePlatform.GAMECUBE))

    @Test fun deletingRemoteComboDoesNotChangeNunchukLibrary() =
        exercise(ConsoleControlsScope(ConsolePlatform.WII, "remote"))

    private fun exercise(selected: ConsoleControlsScope) {
        testStorageContext().use { context ->
            val preferences = AppPreferences(context)
            val other = ConsoleControlsScope(ConsolePlatform.WII, "nunchuk")
            val legacy = CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "legacy", name = "Legacy")))
            val library = CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "selected", name = "Selected console")))
            val otherLibrary = CustomTouchControlLibrary(controls = listOf(CustomTouchControl(id = "other", name = "Other attachment")))
            val layout = ConsoleControlsDefaults.layout(selected).copy(actionOffset = 26f to -37f, stickScale = 133)
            runBlocking {
                preferences.setCustomTouchControls(legacy)
                preferences.saveConsoleControls(selected, ConsoleControlsProfile(layout, library))
                preferences.saveConsoleControls(other, ConsoleControlsProfile(ConsoleControlsDefaults.layout(other), otherLibrary))
            }
            if (selected.platform == ConsolePlatform.WII)
                assertTrue(CoreOptionPreferences(context).setGlobal(ConsolePlatform.WII, "wii_profile", "remote"))
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        ScopedTouchControlCreatorHostScreen(selected.platform.id, true, {}, {})
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("touch_control_creator_delete_selected").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("touch_control_creator_delete_selected").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("touch_control_creator_confirm_delete").performClick()
            compose.waitUntil(10_000) {
                runBlocking { preferences.overlayLayoutSnapshot.first().consoleProfiles[selected.key]?.customControls?.controls?.isEmpty() == true }
            }
            runBlocking {
                val snapshot = preferences.overlayLayoutSnapshot.first()
                assertEquals(layout, snapshot.forConsoleControls(selected).toTouchControlsLayoutProfile())
                assertEquals(legacy, snapshot.customTouchControls)
                assertEquals(otherLibrary, snapshot.forConsoleControls(other).customTouchControls)
            }
        }
    }
}
