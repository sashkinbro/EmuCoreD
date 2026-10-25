package com.sbro.emucored.ui.emulation

import com.sbro.emucored.core.platform.ConsolePlatform
import org.junit.Assert.*
import org.junit.Test

class ActiveCorePerformanceHeaderTest {
    @Test fun eachConsoleShowsItsActualEngineName() {
        val expected = listOf("Azahar", "melonDS", "Dolphin", "Dolphin", "mGBA", "SameBoy", "SameBoy", "Nestopia JG", "bsnes")
        ConsolePlatform.entries.zip(expected).forEach { (console, engine) ->
            assertEquals("EmuCoreD 0.5 | $engine", performanceCoreHeader("0.5", console))
        }
    }

    @Test fun aLiveSessionWinsAndStoppedSessionsNeverKeepAnotherCoreLabel() {
        val sequence = listOf(
            activePerformancePlatform(null, true, false),
            activePerformancePlatform(ConsolePlatform.GBA, false, false),
            activePerformancePlatform(ConsolePlatform.WII, false, false),
            activePerformancePlatform(null, false, true),
            activePerformancePlatform(null, false, false))
        assertEquals(listOf(ConsolePlatform.NDS, ConsolePlatform.GBA, ConsolePlatform.WII, ConsolePlatform.N3DS, null), sequence)
        assertEquals("EmuCoreD 0.5", performanceCoreHeader("0.5", sequence.last()))
        assertEquals(ConsolePlatform.GAMECUBE, activePerformancePlatform(ConsolePlatform.GAMECUBE, false, true))
    }
}
