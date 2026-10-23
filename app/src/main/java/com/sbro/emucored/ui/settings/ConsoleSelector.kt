package com.sbro.emucored.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ScrollableFilterTabRow

@Composable
internal fun ConsoleSelector(selected: ConsolePlatform, onSelected: (ConsolePlatform) -> Unit) {
    ScrollableFilterTabRow(
        tabs = ConsolePlatform.entries.filter { it.isLaunchable },
        selectedTab = selected,
        onSelected = onSelected,
        key = { it.id },
        label = { platform ->
            stringResource(when (platform) {
                ConsolePlatform.NDS -> R.string.profile_platform_ds
                ConsolePlatform.N3DS -> R.string.profile_platform_3ds
                else -> error("A localized label is required before enabling a console")
            })
        },
        icon = { Icons.Rounded.Gamepad },
        verticalPadding = 0.dp
    )
}
