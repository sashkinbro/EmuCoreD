// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.ds.DsSession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSessionLifecycleInstrumentedTest {
    @Test
    fun presentationDimensionsFollowSurfaceReplacementAndAreClearedOnDetach() {
        assertFalse(CoreRuntime.hasSession())
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            CoreRuntime.rememberSurface(surface, 400, 480)
            assertTrue(CoreRuntime.hasAttachedSurface(surface, 400, 480))
            assertArrayEquals(floatArrayOf(0f, 0f, 400f, 480f), CoreRuntime.displayRect(), 0f)
            CoreRuntime.rememberSurface(surface, 800, 480)
            assertFalse(CoreRuntime.hasAttachedSurface(surface, 400, 480))
            assertArrayEquals(floatArrayOf(0f, 0f, 800f, 480f), CoreRuntime.displayRect(), 0f)
            CoreRuntime.detachSurface()
            assertNull(CoreRuntime.displayRect())
            assertFalse(CoreRuntime.hasAttachedSurface(surface, 800, 480))
            // A destroyed/zero-size window must never retain an actionable display rectangle.
            CoreRuntime.rememberSurface(null, 0, 0)
            assertNull(CoreRuntime.displayRect())
        } finally {
            CoreRuntime.detachSurface()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun stoppedRuntimesRejectStateOperationsAndAllowRepeatedLifecycleCleanup() {
        assertFalse(CoreRuntime.hasSession())
        assertFalse(DsSession.isActive)
        repeat(2) {
            CoreRuntime.pause()
            CoreRuntime.resume()
            CoreRuntime.requestShutdown()
            CoreRuntime.shutdown()
            DsSession.pause()
            DsSession.resume()
            DsSession.stop()
            assertFalse(CoreRuntime.saveState(1))
            assertFalse(CoreRuntime.loadState(1))
            assertFalse(EmuCoreNative.saveTemporaryState("/unavailable-checkpoint.cst"))
            assertFalse(EmuCoreNative.loadTemporaryState("/unavailable-checkpoint.cst"))
            assertFalse(DsSession.saveState(1))
            assertFalse(DsSession.loadState(1))
            assertNull(DsSession.statePath(1))
            assertEquals(0L, DsSession.stateLastModified(1))
            assertFalse(CoreRuntime.hasSession())
            assertFalse(DsSession.isActive)
        }
    }

    @Test
    fun rejectedLaunchesNeverCreateASessionOrStateFiles() = runBlocking {
        testStorageContext().use { context ->
            val missing = File(context.cacheDir, "missing-owned-fixture.nds")
            check(!missing.exists())
            assertFalse(EmulatorBridge.startEmulation(""))
            assertFalse(DsSession.start(context, ""))
            assertFalse(DsSession.start(context, "/owned-fixture.3ds"))
            assertFalse(DsSession.start(context, missing.absolutePath))
            assertFalse(DsSession.isActive)
            assertFalse(EmulatorBridge.isVmActive())
            assertFalse(DsSession.saveState(1))
            assertFalse(missing.exists())
        }
    }
}
