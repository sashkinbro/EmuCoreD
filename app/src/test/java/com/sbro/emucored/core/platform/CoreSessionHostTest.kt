package com.sbro.emucored.core.platform

import android.view.Surface
import com.sbro.emucored.core.platform.session.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoreSessionHostTest {
    @Test fun replacementWaitsForOldGuestAndRetiredCommandsCannotReachNewGuest() = runBlocking {
        val events = mutableListOf<String>()
        val engines = mutableListOf<Guest>()
        val host = CoreSessionHost { platform -> Guest(platform.core, events).also { engines += it } }
        assertEquals(EngineStartResult.Started, host.start(ConsolePlatform.GBA) { it.boot() })
        val retired = host.session!!
        retired.setButton(0, EngineButton.A, true)
        assertEquals(EngineStartResult.Started, host.start(ConsolePlatform.NES) { it.boot() })
        assertEquals(listOf("MGBA open", "MGBA button", "MGBA stop", "NESTOPIA open"), events)
        assertFalse(retired.isRunning)
        retired.setButton(0, EngineButton.A, false)
        retired.stop()
        assertFalse(retired.saveState(File("retired.state")))
        host.session!!.setButton(0, EngineButton.START, true)
        assertEquals("NESTOPIA button", events.last())
        assertEquals(1, events.count { it == "MGBA stop" })
        host.stop()
        host.stop()
        assertNull(host.session)
        assertEquals(1, events.count { it == "NESTOPIA stop" })
    }

    @Test fun unsupportedPlatformDoesNotStopTheCurrentGuestOrGuessAnotherCore() = runBlocking {
        val guest = Guest(CoreId.MGBA)
        val host = CoreSessionHost { if (it == ConsolePlatform.GBA) guest else null }
        host.start(ConsolePlatform.GBA) { it.boot() }
        val previous = host.session
        assertEquals(EngineStartResult.Failed("core_unavailable"), host.start(ConsolePlatform.N3DS) {
            fail("Unavailable core must not launch"); EngineStartResult.Started
        })
        assertSame(previous, host.session)
        assertTrue(guest.isRunning)
        host.stop()
    }

    @Test fun failedOrCancelledBootIsCleanedBeforeRetry() = runBlocking {
        val engines = mutableListOf<Guest>()
        val host = CoreSessionHost { Guest(it.core).also { guest -> engines += guest } }
        assertEquals(EngineStartResult.Failed("bad_rom"), host.start(ConsolePlatform.SNES) {
            (it as Guest).isRunning = true
            EngineStartResult.Failed("bad_rom")
        })
        assertNull(host.session)
        assertEquals(1, engines.single().stops)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            host.start(ConsolePlatform.GB) {
                (it as Guest).isRunning = true
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertNull(host.session)
        assertEquals(1, engines.last().stops)
        assertEquals(EngineStartResult.Started, host.start(ConsolePlatform.GBC) { it.boot() })
        host.stop()
    }

    @Test fun failedTeardownRetainsOwnershipAndPreventsReplacementBoot() = runBlocking {
        val guest = Guest(CoreId.BSNES).apply { refuseStop = true }
        val host = CoreSessionHost { if (it == ConsolePlatform.SNES) guest else Guest(it.core) }
        host.start(ConsolePlatform.SNES) { it.boot() }
        try {
            host.start(ConsolePlatform.GBA) { fail("Replacement opened over an owned runtime"); EngineStartResult.Started }
            fail("Stop failure must reach the caller")
        } catch (_: IllegalStateException) { }
        assertEquals(ConsolePlatform.SNES, host.session!!.platform)
        assertFalse(host.session!!.isRunning)
        guest.refuseStop = false
        assertEquals(EngineStartResult.Started, host.start(ConsolePlatform.GBA) { it.boot() })
        host.stop()
    }

    @Test fun stopWaitsForAcceptedStateIoAndRejectsQueuedRetiredIo() = runBlocking {
        val guest = Guest(CoreId.MGBA)
        val host = CoreSessionHost { if (it == ConsolePlatform.GBA) guest else Guest(it.core) }
        host.start(ConsolePlatform.GBA) { it.boot() }
        val session = host.session!!
        guest.stateEntered = CompletableDeferred()
        guest.stateRelease = CompletableDeferred()
        val saving = async { session.saveState(File("state")) }
        guest.stateEntered!!.await()
        val replacing = async { host.start(ConsolePlatform.NES) { it.boot() } }
        yield()
        assertFalse(replacing.isCompleted)
        guest.stateRelease!!.complete(Unit)
        assertTrue(saving.await())
        assertEquals(EngineStartResult.Started, replacing.await())
        assertFalse(session.loadState(File("state")))
        assertEquals(1, guest.stateCalls)
        host.stop()
    }

    @Test fun wrongFactoryCoreIsRejectedWithoutClosingTheCurrentGuest() = runBlocking {
        val guest = Guest(CoreId.MGBA)
        val host = CoreSessionHost { guest }
        host.start(ConsolePlatform.GBA) { it.boot() }
        assertEquals(EngineStartResult.Failed("core_platform_mismatch"), host.start(ConsolePlatform.WII) {
            fail("Wrong core must not launch"); EngineStartResult.Started
        })
        assertTrue(guest.isRunning)
        host.stop()
    }

    @Test fun aFailedBootTeardownIsNotRepeatedInsideTheSameLaunchAttempt() = runBlocking {
        val guest = Guest(CoreId.MGBA).apply { refuseStop = true }
        val host = CoreSessionHost { guest }
        try {
            host.start(ConsolePlatform.GBA) { EngineStartResult.Failed("bad_rom") }
            fail("Teardown failure must retain ownership")
        } catch (_: IllegalStateException) { }
        assertEquals("One failed boot attempted teardown twice", 1, guest.stopAttempts)
        assertNotNull(host.session)
        guest.refuseStop = false
        host.stop()
    }

    @Test fun stopRequestedDuringBootDoesNotPublishAStartedSession() = runBlocking {
        val guest = Guest(CoreId.MGBA)
        val host = CoreSessionHost { guest }
        assertEquals(EngineStartResult.Failed("core_start_stopped"), host.start(ConsolePlatform.GBA) {
            guest.isRunning = true
            host.requestStop()
            EngineStartResult.Started
        })
        assertNull(host.session)
        assertEquals(1, guest.stops)
    }

    @Test fun cancellationAtTheEndOfBlockingBootStillReleasesTheGuest() = runBlocking {
        val guest = Guest(CoreId.MGBA)
        val host = CoreSessionHost { guest }
        val opening = launch {
            host.start(ConsolePlatform.GBA) {
                guest.isRunning = true
                currentCoroutineContext().cancel()
                EngineStartResult.Started
            }
        }
        opening.join()
        assertTrue(opening.isCancelled)
        assertNull("A cancelled launch published a live guest", host.session)
        assertFalse(guest.isRunning)
        assertEquals(1, guest.stops)
    }

    private suspend fun CoreSessionEngine.boot(): EngineStartResult {
        return (this as Guest).let { it.isRunning = true; it.events += "${it.coreId} open"; EngineStartResult.Started }
    }

    /** Native engines are the external boundary; this guest controls their completion/failure. */
    private class Guest(override val coreId: CoreId, val events: MutableList<String> = mutableListOf()) : CoreSessionEngine {
        override var isRunning = false
        override var isPaused = false
        var stops = 0
        var stopAttempts = 0
        var refuseStop = false
        var stateCalls = 0
        var stateEntered: CompletableDeferred<Unit>? = null
        var stateRelease: CompletableDeferred<Unit>? = null
        override suspend fun start(request: EngineLaunchRequest): EngineStartResult = error("Use explicit controlled boot")
        override suspend fun pause() { isPaused = true }
        override suspend fun resume() { isPaused = false }
        override fun requestStop() = Unit
        override suspend fun stop() {
            stopAttempts++
            check(!refuseStop) { "Native teardown failed" }
            stops++; isRunning = false; events += "$coreId stop"
        }
        override fun attachSurface(surface: Surface?, width: Int, height: Int) = Unit
        override fun setButton(player: Int, button: EngineButton, pressed: Boolean) { events += "$coreId button" }
        override fun setSpeed(multiplier: Float) = true
        override suspend fun saveState(target: File): Boolean {
            stateCalls++; stateEntered?.complete(Unit); stateRelease?.await(); return true
        }
        override suspend fun loadState(source: File): Boolean { stateCalls++; return true }
        override suspend fun applyOption(key: String, value: String) = true
        override fun frameCount() = 1L
    }
}
