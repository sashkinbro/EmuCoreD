package com.sbro.emucored.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    private fun FramePacer.waitNanos(now: Long, stepUs: Long, speed: Double = 1.0): Long =
        (nextFrameDeadlineNanos(now, stepUs, speed) - now).coerceAtLeast(0L)

    @Test fun vblankCountQuantizesEmulatedSteps() {
        assertEquals(1, vblankCount(16_683))
        assertEquals(1, vblankCount(8_000))
        assertEquals(2, vblankCount(33_367))
        assertEquals(10, vblankCount(167_000))
        assertEquals(1, vblankCount(0))
        assertEquals(32, vblankCount(5_000_000))
    }

    @Test fun sixtyHertzContentWaitsOneVblankAfterEachFrame() {
        val pacer = FramePacer()
        var now = 0L
        var frames = 0
        while (now < 5_000_000_000L) {
            now += pacer.waitNanos(now, 16_683)
            now += 3_000_000L
            frames++
        }
        assertEquals(59.94, frames * 1e9 / now, 0.5)
    }

    @Test fun thirtyHertzContentWaitsTwoVblanks() {
        val pacer = FramePacer()
        var now = 0L
        var frames = 0
        while (now < 5_000_000_000L) {
            now += pacer.waitNanos(now, 33_367)
            now += 3_000_000L
            frames++
        }
        assertEquals(29.97, frames * 1e9 / now, 0.3)
    }

    @Test fun skippedVblanksAreHeldToRealTime() {
        val pacer = FramePacer()
        assertEquals(VBLANK_NANOS, pacer.waitNanos(0, 16_683))
        // A load skipped nine further vblanks; the wait covers them all.
        assertEquals((10 * VBLANK_NANOS).toDouble(),
            pacer.waitNanos(VBLANK_NANOS, 166_830).toDouble(), 1_000_000.0)
    }

    @Test fun slowFramesDoNotAccumulateDebt() {
        val pacer = FramePacer()
        var now = 0L
        repeat(120) {
            now += pacer.waitNanos(now, 16_683)
            now += 40_000_000L // The host needs 40 ms for a one-vblank frame.
        }
        // Once behind, the pacer stops adding waits instead of building debt.
        assertTrue(now in 120 * 40_000_000L..120 * 40_000_000L + 50_000_000L)
    }

    @Test fun rateIncreaseDoesNotStallTheNextFrame() {
        val pacer = FramePacer()
        pacer.nextFrameDeadlineNanos(0, 33_367)
        pacer.nextFrameDeadlineNanos(33_366_666, 33_367)
        // The game picks up to 60 fps: the next start must not wait a full step.
        val wait = pacer.waitNanos(66_733_332, 16_683)
        assertTrue(wait <= 2 * VBLANK_NANOS)
    }
}
