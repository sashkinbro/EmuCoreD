package com.sbro.emucored.core.platform

import org.junit.Assert.*
import org.junit.Test

class ConsolePlatformTest {
    @Test fun regionalAndEnhancedCatalogsResolveToTheirActualConsoleFamily() {
        val expected = mapOf(37 to ConsolePlatform.N3DS, 137 to ConsolePlatform.N3DS,
            20 to ConsolePlatform.NDS, 159 to ConsolePlatform.NDS, 4 to ConsolePlatform.N64,
            21 to ConsolePlatform.GAMECUBE, 5 to ConsolePlatform.WII,
            24 to ConsolePlatform.GBA, 33 to ConsolePlatform.GB, 22 to ConsolePlatform.GBC,
            18 to ConsolePlatform.NES, 99 to ConsolePlatform.NES,
            19 to ConsolePlatform.SNES, 58 to ConsolePlatform.SNES)
        expected.forEach { (id, platform) ->
            assertEquals("Catalog $id belongs to the wrong family", platform, ConsolePlatform.fromCatalogPlatformId(id))
            assertTrue(id in platform.catalogPlatformIds)
        }
        assertNull(ConsolePlatform.fromCatalogPlatformId(0))
        assertNull(ConsolePlatform.fromCatalogPlatformId(6))
        assertEquals(expected.keys, ConsolePlatform.entries.flatMap { it.catalogPlatformIds }.toSet())
        ConsolePlatform.entries.forEach { assertTrue(it.catalogPlatformId in it.catalogPlatformIds) }
    }

    @Test fun stableIdsCoverAllIntegratedConsoles() {
        assertEquals(setOf("3ds", "nds", "n64", "gamecube", "wii", "gba", "gb", "gbc", "nes", "snes"),
            ConsolePlatform.entries.map { it.id }.toSet())
        assertEquals(ConsolePlatform.entries.toSet(),
            ConsolePlatform.entries.filter { it.isLaunchable }.toSet())
        ConsolePlatform.entries.forEach { assertEquals(it, ConsolePlatform.fromId(it.id)) }
        assertNull(ConsolePlatform.fromId("unknown"))
        assertNull(ConsolePlatform.fromId(null))
    }

    @Test fun sharedEnginesKeepIndependentConsoleIdentities() {
        assertEquals(CoreId.DOLPHIN, ConsolePlatform.GAMECUBE.core)
        assertEquals(CoreId.DOLPHIN, ConsolePlatform.WII.core)
        assertEquals(CoreId.SAMEBOY, ConsolePlatform.GB.core)
        assertEquals(CoreId.SAMEBOY, ConsolePlatform.GBC.core)
        assertEquals(CoreId.MGBA, ConsolePlatform.GBA.core)
        assertEquals(10, ConsolePlatform.entries.map { it.catalogPlatformId }.toSet().size)
    }

    @Test fun fileCandidatesAreCaseInsensitiveAndDoNotGuessAmbiguousDiscIdentity() {
        assertEquals(setOf(ConsolePlatform.NDS), ConsolePlatform.candidatesForFileName("Contra.NDS"))
        for (extension in listOf("z64", "n64", "v64"))
            assertEquals(setOf(ConsolePlatform.N64), ConsolePlatform.candidatesForFileName("Game.$extension"))
        assertEquals(setOf(ConsolePlatform.GBA), ConsolePlatform.candidatesForFileName("Game.GBA"))
        assertEquals(setOf(ConsolePlatform.GBC), ConsolePlatform.candidatesForFileName("Game.gbc"))
        assertEquals(setOf(ConsolePlatform.GB), ConsolePlatform.candidatesForFileName("Game.gb"))
        assertEquals(setOf("nes"), ConsolePlatform.candidatesForFileName("Contra.NES").map { it.id }.toSet())
        assertEquals(setOf("snes"), ConsolePlatform.candidatesForFileName("Game.SFC").map { it.id }.toSet())
        assertEquals(setOf("snes"), ConsolePlatform.candidatesForFileName("Game.smc").map { it.id }.toSet())
        assertEquals(setOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII),
            ConsolePlatform.candidatesForFileName("Game.iso"))
        assertEquals(setOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII),
            ConsolePlatform.candidatesForFileName("Game.rvz"))
        assertTrue(ConsolePlatform.candidatesForFileName("Game.zip").isEmpty())
        assertTrue(ConsolePlatform.candidatesForFileName("not-a-game.txt").isEmpty())
        assertTrue(ConsolePlatform.candidatesForFileName("Game.gba.backup").isEmpty())
    }
}
