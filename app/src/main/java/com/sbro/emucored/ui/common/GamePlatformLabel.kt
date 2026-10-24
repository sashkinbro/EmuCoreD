package com.sbro.emucored.ui.common

import androidx.annotation.StringRes
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.GamePlatform

/** Uses the same translated console names in the library, profile and game details. */
@StringRes
fun gamePlatformLabelResource(platform: GamePlatform): Int = when (ConsolePlatform.valueOf(platform.name)) {
    ConsolePlatform.N3DS -> R.string.profile_platform_3ds
    ConsolePlatform.NDS -> R.string.profile_platform_ds
    ConsolePlatform.GAMECUBE -> R.string.console_gamecube
    ConsolePlatform.WII -> R.string.console_wii
    ConsolePlatform.GBA -> R.string.console_gba
    ConsolePlatform.GB -> R.string.console_gb
    ConsolePlatform.GBC -> R.string.console_gbc
    ConsolePlatform.NES -> R.string.console_nes
    ConsolePlatform.SNES -> R.string.console_snes
}
