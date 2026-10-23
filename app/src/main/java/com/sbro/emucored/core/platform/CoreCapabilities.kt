package com.sbro.emucored.core.platform

/** Implemented frontend features, not a promise of everything an upstream engine can do. */
class CoreCapabilities private constructor(
    val rendererIds: List<Int>,
    val defaultRenderer: Int?,
    val supportsAzaharOptions: Boolean,
    val screenCount: Int,
    private val internalScaleLimits: Map<Int, Int>
) {
    val supportsScreenSwap: Boolean get() = screenCount == 2

    fun maxInternalScale(rendererId: Int): Int = internalScaleLimits[rendererId] ?: 1

    companion object {
        private val azahar = CoreCapabilities(listOf(12, 14), 14, true, 2, mapOf(12 to 10, 14 to 10))
        private val melonDs = CoreCapabilities(listOf(14, 13), 14, false, 2, mapOf(14 to 8, 13 to 1))
        private val pending = CoreCapabilities(emptyList(), null, false, 1, emptyMap())

        fun forPlatform(platform: ConsolePlatform): CoreCapabilities = when (platform) {
            ConsolePlatform.N3DS -> azahar
            ConsolePlatform.NDS -> melonDs
            ConsolePlatform.GAMECUBE, ConsolePlatform.WII, ConsolePlatform.GBA,
            ConsolePlatform.GB, ConsolePlatform.GBC -> pending
        }
    }
}
