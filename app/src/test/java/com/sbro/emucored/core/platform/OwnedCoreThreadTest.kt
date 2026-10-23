package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.OwnedCoreThread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OwnedCoreThreadTest {
    @Test(timeout = 10_000) fun immediateCancellationStillClosesEveryOpenedNativeSession() = runBlocking {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val worker = OwnedCoreThread("cancel-before-frame", { true }, { 60.0 }, { closed.incrementAndGet() })
        try {
            repeat(128) {
                assertTrue(worker.start({ opened.incrementAndGet(); true }, true))
                worker.requestStop()
                worker.stop()
                assertEquals("Cancellation skipped teardown on launch $it", opened.get(), closed.get())
            }
        } finally { worker.stop() }
    }

    @Test(timeout = 5_000) fun coreCallsPauseStateAndTeardownStayOnTheOpeningThread() = runBlocking {
        val threads = CopyOnWriteArrayList<Long>()
        val frames = AtomicInteger()
        val closes = AtomicInteger()
        fun record() { threads += Thread.currentThread().id }
        val worker = OwnedCoreThread("core-test", { record(); frames.incrementAndGet(); true }, { 120.0 }, { record(); closes.incrementAndGet() })
        try {
            assertTrue(worker.start({ record(); true }, false))
            withTimeout(1_000) { while (frames.get() < 3) delay(2) }
            assertFalse("A second open must not replace the live guest", worker.start({ fail("Second opener ran"); true }, false))
            worker.pause { record() }
            val paused = frames.get()
            assertEquals(7, worker.call(-1) { record(); 7 })
            delay(30)
            assertEquals("Pause advanced guest frames", paused, frames.get())
            worker.resume { record() }
            withTimeout(1_000) { while (frames.get() == paused) delay(2) }
            worker.stop()
            assertEquals(1, closes.get())
            assertEquals(1, threads.toSet().size)
            assertNotEquals(Thread.currentThread().id, threads.first())
            assertEquals(-1, worker.call(-1) { fail("A stale command reached the old core"); 7 })
            worker.stop()
            assertEquals(1, closes.get())
        } finally { worker.stop() }
    }

    @Test(timeout = 5_000) fun failedOpenCleansPartialResourcesAndCanBeRetried() = runBlocking {
        val frames = AtomicInteger()
        val closes = AtomicInteger()
        val worker = OwnedCoreThread("failed-open", { frames.incrementAndGet(); true }, { 60.0 }, { closes.incrementAndGet() })
        try {
            assertFalse(worker.start({ false }, false))
            assertFalse(worker.isRunning)
            assertEquals(0, frames.get())
            assertEquals(1, closes.get())
            assertTrue(worker.start({ true }, true))
            delay(20)
            assertEquals(0, frames.get())
            worker.stop()
            assertEquals(2, closes.get())
        } finally { worker.stop() }
    }
}
