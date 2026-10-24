package com.sbro.emucored.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.sbro.emucored.R
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.ui.common.ScrollableFilterTabRow
import com.sbro.emucored.ui.common.gamePlatformLabelResource

/** The same tab component and colors as Settings, shared by grid, list and shelf views. */
@Composable
internal fun LibraryPlatformTabs(
    available: List<GamePlatform>,
    selected: Set<GamePlatform>,
    onSelected: (GamePlatform?) -> Unit,
    horizontalContentPadding: Dp,
    modifier: Modifier = Modifier
) {
    if (available.isEmpty()) return
    var lastClicked by remember { mutableStateOf<GamePlatform?>(null) }
    ScrollableFilterTabRow(
        tabs = listOf(null) + available,
        selectedTab = lastClicked?.takeIf { it in available } ?: selected.firstOrNull(),
        onSelected = { lastClicked = it; onSelected(it) },
        isSelected = { if (it == null) selected.isEmpty() else it in selected },
        key = { it?.name ?: "all" },
        label = { platform ->
            stringResource(platform?.let(::gamePlatformLabelResource) ?: R.string.achievement_filter_all)
        },
        icon = { Icons.Rounded.Gamepad },
        horizontalContentPadding = horizontalContentPadding,
        modifier = modifier
    )
}
