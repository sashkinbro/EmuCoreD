package com.sbro.emucored.core

import java.util.Locale

/** File candidates accepted by the Azahar libretro core (retro_get_system_info). */
object N3dsGameFormats {
    val extensions = setOf(
        "3ds",
        "cci",
        "cxi",
        "app",
        "cia",
        "3dsx",
        "elf",
        "axf",
        "z3dsx",
        "zcci",
        "zcxi",
    )

    /** Cartridge and installed-title images accepted by the core. */
    val libraryFormats = listOf("3DS", "CCI", "CXI", "APP", "CIA")

    /** Homebrew and executable formats. */
    val homebrewFormats = listOf("3DSX", "Z3DSX", "ELF", "AXF")

    fun isSupportedName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return fileName.substringAfterLast('.', "") in extensions
    }
}
