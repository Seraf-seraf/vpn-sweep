package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

internal class PreparedWorkerLeaseDuplicateTest {
    private class Duplicate : SearchLeaseDuplicateV1 {
        val closes = AtomicInteger()
        var fail = false
        override fun close(): Unit {
            closes.incrementAndGet()
            if (fail) throw IOException("synthetic")
        }
    }

    @Test
    fun closeBeforeBindReleasesOnlyTheUnstartedReservation(): Unit {
        val owner = Duplicate()
        val releases = AtomicInteger()
        val quarantines = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(owner, { releases.incrementAndGet() }, { quarantines.incrementAndGet() })
        prepared.close()
        prepared.close()
        assertFalse(prepared.beginBind())
        assertNull(prepared.beginTransfer())
        assertEquals(1, owner.closes.get())
        assertEquals(1, releases.get())
        assertEquals(0, quarantines.get())
    }

    @Test
    fun admittedSenderCopyClosesAfterTransferWithoutWaitingForDeath(): Unit {
        val owner = Duplicate()
        val releases = AtomicInteger()
        val quarantines = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(owner, { releases.incrementAndGet() }, { quarantines.incrementAndGet() })
        assertTrue(prepared.beginBind())
        assertSame(owner, prepared.beginTransfer())
        assertNull(prepared.beginTransfer())
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closer = Thread { started.countDown(); prepared.close(); closed.countDown() }
        closer.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, owner.closes.get())
            prepared.endTransfer()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, owner.closes.get())
            assertEquals(0, releases.get())
            assertEquals(0, quarantines.get())
            assertNull(prepared.beginTransfer())
        } finally { prepared.endTransfer(); closer.join(3000) }
    }

    @Test
    fun failedUnstartedCloseQuarantinesWithoutReleasingOrRetrying(): Unit {
        val owner = Duplicate().apply { fail = true }
        val releases = AtomicInteger()
        val quarantines = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(owner, { releases.incrementAndGet() }, { quarantines.incrementAndGet() })
        repeat(2) {
            try { prepared.close(); fail("expected unknown close") } catch (_: IOException) { }
        }
        assertFalse(prepared.beginBind())
        assertEquals(1, owner.closes.get())
        assertEquals(0, releases.get())
        assertEquals(1, quarantines.get())
    }

    @Test
    fun admissionAndCallerShareOneSenderWhileAnchorWaitsForDeath(): Unit {
        val owner = Duplicate()
        val releases = AtomicInteger()
        val quarantines = AtomicInteger()
        val held = AtomicBoolean(true)
        val prepared = PreparedWorkerLeaseDuplicate(owner, { releases.incrementAndGet() }, { quarantines.incrementAndGet() })
        val lease = object : CoordinatorLease {
            override fun duplicate(): SearchLeaseDuplicateV1 = prepared
            override fun anchorHeldForWorker(): Boolean = held.get()
            override fun workerDeathConfirmed(): Unit { held.set(false) }
            override fun quarantine(): Unit { quarantines.incrementAndGet() }
        }
        val identity = WorkerIdentityV1(41, 2u, 1, 9)
        val network = SearchNetworkV1(7, 3)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        val admitEntered = CountDownLatch(1)
        val admitRelease = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val death = AtomicReference<() -> Unit>()
        val remote = object : CoordinatorRemote {
            override fun linkDeath(onDeath: () -> Unit): Boolean { death.set(onDeath); return true }
            override fun isAlive(): Boolean = true
            override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean {
                assertSame(prepared, duplicate)
                assertSame(owner, prepared.beginTransfer())
                try {
                    admitEntered.countDown()
                    check(admitRelease.await(3, TimeUnit.SECONDS))
                } finally { prepared.endTransfer() }
                return true
            }
            override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = true
            override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { completed(true); return true }
        }
        assertTrue(prepared.beginBind())
        val admitted = AtomicBoolean()
        val connector = Thread { admitted.set(admission.connect(remote)) }
        val closer = Thread { prepared.close(); closeReturned.countDown() }
        connector.start()
        try {
            assertTrue(admitEntered.await(2, TimeUnit.SECONDS))
            closer.start()
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, owner.closes.get())
            admitRelease.countDown()
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
            connector.join(3000)
            assertFalse(connector.isAlive)
            assertTrue(admitted.get())
            assertEquals(1, owner.closes.get())
            assertTrue(held.get())
            admission.stop()
            assertEquals(CoordinatorState.STOPPING, admission.state())
            assertTrue(held.get())
            death.get().invoke()
            assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
            assertFalse(held.get())
            assertEquals(0, releases.get())
            assertEquals(0, quarantines.get())
        } finally {
            admitRelease.countDown()
            connector.join(3000)
            if (closer.state != Thread.State.NEW) closer.join(3000)
        }
    }
}
