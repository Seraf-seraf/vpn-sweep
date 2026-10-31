package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

internal class OwnerCurrentCheckTest {
    private val identity = WorkerIdentityV1(7, 8u, 2, 9)
    private val network = SearchNetworkV1(11, 12)

    @Test
    fun checksUidPidIdentityLeaseAndNetworkBeforeAndAfter(): Unit {
        val trace = mutableListOf<String>()
        val check = OwnerCurrentCheck(100, 200, identity, network,
            { trace.add("pid"); 300 }, { trace.add("admitted"); true },
            { trace.add("lease"); true }, { trace.add("network"); it == network })
        assertTrue(check.permits(100, 300, identity, network))
        assertEquals(listOf("pid", "admitted", "lease", "network", "lease", "admitted", "pid"), trace)
    }

    @Test
    fun rejectsWrongCallerOrGenerationBeforeNetworkCheck(): Unit {
        val calls = AtomicInteger()
        val check = OwnerCurrentCheck(100, 200, identity, network, { 300 }, { true }, { true }, { calls.incrementAndGet(); true })
        assertFalse(check.permits(101, 300, identity, network))
        assertFalse(check.permits(100, 200, identity, network))
        assertFalse(check.permits(100, 0, identity, network))
        assertFalse(check.permits(100, 301, identity, network))
        assertFalse(check.permits(100, 300, identity.copy(workerGeneration = 10), network))
        assertFalse(check.permits(100, 300, identity, network.copy(version = 13)))
        assertEquals(0, calls.get())
    }

    @Test
    fun lossDuringBlockingNetworkCheckIsRejectedWithoutHoldingLock(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val admitted = AtomicBoolean(true)
        val result = AtomicReference<Boolean>()
        val check = OwnerCurrentCheck(100, 200, identity, network, { 300 }, admitted::get, { true }, {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            true
        })
        val worker = Thread { result.set(check.permits(100, 300, identity, network)) }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            admitted.set(false)
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(false, result.get())
        } finally { release.countDown() }
    }

    @Test
    fun rejectsLeaseOrPidChangeAndUnknownCheck(): Unit {
        val leaseCalls = AtomicInteger()
        val lostLease = OwnerCurrentCheck(100, 200, identity, network, { 300 }, { true }, { leaseCalls.incrementAndGet() == 1 }, { true })
        assertFalse(lostLease.permits(100, 300, identity, network))
        val pidCalls = AtomicInteger()
        val changedPid = OwnerCurrentCheck(100, 200, identity, network, { if (pidCalls.incrementAndGet() == 1) 300 else 301 }, { true }, { true }, { true })
        assertFalse(changedPid.permits(100, 300, identity, network))
        val broken = OwnerCurrentCheck(100, 200, identity, network, { 300 }, { true }, { true }, { throw IllegalStateException("synthetic") })
        assertFalse(broken.permits(100, 300, identity, network))
    }

    @Test
    fun normalStopWinsAgainstCurrentBlockedInNetworkCheck(): Unit {
        val lock = Any()
        var stopped = false
        val lease = GateLease()
        val remote = GateRemote()
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val check = OwnerCurrentCheck(100, 200, identity, network,
            { synchronized(lock) { if (stopped) -1 else 300 } },
            { admission.state() == CoordinatorState.CONFIRMED }, lease::anchorHeldForWorker, {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                true
            })
        val current = Thread {
            if (!check.permits(100, 300, identity, network)) {
                rejectCurrentIfActive(lock, { !stopped }, { stopped = true }) { admission.disconnected() }
            }
            returned.countDown()
        }
        current.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            synchronized(lock) { stopped = true }
            admission.stop()
            assertEquals(CoordinatorState.STOPPING, admission.state())
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(CoordinatorState.STOPPING, admission.state())
            remote.die()
            assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
            assertFalse(lease.held.get())
            assertFalse(lease.quarantined.get())
        } finally { release.countDown(); current.join(3000) }
    }

    @Test
    fun rejectedCurrentWhileActiveQuarantines(): Unit {
        val lock = Any()
        var stopped = false
        val lease = GateLease()
        val remote = GateRemote()
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        val check = OwnerCurrentCheck(100, 200, identity, network, { 300 },
            { admission.state() == CoordinatorState.CONFIRMED }, lease::anchorHeldForWorker, { true })
        assertFalse(check.permits(100, 300, identity.copy(workerGeneration = 10), network))
        rejectCurrentIfActive(lock, { !stopped }, { stopped = true; lease.quarantine() }) { admission.disconnected() }
        assertTrue(stopped)
        assertEquals(CoordinatorState.QUARANTINED, admission.state())
        assertTrue(lease.quarantined.get())
    }

    @Test
    fun activeCurrentFailurePreventsRetirementBeforeQuarantineCallback(): Unit {
        val lock = Any()
        var stopped = false
        val lease = GateLease()
        val remote = GateRemote()
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        val chosen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val current = Thread {
            rejectCurrentIfActive(lock, { !stopped }, { stopped = true; lease.quarantine() }) {
                chosen.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                admission.disconnected()
            }
            returned.countDown()
        }
        current.start()
        try {
            assertTrue(chosen.await(1, TimeUnit.SECONDS))
            synchronized(lock) { stopped = true }
            admission.stop()
            remote.die()
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
            assertTrue(lease.held.get())
            assertTrue(lease.quarantined.get())
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
            assertTrue(lease.held.get())
        } finally { release.countDown(); current.join(3000) }
    }

    private class GateLease : CoordinatorLease {
        val held = AtomicBoolean(true)
        val quarantined = AtomicBoolean(false)
        override fun duplicate(): SearchLeaseDuplicateV1 = object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }
        override fun anchorHeldForWorker(): Boolean = held.get()
        override fun workerDeathConfirmed(): Unit { check(!quarantined.get()); held.set(false) }
        override fun quarantine(): Unit { quarantined.set(true) }
    }

    private class GateRemote : CoordinatorRemote {
        private var onDeath: (() -> Unit)? = null
        override fun linkDeath(onDeath: () -> Unit): Boolean { this.onDeath = onDeath; return true }
        override fun isAlive(): Boolean = true
        override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean = true
        override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = true
        override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { completed(true); return true }
        fun die(): Unit { onDeath?.invoke() }
    }
}
