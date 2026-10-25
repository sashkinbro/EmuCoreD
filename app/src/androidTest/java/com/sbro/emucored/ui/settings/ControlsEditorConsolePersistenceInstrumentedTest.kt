package com.sbro.emucored.ui.settings

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.AppPreferencesDataStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ControlsEditorConsolePersistenceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun classicComboChoicesPersistKnownConsoleOnNewOpaqueGameProfile() = exercise(null)
    @Test fun editorRepairsKnownConsoleOnExistingProfileWithoutLosingOtherSettings() = exercise(GamePlatform.N3DS)

    private fun exercise(existingPlatform: GamePlatform?) {
        testStorageContext().use { isolated ->
            val storeJob = SupervisorJob()
            val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob),
                produceFile = { File(isolated.filesDir, "editor.preferences_pb") })
            val context = object : ContextWrapper(isolated), AppPreferencesDataStoreOwner {
                override val appPreferencesDataStore = dataStore
                override fun getApplicationContext() = this
                override fun getExternalFilesDir(type: String?) = File(isolated.filesDir, "external").apply { mkdirs() }
            }
            try {
            // Fail at the actual storage dependency rather than timing out on an absent editor.
            runBlocking { withTimeout(10_000) {
                val preferences = AppPreferences(context)
                preferences.overlayLayoutSnapshot.first()
                preferences.settingsSnapshot.first()
                preferences.stickToggleTarget.first()
            } }
            val path = "content://controls-fixture/document/17"
            val repository = PerGameSettingsRepository(context)
            if (existingPlatform != null) repository.save(PerGameSettings(path, "Fixture",
                gamePlatform = existingPlatform, showFps = true, coreOptions = mapOf("wii_profile" to "classic")))
            assertTrue(CoreOptionPreferences(context).setGlobal(ConsolePlatform.WII, "wii_profile", "classic"))
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        ControlsLayoutEditorHostScreen(gamePath = path, gameTitle = "Fixture", platformId = "wii", onBackClick = {})
                    }
                }
            }
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("controls_editor_add_button").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Exception) {
                val merged = runCatching { compose.onRoot().printToString(maxDepth = 8) }.getOrDefault("No merged root")
                val unmerged = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8) }.getOrDefault("No unmerged root")
                throw AssertionError("Editor did not expose Add control. Merged:\n$merged\nUnmerged:\n$unmerged", failure)
            }
            compose.onNodeWithTag("controls_editor_add_button").performClick()
            // Both selectors must expose the Classic Controller's digital triggers and Home.
            compose.onNodeWithTag("controls_editor_combo_primary_zl").performScrollTo().performClick()
            compose.onNodeWithTag("controls_editor_combo_primary_zr").assertExists()
            compose.onNodeWithTag("controls_editor_combo_secondary_home").performScrollTo().performClick()
            compose.onNodeWithTag("controls_editor_combo_secondary_zr").assertExists()
            compose.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { repository.get(path)?.customTouchControls?.controls?.size == 1 }
            val saved = requireNotNull(repository.get(path))
            assertEquals(GamePlatform.WII, saved.gamePlatform)
            assertNotNull(saved.touchControlsLayout)
            assertEquals("zl", saved.customTouchControls?.controls?.single()?.actionId)
            assertEquals("home", saved.customTouchControls?.controls?.single()?.secondaryActionId)
            if (existingPlatform != null) {
                assertTrue(saved.showFps)
                assertEquals("classic", saved.coreOptions["wii_profile"])
            }
            } finally { runBlocking { storeJob.cancelAndJoin() } }
        }
    }
}
