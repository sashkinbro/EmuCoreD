package com.sbro.emucored.ui.cheats

import org.junit.Assert.*
import org.junit.Test

class CheatSelectionGuardTest {
    @Test fun lateIdentityAndInstallResultsCannotReplaceTheNewGamesConfig() {
        val guard = CheatSelectionGuard()
        val first = guard.select("/games/first.nds")
        val second = guard.select("/games/second.nds")
        var displayed = "second"
        assertFalse(guard.publish(first) { displayed = "first" })
        assertTrue(guard.publish(second) { displayed = "second updated" })
        assertEquals("second updated", displayed)
    }

    @Test fun leavingAndReturningToSameGameDoesNotReviveItsOldRequest() {
        val guard = CheatSelectionGuard()
        val old = guard.select("same")
        guard.select("other")
        val fresh = guard.select("same")
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(fresh))
        assertEquals("same", old.path)
    }

    @Test fun selectingWithoutResolvedIdentityInvalidatesPreviousActionsImmediately() {
        val guard = CheatSelectionGuard()
        val old = guard.select("old")
        guard.select("new")
        assertFalse(guard.publish(old) { fail("Stale selection published") })
    }
}
