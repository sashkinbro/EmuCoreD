package com.sbro.emucored.core.platform.session

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/** Leases bind commands to one lifetime of a singleton native runtime. */
internal class NativeSessionOwnership {
    class Lease internal constructor() { @Volatile internal var closing = false }
    private val gate = ReentrantReadWriteLock()
    private var current: Lease? = null

    fun claim(): Lease? = gate.writeLock().withLock {
        if (current != null) null else Lease().also { current = it }
    }

    /** UI input/status never waits behind a slow native stop. */
    fun <T> call(lease: Lease?, unavailable: T, action: () -> T): T {
        if (lease == null || lease.closing) return unavailable
        val read = gate.readLock()
        if (!read.tryLock()) return unavailable
        try {
            return if (current === lease && !lease.closing) action() else unavailable
        } finally { read.unlock() }
    }

    /** Already accepted I/O completes before teardown or a replacement claim. */
    fun release(lease: Lease?, teardown: () -> Unit): Boolean {
        if (lease == null) return false
        lease.closing = true
        return gate.writeLock().withLock {
            if (current !== lease) false else {
                teardown()
                // If teardown throws, retain ownership so another guest cannot
                // start over resources the old runtime might still own.
                current = null
                true
            }
        }
    }
}
