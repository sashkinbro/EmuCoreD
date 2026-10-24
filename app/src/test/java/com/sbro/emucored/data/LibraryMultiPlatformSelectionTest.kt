package com.sbro.emucored.data

import org.junit.Assert.*
import org.junit.Test

class LibraryMultiPlatformSelectionTest {
    private val games = listOf(GamePlatform.NDS, GamePlatform.GBA, GamePlatform.N3DS, GamePlatform.SNES)
        .map { GameItem("Shared title", "/${it.name}", it.name, 1, 0, platform = it) }

    @Test fun severalConsolesFilterAsAUnionWithoutDuplicatesOrReordering() {
        val selection = setOf(GamePlatform.NDS, GamePlatform.GBA, GamePlatform.SNES)
        assertEquals(listOf(games[0], games[1], games[3]), games.filterLibraryPlatforms(selection))
        assertEquals(games, games.filterLibraryPlatforms(emptySet()))
    }

    @Test fun selectingAndDeselectingChipsDoesNotReplaceTheOtherSelections() {
        var selected = toggleLibraryPlatform(emptySet(), GamePlatform.NDS)
        selected = toggleLibraryPlatform(selected, GamePlatform.GBA)
        assertEquals(setOf(GamePlatform.NDS, GamePlatform.GBA), selected)
        selected = toggleLibraryPlatform(selected, GamePlatform.NDS)
        assertEquals(setOf(GamePlatform.GBA), selected)
        assertTrue(toggleLibraryPlatform(selected, null).isEmpty())
        assertTrue(toggleLibraryPlatform(selected, GamePlatform.GBA).isEmpty())
    }

    @Test fun rescanRemovesOnlyMissingConsolesAndLoadingPreservesTheWholeSelection() {
        val selected = setOf(GamePlatform.NDS, GamePlatform.GBA, GamePlatform.WII)
        assertEquals(selected, resolveLibraryPlatforms(emptyList(), selected, libraryLoaded = false).selected)
        val resolved = resolveLibraryPlatforms(games, selected)
        assertEquals(setOf(GamePlatform.NDS, GamePlatform.GBA), resolved.selected)
        assertEquals(listOf(GamePlatform.N3DS, GamePlatform.NDS, GamePlatform.GBA, GamePlatform.SNES), resolved.available)
        assertTrue(resolveLibraryPlatforms(emptyList(), selected).selected.isEmpty())
    }
}
