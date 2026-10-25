package com.sbro.emucored.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.testStorageContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real, isolated DataStore files and the same backup entry points as the app. */
@RunWith(AndroidJUnit4::class)
class ConsoleControlsPreferencesInstrumentedTest {
    @Test fun scopedEditsAndExplicitResetSurviveBackupWithoutChangingLegacyLayout() = runBlocking {
        testStorageContext().use { context ->
            val preferences = AppPreferences(context)
            val legacy = TouchControlsLayoutProfile(dpadOffset = 37f to -19f, stickScale = 143,
                controlLayouts = AppPreferences.defaultOverlayControlLayouts().toMutableMap().apply {
                    put("right_stick", OverlayControlLayout(visible = false, offset = 21f to 9f,
                        surfaceOnly = true, widthScale = 175))
                })
            val combos = CustomTouchControlLibrary(controls = listOf(
                CustomTouchControl(id = "legacy_combo", actionId = "a", secondaryActionId = "b")))
            preferences.saveTouchControlsLayout(legacy)
            preferences.setCustomTouchControls(combos)
            val gc = ConsoleControlsScope(ConsolePlatform.GAMECUBE)
            val remote = ConsoleControlsScope(ConsolePlatform.WII, "remote")
            val nunchuk = ConsoleControlsScope(ConsolePlatform.WII, "nunchuk")
            val gcProfile = ConsoleControlsProfile(ConsoleControlsDefaults.layout(gc).copy(stickScale = 119), combos)
            val remoteProfile = ConsoleControlsProfile(ConsoleControlsDefaults.layout(remote).copy(actionOffset = 8f to 12f))
            preferences.saveConsoleControls(gc, gcProfile)
            preferences.saveConsoleControls(remote, remoteProfile)
            preferences.resetConsoleControls(nunchuk)

            val expected = preferences.overlayLayoutSnapshot.first()
            assertEquals(legacy, expected.toTouchControlsLayoutProfile())
            assertEquals(combos, expected.customTouchControls)
            assertEquals(gcProfile.layout, expected.forConsoleControls(gc).toTouchControlsLayoutProfile())
            assertEquals(remoteProfile.layout, expected.forConsoleControls(remote).toTouchControlsLayoutProfile())
            assertEquals(ConsoleControlsDefaults.layout(nunchuk), expected.forConsoleControls(nunchuk).toTouchControlsLayoutProfile())
            assertEquals(CustomTouchControlLibrary.Empty, expected.forConsoleControls(nunchuk).customTouchControls)
            // An untouched console still inherits every field of the existing custom layout.
            assertEquals(legacy, expected.forConsoleControls(ConsoleControlsScope(ConsolePlatform.SNES)).toTouchControlsLayoutProfile())

            val backup = preferences.exportJson()
            preferences.resetConsoleControls(gc)
            preferences.importJson(backup)
            val restored = preferences.overlayLayoutSnapshot.first()
            assertEquals(expected.consoleProfiles, restored.consoleProfiles)
            assertEquals(legacy, restored.toTouchControlsLayoutProfile())
            assertEquals(combos, restored.customTouchControls)

            // Older backups intentionally contain only the legacy layout/library.
            val oldBackup = JSONObject(backup.toString()).apply { remove("consoleControlsProfiles") }
            preferences.importJson(oldBackup)
            val oldRestored = preferences.overlayLayoutSnapshot.first()
            assertTrue(oldRestored.consoleProfiles.isEmpty())
            assertEquals(legacy, oldRestored.forConsoleControls(gc).toTouchControlsLayoutProfile())
            assertEquals(combos, oldRestored.forConsoleControls(gc).customTouchControls)
        }
    }
}
