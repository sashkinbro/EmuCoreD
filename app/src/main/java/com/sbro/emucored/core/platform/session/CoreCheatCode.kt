package com.sbro.emucored.core.platform.session

import com.sbro.emucored.core.platform.CoreId
import java.util.Locale

/** A user-enabled typed block. Unsupported formats must never be auto-detected. */
data class CoreCheatCode(val format: String, val code: String)

object CoreCheatFormats {
    private val formats = mapOf(
        CoreId.AZAHAR to setOf("3ds-gateway"),
        CoreId.MELONDS to setOf("nds-action-replay"),
        CoreId.DOLPHIN to setOf("dolphin-action-replay", "dolphin-gecko"),
        CoreId.MGBA to setOf("gba-codebreaker", "gba-action-replay", "gba-gameshark"),
        CoreId.SAMEBOY to setOf("gb-game-genie", "gb-gameshark"),
        CoreId.NESTOPIA to setOf("nes-game-genie", "nes-raw-address-value"),
        CoreId.BSNES to setOf("snes-game-genie", "snes-pro-action-replay", "snes-raw-address-value")
    )
    fun supportsCore(core: CoreId): Boolean = formats[core]?.isNotEmpty() == true
    fun supports(core: CoreId, format: String): Boolean = format in formats[core].orEmpty()
    fun accepts(core: CoreId, cheat: CoreCheatCode): Boolean {
        if (!supports(core, cheat.format) || cheat.code.length !in 1..65_536) return false
        val lines = cheat.code.uppercase(Locale.ROOT).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return false
        val pattern = when (cheat.format) {
            "3ds-gateway", "nds-action-replay", "dolphin-action-replay", "dolphin-gecko",
            "gba-action-replay", "gba-gameshark" -> "[0-9A-F]{8} [0-9A-F]{8}"
            "gba-codebreaker" -> "[0-9A-F]{8} [0-9A-F]{4}"
            "gb-gameshark" -> "[0-9A-F]{8}"
            "gb-game-genie" -> "[0-9A-F]{3}-[0-9A-F]{3}(-[0-9A-F]{3})?"
            "nes-game-genie" -> "[APZLGITYEOXUKSVN]{6}([APZLGITYEOXUKSVN]{2})?"
            "nes-raw-address-value" -> "[0-9A-F]{4}:[0-9A-F]{2}(:[0-9A-F]{2})?"
            "snes-game-genie" -> "[0-9A-F]{4}-[0-9A-F]{4}"
            "snes-pro-action-replay" -> "[0-9A-F]{8}"
            "snes-raw-address-value" -> "[0-9A-F]{6}:[0-9A-F]{2}"
            else -> return false
        }
        val regex=Regex(pattern)
        return lines.all(regex::matches)
    }
}
