package com.sbro.emucored.core.platform

import org.junit.Assert.*
import org.junit.Test

class CoreCapabilitiesTest {
    @Test fun dsKeepsVulkanDefaultAndSoftwareAtNativeInternalResolution() {
        val ds = CoreCapabilities.forPlatform(ConsolePlatform.NDS)
        assertEquals(listOf(14, 13), ds.rendererIds)
        assertEquals(14, ds.defaultRenderer)
        assertEquals(8, ds.maxInternalScale(14))
        assertEquals(1, ds.maxInternalScale(13))
        assertFalse(ds.supportsAzaharOptions)
        assertTrue(ds.supportsScreenSwap)
        assertEquals(2, ds.screenCount)
    }

    @Test fun threeDsRetainsBothHardwareRenderersAndItsOwnOptions() {
        val threeDs = CoreCapabilities.forPlatform(ConsolePlatform.N3DS)
        assertEquals(listOf(12, 14), threeDs.rendererIds)
        assertEquals(14, threeDs.defaultRenderer)
        assertTrue(threeDs.supportsAzaharOptions)
        assertTrue(threeDs.supportsScreenSwap)
    }

    @Test fun unavailableAdaptersNeverInheritAzaharControlsOrClaimRenderingSupport() {
        ConsolePlatform.entries.filterNot { it.isLaunchable }.forEach { platform ->
            val capabilities = CoreCapabilities.forPlatform(platform)
            assertFalse(platform.id, capabilities.supportsAzaharOptions)
            assertFalse(platform.id, capabilities.supportsScreenSwap)
            assertEquals(platform.id, 1, capabilities.screenCount)
            assertTrue(platform.id, capabilities.rendererIds.isEmpty())
            assertNull(platform.id, capabilities.defaultRenderer)
            assertEquals(platform.id, 1, capabilities.maxInternalScale(14))
        }
    }

    @Test fun unsupportedRendererCannotAcquireAnUpscalingCapability() {
        assertEquals(1, CoreCapabilities.forPlatform(ConsolePlatform.NDS).maxInternalScale(12))
        assertEquals(1, CoreCapabilities.forPlatform(ConsolePlatform.N3DS).maxInternalScale(13))
    }
}
