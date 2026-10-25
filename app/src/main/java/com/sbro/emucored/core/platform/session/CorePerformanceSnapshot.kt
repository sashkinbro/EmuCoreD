package com.sbro.emucored.core.platform.session

import com.sbro.emucored.core.ds.GuestFrameRateSampler

/** Actual guest throughput. Null fields mean the engine has no measured value yet. */
data class CorePerformanceSnapshot(
    val frames: Long,
    val fps: Double? = null,
    val targetFps: Double? = null,
    val speedPercent: Double? = null,
    val frameTimeMs: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val backend: String? = null
)

internal class CorePerformanceSampler {
    private var target: Double? = null
    private var sampler = GuestFrameRateSampler(1.0)

    @Synchronized fun reset() { sampler.reset(); target = null }

    @Synchronized fun sample(
        nowMillis: Long, frames: Long, targetFps: Double?, running: Boolean, paused: Boolean
    ): CorePerformanceSnapshot? {
        if (!running || paused) { reset(); return null }
        val actualTarget = targetFps?.takeIf { it.isFinite() && it > 0 }
        if (target != actualTarget) {
            target = actualTarget
            sampler = GuestFrameRateSampler(actualTarget ?: 1.0)
        }
        val value = sampler.sample(nowMillis, frames) ?: return null
        return CorePerformanceSnapshot(frames, value.fps, actualTarget,
            actualTarget?.let { value.speedPercent }, value.frameTimeMs)
    }
}
