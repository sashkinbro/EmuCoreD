package com.sbro.emucored.core.platform

import com.sbro.emucored.core.N3dsGameFormats
import com.sbro.emucored.data.GamePlatform
import java.util.Locale

enum class CoreId { AZAHAR, MELONDS, DOLPHIN, MGBA, SAMEBOY }

/** Product identities are independent of engines: one engine can emulate several consoles. */
enum class ConsolePlatform(
    val id: String,
    val core: CoreId,
    val catalogPlatformId: Int,
    val isLaunchable: Boolean
) {
    N3DS("3ds", CoreId.AZAHAR, 37, true),
    NDS("nds", CoreId.MELONDS, 20, true),
    GAMECUBE("gamecube", CoreId.DOLPHIN, 21, false),
    WII("wii", CoreId.DOLPHIN, 5, false),
    GBA("gba", CoreId.MGBA, 24, false),
    GB("gb", CoreId.SAMEBOY, 33, false),
    GBC("gbc", CoreId.SAMEBOY, 22, false);

    companion object {
        fun fromId(id: String?): ConsolePlatform? = entries.firstOrNull { it.id == id }

        fun fromGamePlatform(platform: GamePlatform): ConsolePlatform = when (platform) {
            GamePlatform.N3DS -> N3DS
            GamePlatform.NDS -> NDS
        }

        /** Candidate discovery only. Disc/executable headers must resolve ambiguous containers. */
        fun candidatesForFileName(name: String): Set<ConsolePlatform> {
            val extension = name.substringAfterLast('/').substringAfterLast('\\')
                .substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                "nds", "srl", "dsi" -> setOf(NDS)
                "gba", "agb" -> setOf(GBA)
                "gb", "sgb" -> setOf(GB)
                "gbc", "cgb" -> setOf(GBC)
                "gcm" -> setOf(GAMECUBE)
                "wbfs", "wad" -> setOf(WII)
                "iso", "rvz", "wia", "gcz", "ciso" -> setOf(GAMECUBE, WII)
                "dol" -> setOf(GAMECUBE, WII)
                "elf" -> setOf(N3DS, GAMECUBE, WII)
                in N3dsGameFormats.extensions -> setOf(N3DS)
                else -> emptySet()
            }
        }
    }
}
