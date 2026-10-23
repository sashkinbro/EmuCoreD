package com.sbro.emucored.ui.emulation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveStatePreviewRequestsTest {
    @Test fun missingScreenDoesNotDelayExit() = runBlocking {
        assertFalse(SaveStatePreviewRequests(10).request("own-slot", true, true))
    }

    @Test fun exitAwaitsCaptureCompletionWithTheOriginalSlotIdentity() = runBlocking {
        val requests = SaveStatePreviewRequests(1_000)
        var finished = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.requests.collect {
                assertEquals("original-game/slot0", it.savePath)
                assertTrue(it.dualScreenPortrait)
                delay(20)
                finished = true
                it.complete(true)
            }
        }
        try {
            assertTrue(requests.request("original-game/slot0", true, true))
            assertTrue(finished)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun lostCollectorTimesOutAndInvalidatesQueuedCapture() = runBlocking {
        val requests = SaveStatePreviewRequests(20)
        var received: SaveStatePreviewRequest? = null
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.requests.collect { received = it }
        }
        try {
            assertFalse(requests.request("old-game/slot0", false, true))
            assertNotNull(received)
            assertFalse(requireNotNull(received).isPending)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun exitWaitsForSpaceWhenAnOrdinaryPreviewIsAlreadyQueued() = runBlocking {
        val requests = SaveStatePreviewRequests(1_000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.requests.collect {
                if (it.savePath == "first") {
                    entered.complete(Unit)
                    release.await()
                }
                it.complete(true)
            }
        }
        try {
            assertTrue(requests.request("first", false, false))
            entered.await()
            assertTrue(requests.request("queued", false, false))
            val exit = async { requests.request("exit-slot", false, true) }
            delay(20)
            assertFalse("A full queue skipped the exit capture", exit.isCompleted)
            release.complete(Unit)
            assertTrue(exit.await())
        } finally { collector.cancelAndJoin() }
    }
}
