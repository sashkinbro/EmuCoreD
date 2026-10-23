package com.sbro.emucored.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.ui.common.ScrollableFilterTabRow

@Composable
internal fun ConsoleSelector(selected: ConsolePlatform, onSelected: (ConsolePlatform) -> Unit) {
    ScrollableFilterTabRow(
        tabs = ConsolePlatform.entries,
        selectedTab = selected,
        onSelected = onSelected,
        key = { it.id },
        label = { platform ->
            val name = consolePlatformLabel(platform)
            if (platform.isLaunchable) name else stringResource(R.string.console_coming_soon, name)
        },
        icon = { Icons.Rounded.Gamepad },
        verticalPadding = 0.dp,
        modifier = Modifier.testTag("settings-console-tabs"),
        enabled = { it.isLaunchable }
    )
}

@Composable
internal fun consolePlatformLabel(platform: ConsolePlatform): String = stringResource(when (platform) {
    ConsolePlatform.NDS -> R.string.profile_platform_ds
    ConsolePlatform.N3DS -> R.string.profile_platform_3ds
    ConsolePlatform.GAMECUBE -> R.string.console_gamecube
    ConsolePlatform.WII -> R.string.console_wii
    ConsolePlatform.GBA -> R.string.console_gba
    ConsolePlatform.GB -> R.string.console_gb
    ConsolePlatform.GBC -> R.string.console_gbc
    ConsolePlatform.NES -> R.string.console_nes
    ConsolePlatform.SNES -> R.string.console_snes
})
