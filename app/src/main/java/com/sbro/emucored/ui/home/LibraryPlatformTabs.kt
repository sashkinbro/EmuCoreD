package com.sbro.emucored.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.sbro.emucored.R
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.ui.common.ScrollableFilterTabRow

/** The same tab component and colors as Settings, shared by grid, list and shelf views. */
@Composable
internal fun LibraryPlatformTabs(
    available: List<GamePlatform>,
    selected: GamePlatform?,
    onSelected: (GamePlatform?) -> Unit,
    horizontalContentPadding: Dp,
    modifier: Modifier = Modifier
) {
    if (available.isEmpty()) return
    ScrollableFilterTabRow(
        tabs = listOf(null) + available,
        selectedTab = selected,
        onSelected = onSelected,
        key = { it?.name ?: "all" },
        label = { platform ->
            stringResource(when (platform) {
                GamePlatform.N3DS -> R.string.profile_platform_3ds
                GamePlatform.NDS -> R.string.profile_platform_ds
                null -> R.string.achievement_filter_all
            })
        },
        icon = { Icons.Rounded.Gamepad },
        horizontalContentPadding = horizontalContentPadding,
        modifier = modifier
    )
}
