package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoordinatorAdmissionTest {
    private val identity = WorkerIdentityV1(41, 2uL, 1, 9)
    private val network = SearchNetworkV1(7, 3)

    @Test
    fun retirementWaitCompletesOnlyAfterUnbindAndWorkerDeath(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        admission.stop()
        val returned = CountDownLatch(1)
        val retired = AtomicBoolean(false)
        val waiter = Thread { retired.set(admission.awaitRetirement(TimeUnit.SECONDS.toNanos(2))); returned.countDown() }
        waiter.start()
        try {
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            assertTrue(lease.anchorHeldForWorker())
            remote.die()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(retired.get())
            assertFalse(lease.anchorHeldForWorker())
        } finally { remote.die(); waiter.join(3000) }
    }

    @Test
    fun retirementWaitRejectsQuarantinedWorker(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        remote.die()
        assertEquals(CoordinatorState.QUARANTINED, admission.state())
        assertFalse(admission.awaitRetirement(TimeUnit.SECONDS.toNanos(1)))
    }

    @Test
    fun confirmsOnlyWithLinkedDeathAndHeldAnchorThenWaitsForUnbindAndDeath(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { it == network })
        assertTrue(admission.connect(remote))
        assertEquals(listOf("link", "admit", "confirm"), remote.events)
        assertEquals(identity, remote.admittedIdentity)
        assertEquals(network, remote.admittedNetwork)
        assertEquals(CoordinatorState.CONFIRMED, admission.state())
        assertEquals(1, lease.duplicateClosed.get())
        assertTrue(lease.anchorHeldForWorker())
        admission.stop()
        assertEquals(CoordinatorState.STOPPING, admission.state())
        assertEquals(1, remote.unbinds.get())
        assertEquals(0, lease.deaths.get())
        remote.die()
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        assertEquals(1, lease.deaths.get())
        admission.stop()
        assertEquals(1, remote.unbinds.get())
        val replacement = FakeRemote(lease)
        assertFalse(admission.connect(replacement))
        assertTrue(replacement.events.isEmpty())
    }

    @Test
    fun deathWhileAdmissionIsBlockedDoesNotWaitForBinderAndQuarantines(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, blockAdmit = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.entered.await(1, TimeUnit.SECONDS))
            val returned = CountDownLatch(1)
            val death = Thread { remote.die(); returned.countDown() }
            death.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
            assertEquals(1, remote.unbinds.get())
            assertEquals(0, lease.deaths.get())
        } finally {
            remote.release.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertFalse(remote.events.contains("confirm"))
        assertEquals(0, lease.deaths.get())
        assertTrue(lease.quarantined.get())
    }

    @Test
    fun deathDoesNotWaitForInFlightLeaseDuplication(): Unit {
        val lease = FakeLease(blockDuplicate = true)
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(lease.duplicateEntered.await(1, TimeUnit.SECONDS))
            val returned = CountDownLatch(1)
            val death = Thread { remote.die(); returned.countDown() }
            death.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
        } finally {
            lease.duplicateRelease.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(CoordinatorState.QUARANTINED, admission.state())
        assertEquals(1, lease.duplicateClosed.get())
        assertEquals(0, lease.deaths.get())
        assertFalse(remote.events.contains("admit"))
    }

    @Test
    fun stopBeforeDuplicateKeepsRealLeaseReservedUntilUnbindDeathAndDispatchEnd(): Unit {
        val realLease = SearchOwnerLease(12345)
        assertTrue(realLease.reserveWorker())
        val lease = FakeLease(realLease = realLease)
        val remote = FakeRemote(lease, blockLink = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.linkEntered.await(1, TimeUnit.SECONDS))
            admission.stop()
            assertCloseDenied(realLease)
            remote.die()
            assertCloseDenied(realLease)
            assertEquals(CoordinatorState.STOPPING, admission.state())
        } finally {
            remote.linkRelease.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        assertEquals(1, lease.deaths.get())
        assertFalse(remote.events.contains("admit"))
        assertTrue(realLease.reserveWorker())
    }

    @Test
    fun unreservedOwnerCannotAdmitAWorker(): Unit {
        val lease = FakeLease(reserved = false)
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertFalse(admission.connect(remote))
        assertTrue(lease.quarantined.get())
        assertFalse(remote.events.contains("admit"))
    }

    @Test
    fun requestedStopWaitsForLocalDuplicateAndExactDeath(): Unit {
        val realLease = SearchOwnerLease(12345)
        assertTrue(realLease.reserveWorker())
        val lease = FakeLease(realLease = realLease)
        val remote = FakeRemote(lease, blockAdmit = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.entered.await(1, TimeUnit.SECONDS))
            admission.stop()
            remote.die()
            assertEquals(CoordinatorState.STOPPING, admission.state())
            assertEquals(0, lease.deaths.get())
            assertCloseDenied(realLease)
        } finally {
            remote.release.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        assertEquals(1, lease.duplicateClosed.get())
        assertEquals(1, lease.deaths.get())
        assertTrue(realLease.reserveWorker())
        assertFalse(remote.events.contains("confirm"))
    }

    @Test
    fun deathDoesNotReplaceUnbindConfirmation(): Unit {
        val realLease = SearchOwnerLease(12345)
        assertTrue(realLease.reserveWorker())
        val lease = FakeLease(realLease = realLease)
        val remote = FakeRemote(lease, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertTrue(admission.connect(remote))
        admission.stop()
        remote.die()
        assertEquals(CoordinatorState.STOPPING, admission.state())
        assertEquals(0, lease.deaths.get())
        assertCloseDenied(realLease)
        remote.finishUnbind(true)
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        assertEquals(1, lease.deaths.get())
        assertTrue(realLease.reserveWorker())
    }

    @Test
    fun failedLocalDuplicateCloseQuarantinesEvenIfWorkerDies(): Unit {
        val lease = FakeLease(failClose = true)
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertFalse(admission.connect(remote))
        assertEquals(CoordinatorState.QUARANTINED, admission.state())
        assertFalse(remote.events.contains("confirm"))
        remote.die()
        assertTrue(lease.quarantined.get())
        assertEquals(0, lease.deaths.get())
    }

    @Test
    fun unknownDeliveryOrNetworkChangeCannotConfirmOrRelease(): Unit {
        for (failure in listOf("link", "admit", "confirm", "network", "anchor", "unbind", "schedule")) {
            val lease = FakeLease()
            val current = AtomicBoolean(true)
            val remote = FakeRemote(lease, failure = failure, onAdmit = {
                if (failure == "network") current.set(false)
                if (failure == "anchor") lease.quarantined.set(true)
            })
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { current.get() })
            val connected = admission.connect(remote)
            if (failure == "unbind" || failure == "schedule") {
                assertTrue(connected)
                admission.stop()
            } else {
                assertFalse(connected)
            }
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
            assertTrue(lease.quarantined.get())
            remote.die()
            assertEquals(0, lease.deaths.get())
            if (failure == "link" || failure == "admit" || failure == "network" || failure == "anchor") {
                assertFalse(remote.events.contains("confirm"))
            }
        }
    }

    private class FakeLease(
        private val failClose: Boolean = false,
        private val blockDuplicate: Boolean = false,
        private val reserved: Boolean = true,
        private val realLease: SearchOwnerLease? = null,
    ) : CoordinatorLease {
        val duplicateClosed = AtomicInteger()
        val duplicateEntered = CountDownLatch(1)
        val duplicateRelease = CountDownLatch(1)
        val deaths = AtomicInteger()
        val quarantined = AtomicBoolean()
        private val outstanding = AtomicBoolean(reserved)
        private val duplicateIssued = AtomicBoolean()
        override fun duplicate(): SearchLeaseDuplicateV1? {
            if (quarantined.get() || !outstanding.get() || !duplicateIssued.compareAndSet(false, true)) return null
            duplicateEntered.countDown()
            if (blockDuplicate) duplicateRelease.await()
            return object : SearchLeaseDuplicateV1 {
                override fun close(): Unit {
                    duplicateClosed.incrementAndGet()
                    check(!failClose)
                }
            }
        }
        override fun anchorHeldForWorker(): Boolean = outstanding.get() && !quarantined.get()
        override fun workerDeathConfirmed(): Unit {
            realLease?.workerDeathConfirmed()
            deaths.incrementAndGet()
            outstanding.set(false)
        }
        override fun quarantine(): Unit { realLease?.quarantine(); quarantined.set(true) }
    }

    private class FakeRemote(
        private val lease: FakeLease,
        private val blockAdmit: Boolean = false,
        private val blockLink: Boolean = false,
        private val delayUnbind: Boolean = false,
        private val failure: String = "",
        private val onAdmit: () -> Unit = {},
    ) : CoordinatorRemote {
        val events = mutableListOf<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val linkEntered = CountDownLatch(1)
        val linkRelease = CountDownLatch(1)
        val unbinds = AtomicInteger()
        private val alive = AtomicBoolean(true)
        private var death: (() -> Unit)? = null
        private var unbindCompletion: ((Boolean) -> Unit)? = null
        var admittedIdentity: WorkerIdentityV1? = null
            private set
        var admittedNetwork: SearchNetworkV1? = null
            private set
        override fun linkDeath(onDeath: () -> Unit): Boolean {
            events.add("link")
            death = onDeath
            linkEntered.countDown()
            if (blockLink) linkRelease.await()
            return failure != "link"
        }
        override fun isAlive(): Boolean = alive.get()
        override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean {
            events.add("admit")
            check(lease.anchorHeldForWorker())
            admittedIdentity = identity
            admittedNetwork = network
            entered.countDown()
            if (blockAdmit) release.await()
            onAdmit()
            return failure != "admit"
        }
        override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean {
            events.add("confirm")
            check(lease.anchorHeldForWorker())
            check(lease.duplicateClosed.get() == 1)
            check(identity == admittedIdentity && network == admittedNetwork)
            return failure != "confirm"
        }
        override fun requestUnbind(completed: (Boolean) -> Unit): Boolean {
            unbinds.incrementAndGet()
            if (failure == "schedule") return false
            if (delayUnbind) unbindCompletion = completed else completed(failure != "unbind")
            return true
        }
        fun die(): Unit { alive.set(false); death?.invoke() }
        fun finishUnbind(success: Boolean): Unit { checkNotNull(unbindCompletion)(success) }
    }

    private fun assertCloseDenied(lease: SearchOwnerLease): Unit {
        try {
            lease.close()
            fail("reserved owner lease was closed")
        } catch (e: IllegalStateException) {
            assertEquals("worker_death_unconfirmed", e.message)
        }
    }
}
