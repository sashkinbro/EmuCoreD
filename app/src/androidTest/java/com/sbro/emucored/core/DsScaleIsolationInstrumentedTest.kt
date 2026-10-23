// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e dsRomPath <readable user-owned DS ROM path or persisted content URI>. */
@RunWith(AndroidJUnit4::class)
class DsScaleIsolationInstrumentedTest {
    @Test(timeout = 60_000)
    fun activeDsScaleDoesNotChangeAzaharResolution() = runBlocking {
        val romPath = InstrumentationRegistry.getArguments().getString("dsRomPath").orEmpty()
        assumeTrue("Supply dsRomPath to run the optional real-ROM regression", romPath.isNotBlank())
        assertTrue("DS JNI library must load", EmuCoreDsNative.isLoaded)
        assertFalse("Test must own the only DS session", DsSession.isActive)
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testStorageContext().use { context ->
            try {
                CoreOptionStore.initialize(context)
                CoreOptionStore.set("resolution_factor", "7")
                val identity = File(context.filesDir, "scale-isolation.nds").absolutePath
                assertTrue(
                    "Software start failed",
                    DsSession.start(context, romPath, identity, renderScale = 2, renderer = 13)
                )

                EmulatorBridge.setUpscaleMultiplier(3f)

                assertEquals("DS runtime scale must change", 3, DsSession.currentRenderScale())
                // Reload the isolated store to check persisted data, not only its in-memory cache.
                CoreOptionStore.initialize(context)
                assertEquals("DS scale leaked into Azahar preferences", "7", CoreOptionStore.value("resolution_factor"))
            } finally {
                DsSession.stop()
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
                CoreOptionStore.initialize(targetContext)
            }
        }
    }
}
