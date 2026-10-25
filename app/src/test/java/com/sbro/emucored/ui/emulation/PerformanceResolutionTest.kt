package com.sbro.emucored.ui.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceResolutionTest {
    @Test fun resolutionFollowsTheSelectedUpscale() {
        assertEquals("640x480", scaledPerformanceResolution(640, 480, 1f))
        assertEquals("1280x960", scaledPerformanceResolution(640, 480, 2f))
        assertEquals("1920x1440", scaledPerformanceResolution(640, 480, 3f))
        assertEquals("800x480", scaledPerformanceResolution(400, 240, 2f))
        assertEquals("1200x720", scaledPerformanceResolution(400, 240, 3f))
    }

    @Test fun invalidGuestDimensionsStayHidden() {
        assertNull(scaledPerformanceResolution(0, 480, 2f))
        assertNull(scaledPerformanceResolution(640, 0, 2f))
        assertNull(scaledPerformanceResolution(-1, -1, 2f))
    }

    @Test fun fractionalMultipliersUseTheNormalizedValue() {
        assertEquals("1280x960", scaledPerformanceResolution(640, 480, 2.2f))
        assertEquals("640x480", scaledPerformanceResolution(640, 480, 0.5f))
    }
}
