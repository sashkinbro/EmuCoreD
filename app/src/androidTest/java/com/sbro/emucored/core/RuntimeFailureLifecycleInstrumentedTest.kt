// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFailureLifecycleInstrumentedTest {
    @Test
    fun nativeFailureIsVisibleThroughBothFrontendBoundariesWithoutClaimingASession() {
        assertFalse(CoreRuntime.hasSession())
        CoreRuntime.reportNativeError("VideoCore", "owned probe: graphics device lost")
        assertEquals("owned probe: graphics device lost", CoreRuntime.failure.value?.detail)
        assertSame(CoreRuntime.failure.value, NativeApp.runtimeFailure.value)
        assertSame(CoreRuntime.failure.value, EmulatorBridge.runtimeFailure.value)
        assertFalse(CoreRuntime.isRunning())
        assertFalse(EmulatorBridge.hasValidVm())
        assertFalse(EmulatorBridge.isVmActive())
    }

    @Test
    fun errorWithNoDetailPreservesItsNameAndNativeExitReplacesItWithTheResult() {
        assertFalse(CoreRuntime.hasSession())
        CoreRuntime.reportNativeError("owned probe: shader failure", "   ")
        assertEquals("owned probe: shader failure", EmulatorBridge.runtimeFailure.value?.detail)
        CoreRuntime.reportNativeExit(7)
        assertEquals("core_exit_7", EmulatorBridge.runtimeFailure.value?.detail)
        assertFalse(CoreRuntime.isRunning())
        assertFalse(CoreRuntime.hasSession())
    }
}
