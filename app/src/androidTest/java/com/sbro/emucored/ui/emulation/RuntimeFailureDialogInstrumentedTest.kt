// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.emulation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.RuntimeFailure
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFailureDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsDiagnosticAndRequiresExplicitExitInsteadOfResumingOnBack() {
        val exits = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                RuntimeFailureDialog(RuntimeFailure("probe: graphics device lost")) { exits.incrementAndGet() }
            }
        }
        compose.onNodeWithTag("runtime-failure-detail")
            .assertIsDisplayed().assertTextEquals("probe: graphics device lost")
        Espresso.pressBack()
        compose.onNodeWithTag("runtime-failure-exit").assertIsDisplayed()
        assertEquals(0, exits.get())
        compose.onNodeWithTag("runtime-failure-exit").performClick()
        assertEquals(1, exits.get())
    }
}
