package com.sbro.emucored.ui.emulation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class DsTouchscreenMappingTest {
    @Test fun touchFollowsSwappedPhysicalScreenInBothRendererViewports() {
        for (height in listOf(384, 386)) {
            val size = IntSize(256, height)
            val upper = Offset(128f, height / 4f)
            val lower = Offset(128f, height * 3f / 4f)
            assertEquals(Offset(128f, 96f), mapDsTouch(upper, size, false, height))
            assertNull(mapDsTouch(lower, size, false, height))
            assertEquals(Offset(128f, 96f), mapDsTouch(lower, size, true, height))
            assertNull(mapDsTouch(upper, size, true, height))
        }
    }

    @Test fun letterboxMarginsStayUntouchableAfterSwapAndResize() {
        assertNull(mapDsTouch(Offset(0f, 200f), IntSize(600, 384), true, 384))
        assertEquals(Offset(128f, 96f), mapDsTouch(Offset(300f, 288f), IntSize(600, 384), true, 384))
        assertNull(mapDsTouch(Offset(128f, 100f), IntSize(256, 800), false, 384))
        assertEquals(Offset(128f, 96f), mapDsTouch(Offset(128f, 304f), IntSize(256, 800), false, 384))
    }
}
