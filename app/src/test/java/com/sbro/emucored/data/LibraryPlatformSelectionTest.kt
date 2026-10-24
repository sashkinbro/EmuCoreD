package com.sbro.emucored.data

import org.junit.Assert.*
import org.junit.Test

class LibraryPlatformSelectionTest {
    @Test fun everyConsoleFilterSurvivesLoadingAndSeparatesIdenticalTitles() {
        val platforms = listOf("N3DS", "NDS", "GAMECUBE", "WII", "GBA", "GB", "GBC", "NES", "SNES")
            .map(GamePlatform::valueOf)
        val games = platforms.reversed().map { game(it, "/games/${it.name}/shared") }
        for (platform in platforms) {
            val loading = resolveLibraryPlatformSelection(emptyList(), platform, libraryLoaded = false)
            assertEquals(platform, loading.selected)
            val loaded = resolveLibraryPlatformSelection(games, loading.selected)
            assertEquals(platforms, loaded.available)
            assertEquals(platform, loaded.selected)
            assertEquals(listOf(games.single { it.platform == platform }), games.filterLibraryPlatform(platform))
            assertNull(resolveLibraryPlatformSelection(games.filterNot { it.platform == platform }, platform).selected)
        }
        assertEquals(games, games.filterLibraryPlatform(null))
    }

    private fun game(platform: GamePlatform, path: String) =
        GameItem("Shared title", path, path.substringAfterLast('/'), 1, 0, platform = platform)

    @Test fun sameNamedGamesRemainSeparateAndAllRetainsBoth() {
        val games = listOf(game(GamePlatform.N3DS, "/games/shared.3ds"), game(GamePlatform.NDS, "/games/shared.nds"))
        assertEquals(games, games.filterLibraryPlatform(null))
        assertEquals(listOf(games[1]), games.filterLibraryPlatform(GamePlatform.NDS))
        assertEquals(listOf(games[0]), games.filterLibraryPlatform(GamePlatform.N3DS))
    }

    @Test fun availablePlatformsComeFromTheWholeLibraryInStableOrder() {
        val games = listOf(game(GamePlatform.NDS, "b.nds"), game(GamePlatform.N3DS, "a.3ds"), game(GamePlatform.NDS, "c.nds"))
        val selection = resolveLibraryPlatformSelection(games, GamePlatform.NDS)
        assertEquals(listOf(GamePlatform.N3DS, GamePlatform.NDS), selection.available)
        assertEquals(GamePlatform.NDS, selection.selected)
    }

    @Test fun rescanRemovingTheSelectedConsoleReturnsToAll() {
        val remaining = listOf(game(GamePlatform.N3DS, "remaining.3ds"))
        assertNull(resolveLibraryPlatformSelection(remaining, GamePlatform.NDS).selected)
        assertNull(resolveLibraryPlatformSelection(emptyList(), GamePlatform.NDS).selected)
        assertTrue(resolveLibraryPlatformSelection(emptyList(), null).available.isEmpty())
    }

    @Test fun recentGamesAndSearchResultsUseTheSamePlatformPredicateWithoutReordering() {
        val ds = game(GamePlatform.NDS, "one.nds")
        val threeDs = game(GamePlatform.N3DS, "two.3ds")
        val otherDs = game(GamePlatform.NDS, "three.nds")
        assertEquals(listOf(otherDs, ds), listOf(otherDs, threeDs, ds).filterLibraryPlatform(GamePlatform.NDS))
        assertTrue(listOf(threeDs).filterLibraryPlatform(GamePlatform.NDS).isEmpty())
    }

    @Test fun restoredFilterSurvivesPreferenceEmissionBeforeLibraryCacheHasLoaded() {
        val loading = resolveLibraryPlatformSelection(emptyList(), GamePlatform.NDS, libraryLoaded = false)
        assertEquals(GamePlatform.NDS, loading.selected)
        assertTrue(loading.available.isEmpty())
        val loaded = resolveLibraryPlatformSelection(listOf(game(GamePlatform.NDS, "game.nds")), loading.selected)
        assertEquals(GamePlatform.NDS, loaded.selected)
    }
}
