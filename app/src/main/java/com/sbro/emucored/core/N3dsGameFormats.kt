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
        "z3ds",
        "zcia",
        "zcci",
        "zcxi",
    )

    /** Cartridge and installed-title images accepted by the core. */
    val libraryFormats = listOf("3DS", "CCI", "CXI", "APP", "CIA", "Z3DS", "ZCCI", "ZCXI", "ZCIA")

    /** Homebrew and executable formats. */
    val homebrewFormats = listOf("3DSX", "Z3DSX", "ELF", "AXF")

    fun isSupportedName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return fileName.substringAfterLast('.', "") in extensions
    }

    /**
     * CIA images cannot be booted like cartridge dumps: the loader only opens
     * NCCH/NCSD containers, so a CIA must be installed into the emulated NAND
     * before its title can run.
     */
    fun isCiaFile(name: String): Boolean {
        val fileName = name.substringAfterLast('/')
        return fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("cia", "zcia")
    }
}
