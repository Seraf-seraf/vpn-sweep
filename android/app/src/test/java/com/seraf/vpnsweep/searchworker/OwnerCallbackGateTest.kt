package com.seraf.vpnsweep.searchworker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import org.junit.Assert.*
import org.junit.Test

class OwnerCallbackGateTest {
    @Test fun latePositiveResultIsDeniedAndTrackedUntilTheCallbackReturns(): Unit {
        val drain = SearchWorkerLocalDrain(7)
        val gate = OwnerCallbackGate(Any(), drain, 7)
        val entered = gate.begin()!!
        val validatedBeforeStop = true
        gate.seal()
        var proof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(7) { proof = it })
        assertNull(proof)
        var reply: Boolean? = null
        assertTrue(gate.respond(entered, validatedBeforeStop) { reply = it })
        assertEquals(false, reply)
        assertNull(proof)
        gate.finish(entered)
        assertNotNull(proof)
        assertNull(gate.begin())
    }

    @Test fun stopWaitsForAReplyAlreadyBeingWrittenBeforeClosingTheGate(): Unit {
        val drain = SearchWorkerLocalDrain(8)
        val gate = OwnerCallbackGate(Any(), drain, 8)
        val entry = gate.begin()!!
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val reply = AtomicReference<Boolean?>()
        val writerFailure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                check(gate.respond(entry, true) { value ->
                    reply.set(value)
                    writing.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                })
            } catch (failure: Throwable) { writerFailure.set(failure) }
            finally { gate.finish(entry) }
        }
        val stopper = Thread { stopStarted.countDown(); gate.seal(); stopped.countDown() }
        writer.start()
        try {
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            stopper.start()
            assertTrue(stopStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1L, stopped.count)
            release.countDown()
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            writer.join(2_000)
            assertNull(writerFailure.get())
            assertEquals(true, reply.get())
            assertNull(gate.begin())
            var proof: SearchWorkerLocalDrain.Proof? = null
            assertTrue(drain.seal(8) { proof = it })
            assertNotNull(proof)
        } finally { release.countDown(); writer.join(2_000); stopper.join(2_000) }
    }

    @Test fun uncertainReplyNeverBecomesDrainProof(): Unit {
        val drain = SearchWorkerLocalDrain(9)
        val gate = OwnerCallbackGate(Any(), drain, 9)
        val entry = gate.begin()!!
        assertFalse(gate.respond(entry, true) { throw IllegalStateException("reply_unknown") })
        gate.finish(entry)
        gate.seal()
        var delivered = false
        var proof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(9) { delivered = true; proof = it })
        assertTrue(delivered)
        assertNull(proof)
    }

    @Test fun supervisorDeathCannotReplaceAStillRunningOwnerCallback(): Unit {
        val processAdmission = SearchOwnerAdmission()
        val token = checkNotNull(processAdmission.reserve())
        val lease = SearchOwnerLease(12345, processAdmission, token, closeFd = { true }, nowMillis = { 1 })
        assertTrue(lease.reserveWorker())
        val coordinatorLease = object : CoordinatorLease {
            override fun duplicate(): SearchLeaseDuplicateV1? = null
            override fun anchorHeldForWorker(): Boolean = lease.anchorHeldForWorker()
            override fun sealForRecovery(): Boolean = lease.sealForRecovery()
            override fun workerDeathConfirmed(): Unit = lease.workerDeathConfirmed()
            override fun quarantine(): Unit = lease.quarantine()
        }
        val identity = WorkerIdentityV1(10, 1uL, 1, 1)
        val admission = CoordinatorAdmission(coordinatorLease, identity, SearchNetworkV1(1, 1), RoundOwner { true })
        val drain = SearchWorkerLocalDrain(10)
        val gate = OwnerCallbackGate(Any(), drain, 10)
        val callback = gate.begin()!!
        val validatedBeforeStop = true
        gate.seal()
        val attempt = Any()
        assertTrue(admission.beginSupervisorStop(attempt))
        var localProof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(10) { localProof = it })
        admission.supervisedUnbindCompleted(true)
        admission.supervisedDuplicateClosed(true)
        var death: SupervisorDeathProof? = null
        val evidence = SupervisorTerminationEvidence(attempt) { death = it }
        assertTrue(evidence.beginLink())
        evidence.linkResult(true)
        evidence.unbindResult(true)
        evidence.binderDied()
        assertTrue(admission.supervisorDied(death!!))
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        assertNull(localProof)
        assertFalse(lease.resumeWorkers())
        var result: Boolean? = null
        assertTrue(gate.respond(callback, validatedBeforeStop) { result = it })
        assertEquals(false, result)
        gate.finish(callback)
        assertNotNull(localProof)
        assertFalse(lease.resumeWorkers())
        assertFalse(lease.releaseUntil(100))
    }
}
