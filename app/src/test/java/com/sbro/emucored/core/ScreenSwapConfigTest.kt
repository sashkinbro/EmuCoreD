package com.sbro.emucored.core

import org.junit.Assert.*
import org.junit.Test

class ScreenSwapConfigTest {
    @Test fun liveSessionSwapWinsOverLaunchChoiceWithoutChangingStoredDefault() {
        assertTrue(resolveScreenSwapConfig(false, "true", "false"))
        assertFalse(resolveScreenSwapConfig(true, "false", "true"))
        assertTrue(resolveScreenSwapConfig(true, null, "false"))
        assertFalse(resolveScreenSwapConfig(null, null, "false"))
        assertTrue(resolveScreenSwapConfig(null, null, "true"))
        assertFalse(resolveScreenSwapConfig(false, "invalid", "true"))
    }
}
