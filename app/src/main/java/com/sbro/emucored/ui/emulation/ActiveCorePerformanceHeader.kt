package com.sbro.emucored.ui.emulation

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreSourceInfo

/** Labels follow the live owner, never the next game or a cached Azahar version. */
internal fun activePerformancePlatform(additional: ConsolePlatform?, dsActive: Boolean, azaharActive: Boolean): ConsolePlatform? =
    additional ?: if (dsActive) ConsolePlatform.NDS else if (azaharActive) ConsolePlatform.N3DS else null

internal fun performanceCoreHeader(appVersion: String, platform: ConsolePlatform?): String =
    "EmuCoreD $appVersion" + (platform?.let { " | ${CoreSourceInfo.forCore(it.core).name}" } ?: "")
