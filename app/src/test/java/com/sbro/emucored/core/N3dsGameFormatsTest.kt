package com.sbro.emucored.core

import org.junit.Assert.*
import org.junit.Test

class N3dsGameFormatsTest {
    @Test fun acceptsCoreExecutableAndGameCardNamesRegardlessOfCase() {
        for (name in listOf(
            "game.3DS",
            "game.cci",
            "game.CXI",
            "game.APP",
            "game.cia",
            "homebrew.3dsx",
            "cube.ELF",
            "cube.axf",
            "game.zcci",
            "game.ZCXI",
            "homebrew.Z3DSX",
            "GAME.3DSX",
        )) {
            assertTrue(name, N3dsGameFormats.isSupportedName(name))
        }
    }

    @Test fun rejectsUnsupportedContainersAndUnrelatedFiles() {
        for (name in listOf(
            "game.iso",
            "game.cso",
            "game.chd",
            "game.pbp",
            "game.prx",
            "game.zip",
            "game.7z",
            "game.rar",
            "game.cia.png",
            "",
        )) {
            assertFalse(name, N3dsGameFormats.isSupportedName(name))
        }
    }
}
