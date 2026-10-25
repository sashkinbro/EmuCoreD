package com.sbro.emucored.ui.home

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import com.sbro.emucored.data.GameItem

internal fun homeLibraryRowKey(game: GameItem): String = "row_${game.path}"

internal fun LazyGridScope.homeLibraryItems(
    sections: List<HomeLibrarySection>,
    columns: Int,
    listView: Boolean,
    header: @Composable (HomeLibrarySection) -> Unit,
    row: @Composable (List<GameItem>, Int) -> Unit
) {
    // Each row is a lazy item so offscreen cards do not compose or decode covers.
    val count = if (listView) 1 else columns.coerceAtLeast(1)
    sections.forEach { section ->
        section.platform?.let { platform ->
            item(
                key = "platform_${platform.name}",
                contentType = "platform",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                header(section)
            }
        }
        items(
            count = (section.games.size + count - 1) / count,
            key = { index -> homeLibraryRowKey(section.games[index * count]) },
            contentType = { if (listView) "list" else "covers_$count" },
            span = { GridItemSpan(maxLineSpan) }
        ) { index ->
            val start = index * count
            row(section.games.subList(start, minOf(start + count, section.games.size)), count)
        }
    }
}
