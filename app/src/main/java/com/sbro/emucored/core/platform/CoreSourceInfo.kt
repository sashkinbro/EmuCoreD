package com.sbro.emucored.core.platform

/** Upstream attribution for the integrated Nintendo engines. */
data class CoreSourceInfo(
    val core: CoreId,
    val name: String,
    val repositoryUrl: String,
    val licenseFamily: String
) {
    val platforms: List<ConsolePlatform> get() = ConsolePlatform.entries.filter { it.core == core }
    val isIntegrated: Boolean get() = platforms.any { it.isLaunchable }

    companion object {
        fun forCore(core: CoreId): CoreSourceInfo = when (core) {
            CoreId.AZAHAR -> CoreSourceInfo(core, "Azahar", "https://github.com/azahar-emu/azahar", "GPL")
            CoreId.MELONDS -> CoreSourceInfo(core, "melonDS", "https://github.com/melonDS-emu/melonDS", "GPL-3.0-or-later")
            // COPYING describes the aggregate and the individual source licenses.
            CoreId.DOLPHIN -> CoreSourceInfo(core, "Dolphin", "https://github.com/dolphin-emu/dolphin", "GPL (COPYING)")
            CoreId.MGBA -> CoreSourceInfo(core, "mGBA", "https://github.com/mgba-emu/mgba", "MPL-2.0")
            CoreId.SAMEBOY -> CoreSourceInfo(core, "SameBoy", "https://github.com/LIJI32/SameBoy", "Expat/MIT")
            CoreId.NESTOPIA -> CoreSourceInfo(core, "Nestopia JG", "https://gitlab.com/jgemu/nestopia", "GPL-2.0-or-later")
            CoreId.BSNES -> CoreSourceInfo(core, "bsnes", "https://github.com/bsnes-emu/bsnes", "GPL-3.0-or-later")
            CoreId.MUPEN64 -> CoreSourceInfo(core, "Mupen64Plus-Next", "https://github.com/libretro/mupen64plus-libretro-nx", "GPL-2.0")
        }
    }
}
