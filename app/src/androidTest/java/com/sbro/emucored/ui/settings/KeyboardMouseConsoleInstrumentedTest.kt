package com.sbro.emucored.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.KeyboardMouseSettings
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KeyboardMouseConsoleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nesMappingDialogShowsRealInputsAndDoesNotRewriteHiddenExistingBindings() {
        val saved = KeyboardMouseSettings(keyboardEnabled = true,
            keyboardBindingsByPad = mapOf(0 to mapOf("x" to 42, "right_stick_up" to 43, "a" to 44)))
        var changes = 0
        compose.setContent {
            MaterialTheme {
                KeyboardMouseMappingDialog(settings = saved, onSettingsChange = { changes++ },
                    initialSource = KeyboardMouseMappingSource.KEYBOARD, onDismiss = {},
                    consolePolicy = ConsoleOverlayPolicy.forPlatform(ConsolePlatform.NES))
            }
        }
        compose.onNodeWithTag("keyboard_mouse_action_a").assertExists()
        compose.onNodeWithTag("keyboard_mouse_action_quick_save").assertExists()
        for (id in listOf("x", "l", "zl", "right_stick_up", "toggle_second_screen", "swap_screens"))
            compose.onNodeWithTag("keyboard_mouse_action_$id").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, changes)
            assertEquals(mapOf("x" to 42, "right_stick_up" to 43, "a" to 44), saved.keyboardBindingsByPad[0])
        }
    }
}
