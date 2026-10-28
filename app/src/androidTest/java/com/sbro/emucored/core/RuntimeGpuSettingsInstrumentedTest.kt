// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeGpuSettingsInstrumentedTest {
    @Test
    fun frameSkipIsClampedAndPersistsWhenTheOptionStoreIsReloaded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // A unique preference namespace never writes the user's option store.
        val context = testStorageContext()
        CoreOptionStore.initialize(context)
        val previous = CoreOptionStore.value("frame_skip")
        try {
            for ((requested, expected) in listOf(-1 to "0", 2 to "2", 100 to "4")) {
                CoreRuntime.setFrameSkip(requested)
                assertEquals(expected, CoreRuntime.coreOptionValue("frame_skip"))
                CoreOptionStore.initialize(context)
                assertEquals(expected, CoreRuntime.coreOptionValue("frame_skip"))
            }
        } finally {
            if (previous == null) CoreOptionStore.remove("frame_skip")
            else CoreOptionStore.set("frame_skip", previous)
            CoreOptionStore.initialize(instrumentation.targetContext)
            context.close()
        }
    }

    @Test
    fun dsRenderScaleAndRendererRequestsAreNormalizedBeforeASessionExists() {
        check(!DsSession.isActive)
        val previousScale = DsSession.currentRenderScale()
        val previousRenderer = DsSession.currentRenderer()
        try {
            DsSession.setRenderScale(-10)
            assertEquals(1, DsSession.currentRenderScale())
            DsSession.setRenderScale(4)
            assertEquals(4, DsSession.currentRenderScale())
            DsSession.setRenderScale(100)
            assertEquals(8, DsSession.currentRenderScale())
            assertTrue(DsSession.setRenderer(13))
            assertEquals(13, DsSession.currentRenderer())
            assertTrue(DsSession.setRenderer(-100))
            assertEquals(13, DsSession.currentRenderer())
        } finally {
            DsSession.setRenderScale(previousScale)
            DsSession.setRenderer(previousRenderer)
        }
    }
}
