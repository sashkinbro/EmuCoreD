package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.CorePerformanceSampler
import org.junit.Assert.*
import org.junit.Test

class CorePerformanceSamplerTest {
    @Test fun palThroughputUsesTheActualFiftyHertzTarget() {
        val sampler = CorePerformanceSampler()
        assertNull(sampler.sample(0, 0, 50.0, true, false))
        val sample = requireNotNull(sampler.sample(500, 25, 50.0, true, false))
        assertEquals(50.0, sample.fps!!, 0.0001)
        assertEquals(100.0, sample.speedPercent!!, 0.0001)
    }

    @Test fun quarterSecondReadersReusePublicationWithoutClampingSlowOrFastGames() {
        val sampler = CorePerformanceSampler()
        sampler.sample(0, 0, 60.0, true, false)
        val slow = requireNotNull(sampler.sample(500, 27, 60.0, true, false))
        assertEquals(54.0, slow.fps!!, 0.0)
        assertEquals(slow.fps, sampler.sample(750, 60, 60.0, true, false)!!.fps)
        assertEquals(132.0, sampler.sample(1000, 93, 60.0, true, false)!!.fps!!, 0.0)
    }

    @Test fun pauseStopCounterResetAndTargetChangeRequireANewWarmup() {
        val sampler = CorePerformanceSampler()
        sampler.sample(0, 0, 60.0, true, false)
        assertNotNull(sampler.sample(500, 30, 60.0, true, false))
        assertNull(sampler.sample(750, 30, 60.0, true, true))
        assertNull(sampler.sample(1000, 30, 60.0, true, false))
        assertNotNull(sampler.sample(1500, 60, 60.0, true, false))
        assertNull(sampler.sample(1600, 0, 60.0, true, false))
        assertNull(sampler.sample(1700, 5, 50.0, true, false))
        assertNull(sampler.sample(2200, 30, 50.0, false, false))
    }

    @Test fun unknownTargetDoesNotInventSpeedOrNominalSixtyHertz() {
        val sampler = CorePerformanceSampler()
        sampler.sample(0, 0, null, true, false)
        val sample = requireNotNull(sampler.sample(500, 20, null, true, false))
        assertEquals(40.0, sample.fps!!, 0.0)
        assertNull(sample.targetFps)
        assertNull(sample.speedPercent)
    }
}
