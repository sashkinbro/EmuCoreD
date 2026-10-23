package com.sbro.emucored.core.platform

/** Upstream attribution, including engines whose Android adapters are still pending. */
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
        }
    }
}
