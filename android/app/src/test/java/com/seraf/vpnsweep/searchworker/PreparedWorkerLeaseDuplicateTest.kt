package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import com.seraf.vpnsweep.searchprotocol.WorkerOpenV2
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerCloseV1
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
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
    fun interruptedTransferAndConcurrentCloseWaitersKeepCustodyUntilRealClose(): Unit {
        val owner = Duplicate()
        val quarantines = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(owner, {}, { quarantines.incrementAndGet() })
        assertTrue(prepared.beginBind())
        assertSame(owner, prepared.beginTransfer())
        val ready = CountDownLatch(2)
        val returned = CountDownLatch(2)
        val failures = AtomicInteger()
        val interrupted = AtomicInteger()
        val closers = (1..2).map {
            Thread {
                ready.countDown()
                try {
                    prepared.close()
                    if (Thread.currentThread().isInterrupted) interrupted.incrementAndGet()
                } catch (_: Exception) { failures.incrementAndGet() }
                finally { returned.countDown() }
            }
        }
        closers.forEach { it.start() }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            closers.forEach { assertTrue(waiting(it)) }
            closers.forEach { it.interrupt() }
            assertEquals(0, owner.closes.get())
            prepared.endTransfer()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
            assertEquals(2, interrupted.get())
            assertEquals(1, owner.closes.get())
            assertEquals(0, quarantines.get())
        } finally { prepared.endTransfer(); closers.forEach { it.join(2_000) } }
    }

    @Test
    fun interruptedAdapterCloserKeepsPreparedCustodyUntilAdmissionAndDeathDrain(): Unit {
        val owner = Duplicate()
        val quarantines = AtomicInteger()
        val deaths = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(owner, {}, { quarantines.incrementAndGet() })
        val lease = object : CoordinatorLease {
            override fun duplicate(): SearchLeaseDuplicateV1 = prepared
            override fun anchorHeldForWorker(): Boolean = true
            override fun workerDeathConfirmed(): Unit { deaths.incrementAndGet() }
            override fun quarantine(): Unit { quarantines.incrementAndGet() }
        }
        val identity = WorkerIdentityV1(41, 2u, 1, 9)
        val network = SearchNetworkV1(7, 3)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val transferEntered = CountDownLatch(1)
        val releaseTransfer = CountDownLatch(1)
        val closerInterrupted = CountDownLatch(1)
        val adapterReturned = CountDownLatch(1)
        val death = AtomicReference<() -> Unit>()
        val remote = object : CoordinatorRemote {
            override fun linkDeath(onDeath: () -> Unit): Boolean { death.set(onDeath); return true }
            override fun isAlive(): Boolean = true
            override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean {
                check(prepared.beginTransfer() === owner)
                try { transferEntered.countDown(); check(releaseTransfer.await(2, TimeUnit.SECONDS)) }
                finally { prepared.endTransfer() }
                return true
            }
            override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = true
            override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { completed(true); return true }
        }
        val port = object : DeadlineWorkerPort {
            override fun openUntil(identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
                configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 =
                WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
            override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 = WorkerReplyV1.Quarantined
            override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit = Unit
            override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 = WorkerCloseV1.CLEAN_AND_DEAD
        }
        assertTrue(prepared.beginBind())
        val connector = Thread { admission.connect(remote) }
        val adapter = SearchEngineWorkerAdapter(41, network, 10_000, 1024, { WorkerRoundClient(port, prepared) }, {}, System::nanoTime,
            startOpenCloser = { thread -> thread.start(); check(waiting(thread)); thread.interrupt(); closerInterrupted.countDown() })
        val caller = Thread {
            try { adapter.open(41, 2, 1, 9, byteArrayOf(1), byteArrayOf(2), 10_000, 5_000) }
            finally { adapterReturned.countDown() }
        }
        connector.start()
        try {
            assertTrue(transferEntered.await(2, TimeUnit.SECONDS))
            caller.start()
            assertTrue(closerInterrupted.await(2, TimeUnit.SECONDS))
            admission.stop()
            death.get().invoke()
            assertEquals(CoordinatorState.STOPPING, admission.state())
            assertEquals(0, owner.closes.get())
            assertEquals(0, deaths.get())
            releaseTransfer.countDown()
            assertTrue(adapterReturned.await(2, TimeUnit.SECONDS))
            connector.join(2_000)
            assertFalse(connector.isAlive)
            assertEquals(1, owner.closes.get())
            assertEquals(0, quarantines.get())
            assertEquals(1, deaths.get())
            assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        } finally {
            releaseTransfer.countDown()
            connector.join(2_000)
            if (caller.state != Thread.State.NEW) caller.join(2_000)
        }
    }

    private fun waiting(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.WAITING && thread.isAlive && System.nanoTime() < deadline) Thread.yield()
        return thread.state == Thread.State.WAITING
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
