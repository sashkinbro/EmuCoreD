package com.sbro.emucored.core

import org.junit.Assert.*
import org.junit.Test

class ConsoleImagePartsTest {
    @Test fun wbfsPartsAreCaseInsensitiveOrderedAndNeverIncludeUnrelatedFiles() {
        assertEquals(listOf("Game.WBFS", "game.WBF1", "Game.wbf2"),
            ConsoleImageParts.parts("Game.WBFS", listOf("notes.txt", "Game.wbf2", "other.wbf1", "game.WBF1", "Game.WBFS")))
        assertEquals(listOf("solo.wbfs"), ConsoleImageParts.parts("solo.wbfs", listOf("solo.wbfs")))
    }

    @Test fun splitIsoRequiresPartOneAndRejectsGapsAndAmbiguousCaseDuplicates() {
        assertEquals(listOf("G.PART0.ISO", "g.part1.iso", "G.part2.iso"),
            ConsoleImageParts.parts("G.PART0.ISO", listOf("G.part2.iso", "G.PART0.ISO", "g.part1.iso")))
        assertNull(ConsoleImageParts.parts("g.part0.iso", listOf("g.part0.iso")))
        assertNull(ConsoleImageParts.parts("g.part0.iso", listOf("g.part0.iso", "g.part2.iso")))
        assertNull(ConsoleImageParts.parts("g.wbfs", listOf("g.wbfs", "g.wbf2")))
        assertNull(ConsoleImageParts.parts("g.wbfs", listOf("g.wbfs", "g.wbf1", "G.WBF1")))
    }

    @Test fun stagingNamesAreFixedAndUnsafeProviderNamesNeverResolveOutsideTheFolder() {
        assertNull(ConsoleImageParts.parts("../g.wbfs", listOf("../g.wbfs")))
        assertNull(ConsoleImageParts.parts("g\\g.part0.iso", emptyList()))
        assertEquals("image.wbfs", ConsoleImageParts.nativeName("g.WBFS", 0))
        assertEquals("image.wbf2", ConsoleImageParts.nativeName("g.wbfs", 2))
        assertEquals("image.part1.iso", ConsoleImageParts.nativeName("g.PART0.ISO", 1))
    }

    @Test fun onlyTheFirstSplitComponentIsDiscoverableAsAGame() {
        assertTrue(ConsoleGameFormats.isSupportedName("game.PART0.ISO"))
        for (name in listOf("game.part1.iso", "game.PART42.ISO", "game.wbf1", "game.cue", "game.bin", "game.m3u", "hif_000000.nfs"))
            assertFalse(name, ConsoleGameFormats.isSupportedName(name))
        assertTrue(ConsoleGameFormats.isSupportedName("normal.iso"))
    }
}
