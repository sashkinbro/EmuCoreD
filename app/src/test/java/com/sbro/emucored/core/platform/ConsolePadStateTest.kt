package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.ConsolePadState
import com.sbro.emucored.core.platform.session.EngineButton
import org.junit.Assert.*
import org.junit.Test

class ConsolePadStateTest {
    @Test fun heldButtonsAreReplayedAfterBootAndUnchangedInputDoesNotFloodTheCore() {
        val input = ConsolePadState()
        val events = mutableListOf<Triple<Int, EngineButton, Boolean>>()
        val emit: (Int, EngineButton, Boolean) -> Unit = { player, button, pressed -> events += Triple(player, button, pressed) }
        input.update(0, 0xFFFF and (1 shl 13).inv(), false, emit)
        assertTrue(events.isEmpty())
        input.replay(emit)
        assertEquals(listOf(Triple(0, EngineButton.A, true)), events)
        input.update(0, 0xFFFF and (1 shl 13).inv(), true, emit)
        assertEquals(1, events.size)
        input.update(0, 0xFFFF, true, emit)
        assertEquals(Triple(0, EngineButton.A, false), events.last())
    }

    @Test fun aNewGuestDoesNotInheritAppliedInputAndPlayersRemainSeparate() {
        val input = ConsolePadState()
        val events = mutableListOf<Triple<Int, EngineButton, Boolean>>()
        val emit: (Int, EngineButton, Boolean) -> Unit = { player, button, pressed -> events += Triple(player, button, pressed) }
        input.update(1, 0xFFFF and (1 shl 3).inv(), true, emit)
        assertEquals(listOf(Triple(1, EngineButton.START, true)), events)
        input.reset()
        events.clear()
        input.replay(emit)
        assertTrue(events.isEmpty())
        input.update(-1, 0, true, emit)
        input.update(4, 0, true, emit)
        assertTrue(events.isEmpty())
    }
}
