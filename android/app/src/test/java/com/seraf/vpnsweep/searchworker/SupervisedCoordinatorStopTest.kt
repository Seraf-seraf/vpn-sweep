package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SupervisedCoordinatorStopTest {
    private val identity = WorkerIdentityV1(42, 1uL, 1, 3)
    private val network = SearchNetworkV1(5, 7)

    @Test fun startsSupervisorOnlyAfterOldUnbindAndWaitsForActualDuplicateClose(): Unit {
        val lease = FakeLease()
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val unbind = AtomicReference<((Boolean) -> Unit)?>()
        val closer = AtomicReference<(() -> Unit)?>()
        val unbound = mutableListOf<Boolean>()
        val closes = AtomicInteger()
        val stop = SupervisedCoordinatorStop(admission,
            { completion -> unbind.set(completion); true },
            { action -> closer.set(action); true },
            { closes.incrementAndGet(); true }, { unbound.add(it) })
        val attempt = Any()
        assertTrue(stop.begin(attempt))
        assertEquals(CoordinatorState.STOPPING, admission.state())
        assertTrue(unbound.isEmpty())
        assertEquals(0, lease.deaths)
        unbind.get()!!.invoke(true)
        assertEquals(listOf(true), unbound)
        assertTrue(admission.supervisorDied(proof(attempt)))
        assertEquals(0, lease.deaths)
        closer.get()!!.invoke()
        closer.get()!!.invoke()
        assertEquals(1, closes.get())
        assertEquals(1, lease.deaths)
        assertEquals(CoordinatorState.WORKER_RETIRED, admission.state())
        unbind.get()!!.invoke(false)
        assertEquals(listOf(true), unbound)
    }

    @Test fun failedOrUnknownOldUnbindAndDuplicateKeepTheLeaseFenced(): Unit {
        for (failure in listOf("unbind", "close", "schedule")) {
            val lease = FakeLease()
            val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
            val unbind = AtomicReference<((Boolean) -> Unit)?>()
            val closer = AtomicReference<(() -> Unit)?>()
            val unbound = mutableListOf<Boolean>()
            val stop = SupervisedCoordinatorStop(admission,
                { completion -> unbind.set(completion); true },
                { action -> if (failure == "schedule") false else { closer.set(action); true } },
                { failure != "close" }, { unbound.add(it) })
            val attempt = Any()
            assertTrue(stop.begin(attempt))
            unbind.get()!!.invoke(failure != "unbind")
            closer.get()?.invoke()
            assertFalse(admission.supervisorDied(proof(attempt)))
            assertEquals(0, lease.deaths)
            assertTrue(lease.quarantined)
            assertEquals(CoordinatorState.QUARANTINED, admission.state())
        }
    }

    @Test fun staleSupervisorAttemptCannotRetireTheRound(): Unit {
        val lease = FakeLease()
        val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true })
        val old = Any()
        val current = Any()
        assertTrue(admission.beginSupervisorStop(old))
        assertTrue(admission.replaceSupervisorAttempt(current))
        admission.supervisedUnbindCompleted(true)
        admission.supervisedDuplicateClosed(true)
        assertFalse(admission.supervisorDied(proof(old)))
        assertEquals(0, lease.deaths)
        assertTrue(admission.supervisorDied(proof(current)))
        assertEquals(1, lease.deaths)
    }

    private fun proof(attempt: Any): SupervisorDeathProof {
        var result: SupervisorDeathProof? = null
        val evidence = SupervisorTerminationEvidence(attempt) { result = it }
        check(evidence.beginLink())
        evidence.linkResult(true)
        evidence.unbindResult(true)
        evidence.binderDied()
        return checkNotNull(result)
    }

    private class FakeLease : CoordinatorLease {
        var deaths = 0
        var quarantined = false
        override fun duplicate(): SearchLeaseDuplicateV1? = null
        override fun anchorHeldForWorker(): Boolean = !quarantined
        override fun sealForRecovery(): Boolean = !quarantined
        override fun workerDeathConfirmed(): Unit { deaths++ }
        override fun quarantine(): Unit { quarantined = true }
    }
}
