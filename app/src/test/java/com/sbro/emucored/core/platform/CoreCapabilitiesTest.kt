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

    @Test fun packagedAdaptersExposeOnlyTheirOwnVideoAndInputCapabilities() {
        listOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII).forEach { platform ->
            val capabilities = CoreCapabilities.forPlatform(platform)
            assertEquals(listOf(14, 12), capabilities.rendererIds)
            assertEquals(14, capabilities.defaultRenderer)
            assertEquals(4, capabilities.maxInternalScale(14))
            assertTrue(capabilities.supportsAnalogControls)
            assertFalse(capabilities.supportsScreenSwap)
        }
        listOf(ConsolePlatform.GBA, ConsolePlatform.GB, ConsolePlatform.GBC, ConsolePlatform.NES, ConsolePlatform.SNES).forEach { platform ->
            val capabilities = CoreCapabilities.forPlatform(platform)
            assertEquals(listOf(13), capabilities.rendererIds)
            assertFalse(capabilities.supportsAnalogControls)
            assertFalse(capabilities.supportsAzaharOptions)
            assertFalse(capabilities.supportsMappedAction("swap_screens"))
            assertTrue(capabilities.supportsMappedAction("a"))
        }
    }

    @Test fun unsupportedRendererCannotAcquireAnUpscalingCapability() {
        assertEquals(1, CoreCapabilities.forPlatform(ConsolePlatform.NDS).maxInternalScale(12))
        assertEquals(1, CoreCapabilities.forPlatform(ConsolePlatform.N3DS).maxInternalScale(13))
    }

    @Test fun dsMappingOffersPhysicalDsButtonsAndImplementedFrontendShortcuts() {
        val ds = CoreCapabilities.forPlatform(ConsolePlatform.NDS)
        listOf("a", "b", "x", "y", "l", "r", "start", "select", "dpad_up", "quick_save", "quick_load", "swap_screens")
            .forEach { assertTrue(it, ds.supportsMappedAction(it)) }
        listOf("zl", "zr", "home", "rewind", "fast_forward", "right_stick_up", "left_stick_down")
            .forEach { assertFalse(it, ds.supportsMappedAction(it)) }
        assertTrue(CoreCapabilities.forPlatform(ConsolePlatform.GBA).supportsMappedAction("a"))
        assertFalse(CoreCapabilities.forPlatform(ConsolePlatform.GBA).supportsMappedAction("x"))
    }
}
