package com.sbro.emucored.core

import kotlin.math.roundToInt

/** Largest emulated step a single frame may advance without it being a state jump. */
internal const val MAX_EMULATED_FRAME_STEP_US = 1_000_000L

/** 3DS refresh rate: Azahar reports a 60 Hz frame clock through AV info. */
internal const val VBLANK_RATE_HZ = 60.0

/** One emulated vblank at 60 Hz, in nanoseconds. */
internal const val VBLANK_NANOS = 16_666_667L

/** Emulated vblanks a frame may fall behind before the schedule is rebased. */
private const val MAX_FALL_BEHIND_STEPS = 5.5

/** A frame advances at least one vblank; anything past this is a load, not content. */
private const val MIN_STEP_VBLANKS = 1
private const val MAX_STEP_VBLANKS = 32

/** How many emulated vblanks one frontend frame advanced. */
internal fun vblankCount(stepUs: Long): Int =
    (stepUs / (VBLANK_NANOS / 1000.0)).roundToInt().coerceIn(MIN_STEP_VBLANKS, MAX_STEP_VBLANKS)

/**
 * Mirrors Azahar's DoFrameTiming() (Core/HLE/sceDisplay.cpp).
 *
 * Pacing is scheduled per emulated vblank: a frame that advanced two vblanks (a
 * 30 fps game) waits two vblank periods, and a frame that skipped vblanks during
 * a load waits for them instead of letting the emulator run ahead. A slow host
 * may fall up to [MAX_FALL_BEHIND_STEPS] behind without accumulating debt, and
 * when the schedule is more than two steps away (pause, seek, state load) the
 * wait is dropped rather than stalling the frame loop.
 */
internal class FramePacer {
    private var lastDeadlineNanos = 0L

    fun reset() {
        lastDeadlineNanos = 0L
    }

    /**
     * Absolute time the next frame may start. [stepUs] is the emulated time the
     * previous frame advanced; [speedFactor] scales real time (target-FPS
     * slowdown, fast forward) where 1.0 is the content's own rate. The returned
     * deadline is already in the past when the host is too slow to keep up.
     */
    fun nextFrameDeadlineNanos(nowNanos: Long, stepUs: Long, speedFactor: Double = 1.0): Long {
        if (stepUs <= 0L) return nowNanos

        val vblanks = vblankCount(stepUs)
        val factor = if (speedFactor.isFinite() && speedFactor > 0.01) speedFactor else 1.0
        val stepNanos = (vblanks * VBLANK_NANOS / factor).toLong()

        val next = if (lastDeadlineNanos == 0L) {
            nowNanos + stepNanos
        } else {
            // Azahar: max(lastFrameTime + scaledTimestep, now - maxFallBehindFrames * scaledTimestep).
            maxOf(lastDeadlineNanos + stepNanos,
                nowNanos - (MAX_FALL_BEHIND_STEPS * stepNanos).toLong())
        }

        // A gap over two timesteps is a pause or seek; jump instead of stalling.
        lastDeadlineNanos = if (next - nowNanos > 2 * stepNanos) nowNanos else next
        return lastDeadlineNanos
    }
}
