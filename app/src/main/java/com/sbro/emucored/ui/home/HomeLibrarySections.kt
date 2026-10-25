package com.sbro.emucored.ui.home

import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform

/** A run of library games that share a platform, or one ungrouped run for other sorts. */
internal data class HomeLibrarySection(
    val platform: GamePlatform?,
    val games: List<GameItem>
)

/**
 * Groups the already sorted library by platform for the default platform sort.
 * Other sort modes keep a single section so the catalog reads as one list.
 */
internal fun buildHomeLibrarySections(
    games: List<GameItem>,
    sortOption: HomeSortOption
): List<HomeLibrarySection> {
    if (games.isEmpty()) return emptyList()
    if (sortOption != HomeSortOption.PLATFORM) {
        return listOf(HomeLibrarySection(platform = null, games = games))
    }
    val byPlatform = games.groupBy(GameItem::platform)
    return GamePlatform.entries.mapNotNull { platform ->
        byPlatform[platform]?.let { HomeLibrarySection(platform, it) }
    }
}
