package com.sbro.emucored.ui.settings

import com.sbro.emucored.core.KeyboardMouseBindings
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ConsoleOverlayPolicy

/** A read-only UI projection: changing consoles never clears existing bindings or modes. */
internal class KeyboardMouseConsolePolicy(val overlay: ConsoleOverlayPolicy) {
    fun supportsAction(id: String): Boolean = when {
        id in setOf("quick_save", "quick_load") -> true
        id == "toggle_second_screen" -> overlay.platform == ConsolePlatform.N3DS
        id == "swap_screens" -> overlay.platform in setOf(ConsolePlatform.NDS, ConsolePlatform.N3DS)
        id.startsWith("left_stick_") -> overlay.leftAnalog
        id.startsWith("right_stick_") -> overlay.rightAnalog
        else -> overlay.supportsAction(id)
    }

    fun supportsWheel(mode: Int): Boolean {
        if (mode == KeyboardMouseBindings.MOUSE_WHEEL_OFF) return true
        val (up, down) = KeyboardMouseBindings.mouseWheelActions(mode)
        return up != null && down != null && supportsAction(up) && supportsAction(down)
    }

    fun supportsAim(mode: Int): Boolean = when (mode) {
        KeyboardMouseBindings.MOUSE_AIM_OFF -> true
        KeyboardMouseBindings.MOUSE_AIM_LEFT_STICK -> overlay.leftAnalog
        KeyboardMouseBindings.MOUSE_AIM_RIGHT_STICK -> overlay.rightAnalog
        else -> false
    }
}
