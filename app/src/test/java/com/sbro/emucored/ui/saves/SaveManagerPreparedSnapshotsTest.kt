package com.sbro.emucored.ui.saves

import org.junit.Assert.*
import org.junit.Test

class SaveManagerPreparedSnapshotsTest {
    @Test fun firstPaintConsumesOnlyTheRequestedContextAndOnlyOnce() {
        val cache = SaveManagerPreparedSnapshots<String>(2)
        val a = cache.begin("context/gameA")
        cache.publish("context/gameA", a, "complete A")
        assertNull(cache.take("context/gameB"))
        assertEquals("complete A", cache.take("context/gameA"))
        assertNull(cache.take("context/gameA"))
    }

    @Test fun newerScanWinsEvenWhenOldPreviewLoadingFinishesLast() {
        val cache = SaveManagerPreparedSnapshots<String>(2)
        val old = cache.begin("game")
        val fresh = cache.begin("game")
        assertTrue(cache.publish("game", fresh, "new slots"))
        assertFalse(cache.publish("game", old, "deleted slot"))
        assertEquals("new slots", cache.take("game"))
    }

    @Test fun mutationsInvalidateInflightAndReadySnapshots() {
        val cache = SaveManagerPreparedSnapshots<String>(2)
        val request = cache.begin("game")
        cache.publish("game", request, "before delete")
        cache.clear()
        assertNull(cache.take("game"))
        assertFalse(cache.publish("game", request, "before delete"))
    }

    @Test fun preparedDataAndRequestTrackingStayBounded() {
        val cache = SaveManagerPreparedSnapshots<Int>(2)
        repeat(20) { index -> cache.publish("$index", cache.begin("$index"), index) }
        assertNull(cache.take("0"))
        assertEquals(18, cache.take("18"))
        assertEquals(19, cache.take("19"))
    }

    @Test fun abandonedNavigationCannotReuseAnExpiredSnapshot() {
        var now = 0L
        val cache = SaveManagerPreparedSnapshots<String>(clockMs = { now }, maximumAgeMs = 100)
        cache.publish("game", cache.begin("game"), "old slots")
        now = 101
        assertNull(cache.take("game"))
    }
}
