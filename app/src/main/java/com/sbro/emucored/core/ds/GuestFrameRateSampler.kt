package com.sbro.emucored.core.ds

import java.util.ArrayDeque

/** Guest-frame throughput with the same 500 ms refresh cadence as EmuCoreX metrics.
 * The cutoff count is interpolated between adjacent counter observations. Faster overlay
 * polls reuse the last published value without rounding slow games up or capping fast-forward.
 */
internal class GuestFrameRateSampler(
    private val nominalFramesPerSecond: Double = 59.826,
    private val windowMillis: Long = 500L
) {
    internal data class Sample(val fps: Double, val speedPercent: Double, val frameTimeMs: Double)
    private data class Observation(val time: Long, val frames: Long)
    private val observations = ArrayDeque<Observation>()
    private var published: Sample? = null
    private var publishedAt: Long? = null

    init {
        require(nominalFramesPerSecond.isFinite() && nominalFramesPerSecond > 0.0)
        require(windowMillis > 0)
    }

    @Synchronized
    fun reset() {
        observations.clear()
        published = null
        publishedAt = null
    }

    @Synchronized
    fun sample(nowMillis: Long, totalFrames: Long): Sample? {
        if (nowMillis < 0 || totalFrames < 0) { reset(); return null }
        val previous = observations.peekLast()
        if (previous == null || nowMillis < previous.time || totalFrames < previous.frames ||
            nowMillis - previous.time > maxOf(1000L, windowMillis)) {
            reset()
            observations.addLast(Observation(nowMillis, totalFrames))
            return null
        }
        // Keep the original baseline for simultaneous polls; no elapsed time can be measured.
        if (nowMillis == previous.time) return published
        observations.addLast(Observation(nowMillis, totalFrames))
        if (nowMillis - observations.first.time < windowMillis) return null
        val cutoff = nowMillis - windowMillis
        while (observations.size > 1) {
            val iterator = observations.iterator()
            iterator.next()
            if (iterator.next().time > cutoff) break
            observations.removeFirst()
        }
        // Counter observations still advance on each poll; only publication is throttled.
        publishedAt?.let { if (nowMillis - it < 500L) return published }
        val first = observations.first
        val beforeCutoff = if (first.time < cutoff) {
            val iterator = observations.iterator()
            iterator.next()
            val next = iterator.next()
            (next.frames - first.frames).toDouble() * (cutoff - first.time) / (next.time - first.time)
        } else 0.0
        val windowFrames = (totalFrames - first.frames).toDouble() - beforeCutoff
        val fps = windowFrames * 1000.0 / windowMillis
        return Sample(fps, fps / nominalFramesPerSecond * 100.0, if (fps > 0.0) 1000.0 / fps else 0.0).also {
            published = it
            publishedAt = nowMillis
        }
    }
}
