package com.sbro.emucored.core.ds

import kotlin.math.floor
import org.junit.Assert.*
import org.junit.Test

class GuestFrameRateSamplerTest {
    @Test fun defaultCadenceMeasuresHalfASecondAndCachesBetweenPublications() {
        val sampler = GuestFrameRateSampler()
        assertNull(sampler.sample(0, 0))
        assertNull(sampler.sample(250, 15))
        val first = requireNotNull(sampler.sample(500, 30))
        assertEquals(60.0, first.fps, 0.0)
        assertSame(first, sampler.sample(750, 40))
        val second = requireNotNull(sampler.sample(1000, 50))
        assertEquals(40.0, second.fps, 0.0)
        assertNotSame(first, second)
        assertSame(second, sampler.sample(1250, 75))
        val third = requireNotNull(sampler.sample(1500, 90))
        assertEquals(80.0, third.fps, 0.0)
        assertSame(third, sampler.sample(1500, 90))
        assertEquals(80.0 / 59.826 * 100.0, third.speedPercent, 0.000001)
    }

    @Test fun resetAndInvalidObservationsClearPublishedValuesBeforeFreshWarmup() {
        val sampler = GuestFrameRateSampler()
        sampler.sample(0, 0)
        assertNotNull(sampler.sample(500, 30))
        sampler.reset()
        assertNull(sampler.sample(10_000, 100))
        assertNull(sampler.sample(10_250, 115))
        assertNotNull(sampler.sample(10_500, 130))
        assertNull(sampler.sample(10_750, -1))
        assertNull(sampler.sample(11_000, 140))
        assertNull(sampler.sample(11_250, 155))
        assertNotNull(sampler.sample(11_500, 170))
        assertNull(sampler.sample(11_750, 0))
        assertNull(sampler.sample(12_000, 15))
        assertEquals(60.0, requireNotNull(sampler.sample(12_250, 30)).fps, 0.0)
    }

    @Test fun defaultCadenceAcceptsOneSecondPollsButDropsLongHiddenOverlayGaps() {
        val sampler = GuestFrameRateSampler()
        assertNull(sampler.sample(0, 0))
        assertEquals(53.0, requireNotNull(sampler.sample(1000, 53)).fps, 0.0)
        assertEquals(90.0, requireNotNull(sampler.sample(2000, 143)).fps, 0.0)
        assertNull(sampler.sample(3500, 150))
        assertNull(sampler.sample(3750, 163))
        assertEquals(54.0, requireNotNull(sampler.sample(4000, 177)).fps, 0.0)
    }

    @Test fun nativeRateAtQuarterSecondPollsDoesNotProduceFourFpsQuantization() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        val samples = (0L..60_000L step 250).mapNotNull { time ->
            sampler.sample(time, floor(time * 59.826 / 1000.0).toLong())
        }
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.maxOf { it.fps } - samples.minOf { it.fps } <= 1.000001)
        samples.forEach { assertEquals(59.826, it.fps, 1.0) }
        assertEquals(samples.last().fps / 59.826 * 100.0, samples.last().speedPercent, 0.000001)
    }

    @Test fun infrequentPollsWithinTheWindowStillMeasureTheNativeGuestRate() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        val samples = (0L..30_000L step 750).mapNotNull { time ->
            sampler.sample(time, floor(time * 59.826 / 1000.0).toLong())
        }
        assertTrue(samples.isNotEmpty())
        samples.forEach { assertEquals(59.826, it.fps, 1.5) }
    }

    @Test fun realSlowdownIsReportedWithinOneWindowWithoutClampingOrOldHistory() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        for (time in 0L..2000L step 250) sampler.sample(time, time * 60 / 1000)
        var sample: GuestFrameRateSampler.Sample? = null
        for (elapsed in 250L..1000L step 250) sample = sampler.sample(2000 + elapsed, 120 + elapsed * 53 / 1000)
        assertEquals(53.0, requireNotNull(sample).fps, 0.000001)
        assertEquals(53.0 / 59.826 * 100.0, sample!!.speedPercent, 0.000001)
        assertEquals(1000.0 / 53.0, sample!!.frameTimeMs, 0.000001)
    }

    @Test fun speedsAboveNormalAndARealCompleteStallRemainVisible() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        assertNull(sampler.sample(0, 0))
        assertEquals(90.0, requireNotNull(sampler.sample(1000, 90)).fps, 0.0)
        assertTrue(requireNotNull(sampler.sample(2000, 180)).speedPercent > 100.0)
        val stalled = requireNotNull(sampler.sample(3000, 180))
        assertEquals(0.0, stalled.fps, 0.0)
        assertEquals(0.0, stalled.frameTimeMs, 0.0)
    }

    @Test fun resetDropsPausedOrPreviousSessionTimeAndRequiresFreshWarmup() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        sampler.sample(0, 0)
        assertNotNull(sampler.sample(1000, 60))
        sampler.reset()
        assertNull(sampler.sample(80_000, 60))
        assertNull(sampler.sample(80_250, 75))
        assertEquals(60.0, requireNotNull(sampler.sample(81_000, 120)).fps, 0.0)
        sampler.reset()
        assertNull(sampler.sample(82_000, 0))
        assertEquals(60.0, requireNotNull(sampler.sample(83_000, 60)).fps, 0.0)
    }

    @Test fun hiddenOverlayGapCounterRollbackAndClockRollbackStartNewWindows() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        sampler.sample(0, 0)
        sampler.sample(1000, 60)
        assertNull(sampler.sample(10_000, 100))
        assertEquals(60.0, requireNotNull(sampler.sample(11_000, 160)).fps, 0.0)
        assertNull(sampler.sample(11_250, 1))
        assertEquals(60.0, requireNotNull(sampler.sample(12_250, 61)).fps, 0.0)
        assertNull(sampler.sample(5000, 80))
        assertEquals(60.0, requireNotNull(sampler.sample(6000, 140)).fps, 0.0)
    }

    @Test fun sameTimestampAndInvalidCountersNeverDivideByZeroOrPoisonTheNextSample() {
        val sampler = GuestFrameRateSampler(windowMillis = 1000)
        assertNull(sampler.sample(0, 0))
        assertNull(sampler.sample(0, 1))
        assertEquals(60.0, requireNotNull(sampler.sample(1000, 60)).fps, 0.0)
        assertNull(sampler.sample(1001, -1))
        assertNull(sampler.sample(-1, 1))
        assertNull(sampler.sample(2000, 0))
        assertEquals(60.0, requireNotNull(sampler.sample(3000, 60)).fps, 0.0)
    }
}
