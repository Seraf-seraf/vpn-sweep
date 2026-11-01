package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoordinatorAdmissionTest {
    private val identity = WorkerIdentityV1(41, 2uL, 1, 9)
    private val network = SearchNetworkV1(7, 3)

    @Test
    fun supervisorStopBeforeConnectionWaitsForUnbindDuplicateCloseAndExactProcessDeath(): Unit {
        for (order in listOf(listOf("unbind", "duplicate", "death"), listOf("death", "unbind", "duplicate"), listOf("duplicate", "death", "unbind"))) {
            val lease = FakeLease()
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
            val attempt = Any()
            assertTrue(admission.beginSupervisorStop(attempt))
            assertEquals(CoordinatorState.STOPPING, admission.state())
            assertFalse(lease.quarantined.get())
            val delivered = CountDownLatch(1)
            val retirement = AtomicReference<CoordinatorRetirementProof?>()
            assertTrue(admission.observeRetirement { retirement.set(it); delivered.countDown() })
            assertFalse(admission.supervisorDied(supervisorProof(Any())))
            for ((index, action) in order.withIndex()) {
                when (action) {
                    "unbind" -> admission.supervisedUnbindCompleted(true)
                    "duplicate" -> admission.supervisedDuplicateClosed(true)
                    else -> assertTrue(admission.supervisorDied(supervisorProof(attempt)))
                }
                if (index < order.lastIndex) {
                    assertEquals(1L, delivered.count)
                    assertEquals(0, lease.deaths.get())
                }
            }
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertTrue(admission.ownsRetirement(retirement.get()!!, identity))
            assertEquals(1, lease.deaths.get())
            assertFalse(lease.quarantined.get())
        }
    }

    @Test
    fun supervisorStopBeforeConnectionKeepsUnknownForFailedUnbindOrDuplicateClose(): Unit {
        for (failed in listOf("unbind", "duplicate")) {
            val lease = FakeLease()
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
            val attempt = Any()
            assertTrue(admission.beginSupervisorStop(attempt))
            val delivered = CountDownLatch(1)
            val retirement = AtomicReference<CoordinatorRetirementProof?>()
            assertTrue(admission.observeRetirement { retirement.set(it); delivered.countDown() })
            admission.supervisedUnbindCompleted(failed != "unbind")
            admission.supervisedDuplicateClosed(failed != "duplicate")
            assertFalse(admission.supervisorDied(supervisorProof(attempt)))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertNull(retirement.get())
            assertTrue(lease.quarantined.get())
            assertEquals(0, lease.deaths.get())
        }
    }

    @Test
    fun sealedRecoveryLeaseCannotStartOrReleaseAnotherWorker(): Unit {
        val processAdmission = SearchOwnerAdmission()
        val token = checkNotNull(processAdmission.reserve())
        val lease = SearchOwnerLease(12345, processAdmission, token, closeFd = { true }, nowMillis = { 1 })
        assertTrue(lease.reserveWorker())
        assertTrue(lease.sealForRecovery())
        assertFalse(lease.anchorHeldForWorker())
        assertFalse(lease.reserveWorker())
        assertFalse(lease.resumeWorkers())
        lease.workerDeathConfirmed()
        assertFalse(lease.resumeWorkers())
        assertFalse(lease.releaseUntil(100))
        assertFalse(processAdmission.isQuarantined())
    }

    @Test
    fun supervisedStopRacingAnchorChecksAbortsWithoutPoisoningRealLease(): Unit {
        for (pauseAt in listOf(1, 2)) {
            val processAdmission = SearchOwnerAdmission()
            val token = checkNotNull(processAdmission.reserve())
            val realLease = SearchOwnerLease(12345, processAdmission, token, closeFd = { true }, nowMillis = { 1 })
            assertTrue(realLease.reserveWorker())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val checks = AtomicInteger()
            val closes = AtomicInteger()
            val deaths = AtomicInteger()
            val lease = object : CoordinatorLease {
                override fun duplicate(): SearchLeaseDuplicateV1 = object : SearchLeaseDuplicateV1 {
                    override fun close(): Unit { closes.incrementAndGet() }
                }
                override fun anchorHeldForWorker(): Boolean = observeAnchor() == WorkerAnchorObservation.HELD
                override fun observeAnchor(): WorkerAnchorObservation {
                    if (checks.incrementAndGet() != pauseAt) return WorkerAnchorObservation.HELD
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    return realLease.observeAnchor()
                }
                override fun sealForRecovery(): Boolean = realLease.sealForRecovery()
                override fun workerDeathConfirmed(): Unit { realLease.workerDeathConfirmed(); deaths.incrementAndGet() }
                override fun quarantine(): Unit = realLease.quarantine()
            }
            val remote = object : CoordinatorRemote {
                override fun linkDeath(onDeath: () -> Unit): Boolean = true
                override fun isAlive(): Boolean = true
                override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean = true
                override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = true
                override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { completed(true); return true }
            }
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
            val caller = Thread { admission.connect(remote) }
            caller.start()
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val attempt = Any()
                assertTrue(admission.beginSupervisorStop(attempt))
                admission.supervisedDuplicateClosed(true)
                assertTrue(admission.supervisorDied(supervisorProof(attempt)))
                assertEquals(0, deaths.get())
                release.countDown()
                caller.join(2_000)
                assertFalse(caller.isAlive)
                assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
                assertEquals(1, deaths.get())
                assertEquals(if (pauseAt == 1) 0 else 1, closes.get())
                assertFalse(processAdmission.isQuarantined())
                assertFalse(realLease.resumeWorkers())
            } finally { release.countDown(); caller.join(2_000) }
        }
    }

    @Test
    fun independentUnknownObservedBeforeSupervisorStopCannotBecomeFence(): Unit {
        for (failure in listOf("anchor", "network", "network_exception", "duplicate", "link", "alive", "admit", "admit_exception", "confirm")) {
            val processAdmission = SearchOwnerAdmission()
            val token = checkNotNull(processAdmission.reserve())
            val realLease = SearchOwnerLease(12345, processAdmission, token, closeFd = { true }, nowMillis = { 1 })
            assertTrue(realLease.reserveWorker())
            val observed = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val deaths = AtomicInteger()
            fun pause(): Unit { observed.countDown(); check(resume.await(2, TimeUnit.SECONDS)) }
            val lease = object : CoordinatorLease {
                override fun duplicate(): SearchLeaseDuplicateV1 = object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }
                override fun observeDuplicate(): WorkerDuplicateObservation {
                    if (failure == "duplicate") { pause(); return WorkerDuplicateObservation.Unknown }
                    return WorkerDuplicateObservation.Available(duplicate())
                }
                override fun anchorHeldForWorker(): Boolean = true
                override fun observeAnchor(): WorkerAnchorObservation {
                    if (failure == "anchor") { pause(); return WorkerAnchorObservation.UNKNOWN }
                    return WorkerAnchorObservation.HELD
                }
                override fun sealForRecovery(): Boolean = realLease.sealForRecovery()
                override fun workerDeathConfirmed(): Unit { realLease.workerDeathConfirmed(); deaths.incrementAndGet() }
                override fun quarantine(): Unit = realLease.quarantine()
            }
            val remote = object : CoordinatorRemote {
                override fun linkDeath(onDeath: () -> Unit): Boolean { if (failure == "link") { pause(); return false }; return true }
                override fun isAlive(): Boolean { if (failure == "alive") { pause(); return false }; return true }
                override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean {
                    if (failure == "admit" || failure == "admit_exception") {
                        pause()
                        if (failure == "admit_exception") throw IllegalStateException("synthetic")
                        return false
                    }
                    return true
                }
                override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean { if (failure == "confirm") { pause(); return false }; return true }
                override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { completed(true); return true }
            }
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner {
                if (failure == "network" || failure == "network_exception") {
                    pause()
                    if (failure == "network_exception") throw IllegalStateException("synthetic")
                    false
                } else true
            })
            val caller = Thread { admission.connect(remote) }
            caller.start()
            try {
                assertTrue("not reached: $failure", observed.await(2, TimeUnit.SECONDS))
                val attempt = Any()
                assertTrue(admission.beginSupervisorStop(attempt))
                admission.supervisedDuplicateClosed(true)
                assertTrue(admission.supervisorDied(supervisorProof(attempt)))
                assertEquals(0, deaths.get())
                resume.countDown()
                caller.join(2_000)
                assertFalse(caller.isAlive)
                assertEquals("lost unknown: $failure", CoordinatorState.QUARANTINED, admission.state())
                assertTrue(processAdmission.isQuarantined())
                assertEquals(0, deaths.get())
            } finally { resume.countDown(); caller.join(2_000) }
        }
    }

    @Test
    fun duplicateCloseFailureDuringSupervisedStopRemainsUnknown(): Unit {
        val lease = FakeLease(failClose = true)
        val remote = FakeRemote(lease, blockAdmit = true, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.entered.await(2, TimeUnit.SECONDS))
            val attempt = Any()
            assertTrue(admission.beginSupervisorStop(attempt))
            remote.finishUnbind(true)
            admission.supervisedDuplicateClosed(true)
            assertTrue(admission.supervisorDied(supervisorProof(attempt)))
            remote.release.countDown()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
            assertEquals(0, lease.deaths.get())
            assertTrue(lease.quarantined.get())
        } finally { remote.release.countDown(); caller.join(2_000) }
    }

    @Test
    fun supervisedStopPreventsAnUnclaimedDuplicateTransfer(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, blockLink = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val closes = AtomicInteger()
        val prepared = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 {
            override fun close(): Unit { closes.incrementAndGet() }
        }, {}, {})
        assertTrue(prepared.beginBind())
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.linkEntered.await(2, TimeUnit.SECONDS))
            val attempt = Any()
            assertTrue(admission.beginSupervisorStop(attempt))
            assertEquals(WorkerTransferObservation.Fenced, admission.observeTransfer(prepared))
            prepared.close()
            admission.supervisedDuplicateClosed(true)
            assertTrue(admission.supervisorDied(supervisorProof(attempt)))
            assertEquals(0, lease.deaths.get())
            remote.linkRelease.countDown()
            caller.join(2_000)
            assertFalse(caller.isAlive)
            assertEquals(1, closes.get())
            assertEquals(1, lease.deaths.get())
            assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        } finally { remote.linkRelease.countDown(); caller.join(2_000) }
    }

    private fun supervisorProof(attempt: Any): SupervisorDeathProof {
        var proof: SupervisorDeathProof? = null
        val evidence = SupervisorTerminationEvidence(attempt) { proof = it }
        check(evidence.beginLink())
        evidence.linkResult(true)
        evidence.unbindResult(true)
        evidence.binderDied()
        return checkNotNull(proof)
    }

    @Test
    fun lateRetirementObserverRequiresTheSameWorkerDeathAndCompletedUnbind(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val other = CoordinatorAdmission(FakeLease(), identity, network, RoundOwner { true })
        assertTrue(admission.connect(remote))
        assertFalse(admission.observeRetirement { fail("not stopping") })
        admission.stop()
        assertFalse(admission.awaitRetirement(1))
        val delivered = CountDownLatch(1)
        val observed = AtomicReference<CoordinatorRetirementProof?>()
        assertTrue(admission.observeRetirement { observed.set(it); delivered.countDown() })
        assertFalse(admission.observeRetirement { fail("second observer") })
        remote.die()
        assertEquals(1L, delivered.count)
        assertEquals(0, lease.deaths.get())
        remote.finishUnbind(true)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        val proof = observed.get()
        assertNotNull(proof)
        assertTrue(admission.ownsRetirement(proof!!, identity))
        assertFalse(other.ownsRetirement(proof, identity))
        assertFalse(admission.ownsRetirement(proof, identity.copy(workerGeneration = 10)))
        assertEquals(1, lease.deaths.get())
    }

    @Test
    fun lateRetirementObserverDoesNotUpgradeFailedUnbindAfterDeath(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertTrue(admission.connect(remote))
        admission.stop()
        val delivered = CountDownLatch(1)
        val observed = AtomicReference<CoordinatorRetirementProof?>()
        assertTrue(admission.observeRetirement { observed.set(it); delivered.countDown() })
        remote.finishUnbind(false)
        remote.die()
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertNull(observed.get())
        assertEquals(0, lease.deaths.get())
        assertTrue(lease.quarantined.get())
    }

    @Test
    fun observerCanRegisterAfterRetirementAndRunsOutsideAdmissionLock(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertTrue(admission.connect(remote))
        admission.stop()
        remote.die()
        var proof: CoordinatorRetirementProof? = null
        val otherThreadRead = CountDownLatch(1)
        assertTrue(admission.observeRetirement {
            proof = it
            Thread { admission.state(); otherThreadRead.countDown() }.start()
            assertTrue(otherThreadRead.await(2, TimeUnit.SECONDS))
        })
        assertTrue(otherThreadRead.await(2, TimeUnit.SECONDS))
        assertNotNull(proof)
        assertTrue(admission.ownsRetirement(proof!!, identity))
    }

    @Test
    fun observerWaitsForAnInFlightAdmissionAndDuplicateClose(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, blockAdmit = true, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val caller = Thread { admission.connect(remote) }
        caller.start()
        try {
            assertTrue(remote.entered.await(2, TimeUnit.SECONDS))
            admission.stop()
            val delivered = CountDownLatch(1)
            val proof = AtomicReference<CoordinatorRetirementProof?>()
            assertTrue(admission.observeRetirement { proof.set(it); delivered.countDown() })
            remote.die()
            remote.finishUnbind(true)
            assertEquals(1L, delivered.count)
            assertEquals(0, lease.duplicateClosed.get())
            remote.release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(1, lease.duplicateClosed.get())
            assertTrue(admission.ownsRetirement(proof.get()!!, identity))
        } finally { remote.release.countDown(); caller.join(2_000) }
    }

    @Test
    fun concurrentObserverRegistrationAndRetirementDeliverExactlyOnce(): Unit {
        val lease = FakeLease()
        val remote = FakeRemote(lease, delayUnbind = true)
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        assertTrue(admission.connect(remote))
        admission.stop()
        remote.die()
        val barrier = CyclicBarrier(2)
        val delivered = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val proof = AtomicReference<CoordinatorRetirementProof?>()
        val registration = Thread {
            barrier.await()
            check(admission.observeRetirement { proof.set(it); callbacks.incrementAndGet(); delivered.countDown() })
        }
        registration.start()
        barrier.await()
        remote.finishUnbind(true)
        registration.join(2_000)
        assertFalse(registration.isAlive)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, callbacks.get())
        assertTrue(admission.ownsRetirement(proof.get()!!, identity))
    }

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
        override fun sealForRecovery(): Boolean = !quarantined.get() && outstanding.get() && (realLease?.sealForRecovery() ?: true)
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
