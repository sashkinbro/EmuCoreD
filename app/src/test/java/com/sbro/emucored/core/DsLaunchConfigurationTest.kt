package com.sbro.emucored.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DsLaunchConfigurationTest {
    @Test
    fun softwareRequestSurvivesTheDsLaunchBoundary() {
        val config = dsLaunchConfiguration(renderer = 13, renderScale = 2)
        assertEquals(13, config.renderer)
        assertEquals(2, config.renderScale)
    }

    @Test
    fun vulkanRequestAndScaleReachTheDsLaunchBoundary() {
        val config = dsLaunchConfiguration(renderer = 14, renderScale = 8)
        assertEquals(14, config.renderer)
        assertEquals(8, config.renderScale)
    }

    @Test
    fun unsupportedRendererUsesDsSoftwareAndScaleIsBounded() {
        assertEquals(13, dsLaunchConfiguration(renderer = 12, renderScale = 0).renderer)
        assertEquals(1, dsLaunchConfiguration(renderer = 13, renderScale = 0).renderScale)
        assertEquals(8, dsLaunchConfiguration(renderer = 14, renderScale = 12).renderScale)
    }
}
