package com.sbro.emucored.core

/**
 * App performance profiles. Both entries map onto real Azahar core options:
 * the CPU clock percentage and the JIT, so a profile never invents settings
 * the core does not understand.
 */
data class PerformanceProfileConfig(
    val id: Int,
    val cpuClockPercentage: Int,
    val useCpuJit: Boolean
)

object PerformanceProfiles {
    const val SAFE = 0
    const val FAST = 1

    val safeConfig = PerformanceProfileConfig(
        id = SAFE,
        cpuClockPercentage = 100,
        useCpuJit = true
    )

    val fastConfig = PerformanceProfileConfig(
        id = FAST,
        cpuClockPercentage = 200,
        useCpuJit = true
    )

    fun normalize(profileId: Int): Int {
        return if (profileId == FAST) FAST else SAFE
    }

    fun configFor(profileId: Int): PerformanceProfileConfig {
        return if (normalize(profileId) == FAST) fastConfig else safeConfig
    }
}
