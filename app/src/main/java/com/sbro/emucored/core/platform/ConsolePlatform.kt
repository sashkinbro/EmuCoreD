package com.sbro.emucored.core.platform

import com.sbro.emucored.data.GamePlatform

enum class CoreId { AZAHAR, MELONDS, DOLPHIN, MGBA, SAMEBOY, NESTOPIA, BSNES }

/** Product identities are independent of engines: one engine can emulate several consoles. */
enum class ConsolePlatform(
    val id: String,
    val core: CoreId,
    val catalogPlatformId: Int,
    val isLaunchable: Boolean,
    val catalogPlatformIds: Set<Int> = setOf(catalogPlatformId)
) {
    N3DS("3ds", CoreId.AZAHAR, 37, true, setOf(37, 137)),
    NDS("nds", CoreId.MELONDS, 20, true, setOf(20, 159)),
    GAMECUBE("gamecube", CoreId.DOLPHIN, 21, false),
    WII("wii", CoreId.DOLPHIN, 5, false),
    GBA("gba", CoreId.MGBA, 24, false),
    GB("gb", CoreId.SAMEBOY, 33, false),
    GBC("gbc", CoreId.SAMEBOY, 22, false),
    NES("nes", CoreId.NESTOPIA, 18, false, setOf(18, 99)),
    SNES("snes", CoreId.BSNES, 19, false, setOf(19, 58));

    companion object {
        fun fromId(id: String?): ConsolePlatform? = entries.firstOrNull { it.id == id }
        fun fromCatalogPlatformId(id: Int): ConsolePlatform? = entries.firstOrNull { id in it.catalogPlatformIds }

        fun fromGamePlatform(platform: GamePlatform): ConsolePlatform = valueOf(platform.name)

        /** Candidate discovery only. Disc/executable headers must resolve ambiguous containers. */
        fun candidatesForFileName(name: String): Set<ConsolePlatform> {
            return com.sbro.emucored.core.ConsoleGameFormats.candidatesForFileName(name)
        }
    }
}
