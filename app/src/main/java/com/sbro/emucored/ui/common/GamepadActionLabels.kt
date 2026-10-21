package com.sbro.emucored.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucored.R
import com.sbro.emucored.core.GamepadManager

@StringRes
fun gamepadActionLabelRes(actionId: String): Int = when (actionId) {
    "b" -> R.string.settings_gamepad_action_b
    "a" -> R.string.settings_gamepad_action_a
    "y" -> R.string.settings_gamepad_action_y
    "x" -> R.string.settings_gamepad_action_x
    "l" -> R.string.settings_gamepad_action_l
    "r" -> R.string.settings_gamepad_action_r
    "zl" -> R.string.settings_gamepad_action_zl
    "zr" -> R.string.settings_gamepad_action_zr
    "home" -> R.string.settings_gamepad_action_home
    "touch" -> R.string.settings_gamepad_action_touch
    "select" -> R.string.settings_gamepad_action_select
    "start" -> R.string.settings_gamepad_action_start
    "left_input_toggle" -> R.string.settings_gamepad_action_left_input_toggle
    GamepadManager.ACTION_QUICK_SAVE -> R.string.emulation_quick_save
    GamepadManager.ACTION_QUICK_LOAD -> R.string.emulation_quick_load
    GamepadManager.ACTION_TOGGLE_SECOND_SCREEN -> R.string.emulation_second_screen
    GamepadManager.ACTION_SWAP_SCREENS -> R.string.settings_second_screen_swap_now
    GamepadManager.ACTION_FAST_FORWARD -> R.string.emulation_fast_forward
    GamepadManager.ACTION_REWIND -> R.string.emulation_rewind
    "dpad_up" -> R.string.settings_gamepad_action_dpad_up
    "dpad_down" -> R.string.settings_gamepad_action_dpad_down
    "dpad_left" -> R.string.settings_gamepad_action_dpad_left
    "dpad_right" -> R.string.settings_gamepad_action_dpad_right
    "left_stick_up" -> R.string.settings_gamepad_action_left_stick_up
    "left_stick_down" -> R.string.settings_gamepad_action_left_stick_down
    "left_stick_left" -> R.string.settings_gamepad_action_left_stick_left
    "left_stick_right" -> R.string.settings_gamepad_action_left_stick_right
    "right_stick_up" -> R.string.settings_gamepad_action_right_stick_up
    "right_stick_down" -> R.string.settings_gamepad_action_right_stick_down
    "right_stick_left" -> R.string.settings_gamepad_action_right_stick_left
    "right_stick_right" -> R.string.settings_gamepad_action_right_stick_right
    else -> R.string.settings_gamepad_section
}

@Composable
fun gamepadActionLabel(actionId: String): String = stringResource(gamepadActionLabelRes(actionId))
