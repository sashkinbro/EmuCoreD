package com.sbro.emucored.core

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.GameMenuTabId
import com.sbro.emucored.data.GameMenuSectionId
import org.junit.Assert.*
import org.junit.Test

class PlatformUiCapabilitiesTest {
    @Test fun dsMenusUseDsRenderersAndResolutionLimitBeforeSessionStarts() {
        val capabilities = PlatformUiCapabilities.forGame("/games/Mario.NDS")
        assertEquals(listOf(14, 13), capabilities.rendererIds)
        assertEquals(13, capabilities.normalizeRenderer(13))
        assertEquals(14, capabilities.normalizeRenderer(12))
        assertEquals(8, capabilities.maxUpscaleMultiplier(10))
        assertFalse(capabilities.supportsAzaharOptions)
        assertFalse(capabilities.supportsAnalogControls)
        assertTrue(capabilities.supportsInternalUpscaling(14))
        assertFalse(capabilities.supportsInternalUpscaling(13))
        assertFalse(capabilities.supportsInternalUpscaling(12))
    }

    @Test fun threeDsMenusRetainHardwareRenderersAndCoreOptions() {
        val capabilities = PlatformUiCapabilities.forGame("/games/Mario.3ds")
        assertEquals(listOf(12, 14), capabilities.rendererIds)
        assertEquals(14, capabilities.normalizeRenderer(13))
        assertEquals(12, capabilities.normalizeRenderer(12))
        assertEquals(10, capabilities.maxUpscaleMultiplier(10))
        assertTrue(capabilities.supportsAzaharOptions)
        assertTrue(capabilities.supportsAnalogControls)
    }

    @Test fun cachedPlatformIdentifiesOpaqueDocumentPaths() {
        assertFalse(PlatformUiCapabilities.forGame("content://provider/document/123", GamePlatform.NDS).supportsAzaharOptions)
        assertTrue(PlatformUiCapabilities.forGame(null).supportsAzaharOptions)
    }

    @Test fun allNineCoresExposeTheirImplementedTypedCheatsAndDsStillHidesAzaharAudioCpu() {
        val ds = PlatformUiCapabilities.forGame("content://provider/document/Game%2Ends")
        val threeDs = PlatformUiCapabilities.forGame("/games/Game.3ds")
        for (platform in GamePlatform.entries) {
            assertEquals(platform.name, platform != GamePlatform.N64,
                PlatformUiCapabilities.forGame(null, platform).supportsGameMenuTab(GameMenuTabId.CHEATS))
            assertTrue(platform.name, PlatformUiCapabilities.forGame(null, platform).supportsGameMenuSection(GameMenuSectionId.EMULATION_PERFORMANCE))
        }
        assertTrue(threeDs.supportsGameMenuTab(GameMenuTabId.CHEATS))
        assertTrue(ds.supportsGameMenuTab(GameMenuTabId.SESSION))
        assertTrue(ds.supportsGameMenuTab(GameMenuTabId.CONTROLS))
        assertTrue(ds.supportsGameMenuTab(GameMenuTabId.GRAPHICS))
        assertFalse(ds.supportsGameMenuSection(GameMenuSectionId.EMULATION_CPU))
        assertFalse(ds.supportsGameMenuSection(GameMenuSectionId.EMULATION_AUDIO))
        assertTrue(ds.supportsGameMenuSection(GameMenuSectionId.EMULATION_CHEATS))
        assertTrue(ds.supportsGameMenuSection(GameMenuSectionId.SAVE_STATES))
        assertTrue(threeDs.supportsGameMenuSection(GameMenuSectionId.EMULATION_CPU))
    }
}
