package com.sbro.emucored.core

import androidx.annotation.Keep
import com.sbro.emucored.core.platform.ConsolePlatform

/** JNI checks API level and the actual KGSL device, not only the SoC name. */
@Keep
internal object GpuDriverHelper {
    external fun supportsCustomDriverLoading(): Boolean
}

object GpuDriverSupport {
    fun canUse(platform: ConsolePlatform, usesVulkan: Boolean, deviceSupported: Boolean): Boolean =
        usesVulkan && deviceSupported && platform in setOf(
            ConsolePlatform.N3DS, ConsolePlatform.NDS, ConsolePlatform.GAMECUBE, ConsolePlatform.WII
        )

    fun deviceSupported(): Boolean = EmuCoreNative.isLoaded &&
        runCatching { GpuDriverHelper.supportsCustomDriverLoading() }.getOrDefault(false)
}
