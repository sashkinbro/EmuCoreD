package com.sbro.emucored.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N3dsGameCompatibilityTest {
    @Test fun luigiMansionTwoGetsStableTimingDefaults() {
        val bySerial = N3dsGameCompatibility.defaultsFor("CTR-P-AGGP", "Luigi's Mansion 2")
        assertTrue(bySerial.simulateGpuTimings)
        assertEquals(6_500, bySerial.delayGameRenderThreadUs)

        val byTitle = N3dsGameCompatibility.defaultsFor(null, "Luigi's Mansion: Dark Moon")
        assertTrue(byTitle.simulateGpuTimings)
        assertEquals(6_500, byTitle.delayGameRenderThreadUs)
    }

    @Test fun otherGamesResetCompatibilityDefaults() {
        val marioKart = N3dsGameCompatibility.defaultsFor("CTR-P-AMKP", "Mario Kart 7")
        assertFalse(marioKart.simulateGpuTimings)
        assertEquals(0, marioKart.delayGameRenderThreadUs)

        val unknown = N3dsGameCompatibility.defaultsFor(null, null)
        assertFalse(unknown.simulateGpuTimings)
        assertEquals(0, unknown.delayGameRenderThreadUs)
    }
}
