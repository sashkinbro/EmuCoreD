package com.sbro.emucored.core.platform

import org.junit.Assert.*
import org.junit.Test

class ConsolePlatformTest {
    @Test fun stableIdsCoverTheApprovedConsolesWithoutEnablingUnfinishedAdapters() {
        assertEquals(setOf("3ds", "nds", "gamecube", "wii", "gba", "gb", "gbc"),
            ConsolePlatform.entries.map { it.id }.toSet())
        assertEquals(setOf(ConsolePlatform.N3DS, ConsolePlatform.NDS),
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
        assertEquals(7, ConsolePlatform.entries.map { it.catalogPlatformId }.toSet().size)
    }

    @Test fun fileCandidatesAreCaseInsensitiveAndDoNotGuessAmbiguousDiscIdentity() {
        assertEquals(setOf(ConsolePlatform.NDS), ConsolePlatform.candidatesForFileName("Contra.NDS"))
        assertEquals(setOf(ConsolePlatform.GBA), ConsolePlatform.candidatesForFileName("Game.GBA"))
        assertEquals(setOf(ConsolePlatform.GBC), ConsolePlatform.candidatesForFileName("Game.gbc"))
        assertEquals(setOf(ConsolePlatform.GB), ConsolePlatform.candidatesForFileName("Game.gb"))
        assertEquals(setOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII),
            ConsolePlatform.candidatesForFileName("Game.iso"))
        assertEquals(setOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII),
            ConsolePlatform.candidatesForFileName("Game.rvz"))
        assertTrue(ConsolePlatform.candidatesForFileName("Game.zip").isEmpty())
        assertTrue(ConsolePlatform.candidatesForFileName("not-a-game.txt").isEmpty())
        assertTrue(ConsolePlatform.candidatesForFileName("Game.gba.backup").isEmpty())
    }
}
