package com.seraf.vpnsweep.searchworker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SearchOwnerLeaseTest {
    private fun nowMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    @Test fun workerRetirementPrecedesAnchorCloseAndBlocksNewRound(): Unit {
        val admission = SearchOwnerAdmission()
        val token = checkNotNull(admission.reserve())
        val closes = AtomicInteger()
        val lease = SearchOwnerLease(42, admission, token, { closes.incrementAndGet(); true }, ::nowMillis)
        assertTrue(lease.reserveWorker())
        val retired = AtomicBoolean()
        val waiter = Thread { retired.set(lease.retireWorkerUntil(nowMillis() + 2_000)) }
        waiter.start()
        try {
            // A held reservation is retired only by the coordinator's confirmed death/unbind path.
            assertEquals(0, closes.get())
            assertNull(admission.reserve())
            lease.workerDeathConfirmed()
            waiter.join(2_000)
            assertTrue(retired.get())
            assertFalse(lease.reserveWorker())
            assertTrue(lease.releaseUntil(nowMillis() + 2_000))
            assertEquals(1, closes.get())
            assertNull(admission.reserve())
            assertTrue(lease.acceptRelease())
            assertNotNull(admission.reserve())
        } finally { waiter.join(2_000) }
    }

    @Test fun lateCloseOrRevokePermanentlyBlocksProcessAdmission(): Unit {
        val admission = SearchOwnerAdmission()
        val token = checkNotNull(admission.reserve())
        val entered = CountDownLatch(1)
        val continueClose = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val lease = SearchOwnerLease(42, admission, token, {
            entered.countDown()
            check(continueClose.await(2, TimeUnit.SECONDS))
            true
        }, ::nowMillis)
        val closer = Thread { result.set(lease.releaseUntil(nowMillis() + 2_000)) }
        closer.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(admission.reserve())
            val returned = CountDownLatch(1)
            Thread { lease.quarantine(); returned.countDown() }.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
        } finally {
            continueClose.countDown()
            closer.join(2_000)
        }
        assertFalse(result.get())
        assertTrue(admission.isQuarantined())
        assertNull(admission.reserve())
    }

    @Test fun expiredWorkerRetirementNeverClosesAnchor(): Unit {
        val admission = SearchOwnerAdmission()
        val token = checkNotNull(admission.reserve())
        val closes = AtomicInteger()
        val lease = SearchOwnerLease(42, admission, token, { closes.incrementAndGet(); true }, { 100 })
        assertTrue(lease.reserveWorker())
        assertFalse(lease.releaseUntil(100))
        assertEquals(0, closes.get())
        assertTrue(admission.isQuarantined())
    }

    @Test fun closeAtExactDeadlineCannotReleaseProcessAdmission(): Unit {
        val admission = SearchOwnerAdmission()
        val token = checkNotNull(admission.reserve())
        val time = AtomicInteger(499)
        val lease = SearchOwnerLease(42, admission, token, { time.set(500); true }, { time.get().toLong() })
        assertFalse(lease.releaseUntil(500))
        assertTrue(admission.isQuarantined())
        assertNull(admission.reserve())
    }
}
