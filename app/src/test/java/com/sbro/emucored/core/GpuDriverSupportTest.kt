package com.sbro.emucored.core

import com.sbro.emucored.core.platform.ConsolePlatform
import org.junit.Assert.*
import org.junit.Test

class GpuDriverSupportTest {
    @Test fun customDriverControlsRequireAnImplementedVulkanBackendAndDeviceSupport() {
        assertTrue(GpuDriverSupport.canUse(ConsolePlatform.N3DS, true, true))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.N3DS, false, true))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.N3DS, true, false))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.N64, true, true))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.GBA, true, true))
        assertTrue(GpuDriverSupport.canUse(ConsolePlatform.NDS, true, true))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.NDS, false, true))
        assertTrue(GpuDriverSupport.canUse(ConsolePlatform.GAMECUBE, true, true))
        assertTrue(GpuDriverSupport.canUse(ConsolePlatform.WII, true, true))
        assertFalse(GpuDriverSupport.canUse(ConsolePlatform.WII, false, true))
    }
}
