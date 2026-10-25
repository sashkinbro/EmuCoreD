package com.sbro.emucored.ui.home

import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLibrarySectionsTest {
    private fun game(title: String, platform: GamePlatform) = GameItem(
        title = title,
        path = title,
        fileName = title,
        fileSize = 1,
        lastModified = 0,
        platform = platform
    )

    @Test fun emptyLibraryHasNoSections() {
        assertTrue(buildHomeLibrarySections(emptyList(), HomeSortOption.PLATFORM).isEmpty())
    }

    @Test fun platformSortGroupsInConsoleOrderAndKeepsTitleOrder() {
        val games = listOf(
            game("Mario", GamePlatform.NES),
            game("Zelda", GamePlatform.SNES),
            game("Metroid", GamePlatform.NES),
            game("Pokemon", GamePlatform.GB)
        )

        val sections = buildHomeLibrarySections(games, HomeSortOption.PLATFORM)

        assertEquals(
            listOf(GamePlatform.GB, GamePlatform.NES, GamePlatform.SNES),
            sections.map { it.platform }
        )
        assertEquals(listOf("Pokemon"), sections[0].games.map { it.title })
        assertEquals(listOf("Mario", "Metroid"), sections[1].games.map { it.title })
        assertEquals(listOf("Zelda"), sections[2].games.map { it.title })
    }

    @Test fun otherSortModesRemainOneUnlabelledSection() {
        val games = listOf(
            game("Mario", GamePlatform.NES),
            game("Pokemon", GamePlatform.GB)
        )

        val sections = buildHomeLibrarySections(games, HomeSortOption.TITLE_ASC)

        assertEquals(1, sections.size)
        assertNull(sections.single().platform)
        assertEquals(games, sections.single().games)
    }
}
