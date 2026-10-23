package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.NativeSessionOwnership
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeSessionOwnershipTest {
    @Test(timeout = 5_000) fun oldCommandsCannotReachAReplacementNativeSession() {
        val owners = NativeSessionOwnership()
        val old = requireNotNull(owners.claim())
        assertNull(owners.claim())
        val changes = AtomicInteger()
        assertTrue(owners.call(old, false) { changes.incrementAndGet(); true })
        owners.release(old) { changes.incrementAndGet() }
        val replacement = requireNotNull(owners.claim())
        assertFalse(owners.call(old, false) { fail("Old call reached the new guest"); true })
        owners.release(old) { fail("Old stop closed the new guest") }
        assertTrue(owners.call(replacement, false) { true })
        owners.release(replacement) { changes.incrementAndGet() }
        owners.release(replacement) { fail("Teardown ran twice") }
        assertEquals(3, changes.get())
    }

    @Test(timeout = 5_000) fun shutdownWaitsForAcceptedIoWithoutBlockingUiCommands() {
        val owners = NativeSessionOwnership()
        val lease = requireNotNull(owners.claim())
        val entered = CountDownLatch(1)
        val finishIo = CountDownLatch(1)
        val teardown = CountDownLatch(1)
        val finishTeardown = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(3)
        try {
            val state = threads.submit<Boolean> { owners.call(lease, false) {
                entered.countDown(); check(finishIo.await(2, TimeUnit.SECONDS)); true
            } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = threads.submit { owners.release(lease) {
                teardown.countDown(); check(finishTeardown.await(2, TimeUnit.SECONDS))
            } }
            assertFalse("Stop overtook accepted state I/O", teardown.await(30, TimeUnit.MILLISECONDS))
            finishIo.countDown()
            assertTrue(state.get(2, TimeUnit.SECONDS))
            assertTrue(teardown.await(2, TimeUnit.SECONDS))
            val staleInput = threads.submit<Boolean> { owners.call(lease, false) { fail("Input reached a closing guest"); true } }
            assertFalse("UI command blocked behind native teardown", staleInput.get(250, TimeUnit.MILLISECONDS))
            finishTeardown.countDown()
            stop.get(2, TimeUnit.SECONDS)
            val replacement = requireNotNull(owners.claim())
            owners.release(replacement) {}
        } finally {
            finishIo.countDown(); finishTeardown.countDown(); threads.shutdownNow()
        }
    }
}
