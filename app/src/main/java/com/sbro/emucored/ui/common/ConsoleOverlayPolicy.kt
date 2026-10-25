package com.sbro.emucored.ui.common

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreCapabilities
import com.sbro.emucored.data.CustomTouchControl

/** Runtime projection only: a different console never rewrites a saved control layout. */
@kotlin.ConsistentCopyVisibility
data class ConsoleOverlayPolicy private constructor(
    val platform: ConsolePlatform,
    val wiiProfile: String
) {
    private val capabilities = CoreCapabilities.forPlatform(platform)
    val leftAnalog: Boolean = capabilities.supportsAnalogControls &&
        (platform != ConsolePlatform.WII || wiiProfile != "remote")
    // A Wii Remote uses the secondary analog input for its IR pointer.
    val rightAnalog: Boolean = capabilities.supportsAnalogControls
    val guestTouch: Boolean = platform in setOf(ConsolePlatform.NDS, ConsolePlatform.N3DS) ||
        (platform == ConsolePlatform.WII && wiiProfile != "classic")

    fun inputAlias(id: String): String = when (id) {
        "up" -> "dpad_up"; "down" -> "dpad_down"; "left" -> "dpad_left"; "right" -> "dpad_right"
        "swap_screens" -> "swap_screen"
        "z" -> if (platform == ConsolePlatform.GAMECUBE) "select" else "r"
        "c" -> "l"; "plus" -> "start"; "minus" -> "select"; "one" -> "x"; "two" -> "y"
        else -> id
    }

    fun supportsAction(id: String): Boolean {
        if (id in setOf("z", "c", "one", "two", "plus", "minus")) {
            if (id == "z" && platform == ConsolePlatform.GAMECUBE) return true
            if (platform != ConsolePlatform.WII) return false
            if (id in setOf("c", "z") && wiiProfile != "nunchuk") return false
            if (id in setOf("one", "two") && wiiProfile == "classic") return false
        }
        val action = inputAlias(id)
        if (action == "swap_screen") return capabilities.supportsScreenSwap
        if (action in setOf("fast_forward", "rewind")) return platform == ConsolePlatform.N3DS
        if (action == "touch") return platform == ConsolePlatform.N3DS
        if (action == "left_input_toggle") return leftAnalog || rightAnalog
        if (action !in BUTTON_ACTIONS) return false
        if (platform == ConsolePlatform.GAMECUBE && action in setOf("zl", "zr")) return false
        if (platform == ConsolePlatform.WII) {
            if (action in setOf("zl", "zr") && wiiProfile != "classic") return false
            if (action in setOf("l", "r") && wiiProfile == "remote") return false
        }
        return capabilities.supportsMappedAction(action)
    }

    fun supportsControl(id: String): Boolean = when (id) {
        "left_stick" -> leftAnalog
        "right_stick" -> rightAnalog
        "dpad_cluster" -> true
        "dpad_toggle" -> leftAnalog || rightAnalog
        else -> supportsAction(id)
    }

    /** Symbols printed on the console's physical buttons are language independent. */
    fun buttonLabel(id: String): String? = when {
        platform == ConsolePlatform.GAMECUBE && id == "select" -> "Z"
        platform == ConsolePlatform.WII && id == "start" -> "+"
        platform == ConsolePlatform.WII && id == "select" -> "\u2212"
        platform == ConsolePlatform.WII && id == "home" -> "\u2302"
        platform == ConsolePlatform.WII && wiiProfile != "classic" && id == "x" -> "1"
        platform == ConsolePlatform.WII && wiiProfile != "classic" && id == "y" -> "2"
        platform == ConsolePlatform.WII && wiiProfile == "nunchuk" && id == "l" -> "C"
        platform == ConsolePlatform.WII && wiiProfile == "nunchuk" && id == "r" -> "Z"
        else -> null
    }

    fun customControls(controls: List<CustomTouchControl>): List<CustomTouchControl> = controls
        .filter { supportsAction(it.actionId) }
        .map { control ->
            if (control.secondaryActionId == null || supportsAction(control.secondaryActionId)) control
            else control.copy(secondaryActionId = null)
        }

    companion object {
        private val BUTTON_ACTIONS = setOf("a", "b", "x", "y", "l", "r", "zl", "zr", "start", "select", "home",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right")
        fun forPlatform(platform: ConsolePlatform, wiiProfile: String = "nunchuk"): ConsoleOverlayPolicy =
            ConsoleOverlayPolicy(platform, wiiProfile.takeIf { platform == ConsolePlatform.WII && it in setOf("remote", "nunchuk", "classic") }
                ?: "nunchuk")
    }
}
