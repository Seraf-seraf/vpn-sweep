package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.ArrayDeque
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PreconnectRecoveryTest {
    @Test fun exactSupervisorAndActualLocalDrainAreBothRequired(): Unit {
        val test = Harness()
        val operation = test.drain.begin(41)!!
        assertTrue(test.start())
        test.pump()
        assertFalse(test.attempt.started)
        assertNull(test.outcome)
        test.unbind!!(true)
        test.pump()
        assertTrue(test.attempt.started)
        test.attempt.die()
        test.pump()
        assertNull(test.outcome)
        assertEquals(0, test.lease.deaths)
        test.closeDuplicate!!.invoke()
        test.pump()
        assertEquals(1, test.lease.deaths)
        assertNull(test.outcome)
        operation.closed(true)
        test.pump()
        assertEquals(true, test.outcome)
    }

    @Test fun failedLocalCloseOrOldUnbindNeverProducesRecovery(): Unit {
        for (failure in listOf("local", "unbind", "duplicate")) {
            val test = Harness(failure == "duplicate")
            val operation = test.drain.begin(41)!!
            assertTrue(test.start())
            test.unbind!!(failure != "unbind")
            test.pump()
            if (failure != "unbind") {
                test.attempt.die()
                test.pump()
                test.closeDuplicate!!.invoke()
            }
            operation.closed(failure != "local")
            test.pump()
            assertEquals(false, test.outcome)
        }
    }

    @Test fun openDrainDistinguishesExpectedWaitEndFromLateFailure(): Unit {
        for (kind in listOf("wait", "action", "close")) {
            val drain = SearchWorkerLocalDrain(41)
            val ticket = checkNotNull(drain.begin(41))
            var outcome: Boolean? = null
            assertTrue(drain.seal(41) { outcome = it != null })
            try {
                trackedPreconnectOpen(ticket) { resource ->
                    trackedWorkerResource(resource, { Any() }, {
                        if (kind == "close") throw IOException("close failed")
                    }) {
                        if (kind == "action") throw IOException("unexpected action failure")
                        throw ExpectedPreconnectWaitStop()
                    }
                }
                fail("expected failure")
            } catch (_: IOException) { }
            assertEquals(kind == "wait", outcome)
        }
    }

    private class Harness(private val failDuplicate: Boolean = false) {
        val drain = SearchWorkerLocalDrain(41)
        val lease = FakeLease()
        val admission = CoordinatorAdmission(lease, WorkerIdentityV1(41, 1uL, 1, 1), SearchNetworkV1(2, 3), RoundOwner { true })
        val actions = ArrayDeque<() -> Unit>()
        var unbind: ((Boolean) -> Unit)? = null
        var closeDuplicate: (() -> Unit)? = null
        var outcome: Boolean? = null
        lateinit var attempt: FakeAttempt
        private val worker = object : PreconnectWorkerStop {
            override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean =
                SupervisedCoordinatorStop(admission, { unbind = it; true }, { closeDuplicate = it; true },
                    { !failDuplicate }, unbound).begin(attempt)
            override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)
            override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)
            override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)
            override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean =
                admission.ownsRetirement(proof, WorkerIdentityV1(41, 1uL, 1, 1))
        }
        private val custody = Any()
        val recovery = PreconnectRecovery(41, custody, drain, worker, { delay, action -> if (delay == 0L) actions.add(action); true },
            { confirmed, retry, failed -> FakeAttempt(actions, confirmed, retry, failed).also { attempt = it } })

        fun start(): Boolean = recovery.start { outcome = it != null }

        fun pump(): Unit { while (actions.isNotEmpty()) actions.removeFirst().invoke() }
    }

    private class FakeAttempt(
        actions: ArrayDeque<() -> Unit>,
        confirmed: (SupervisorDeathProof) -> Unit,
        retry: () -> Unit,
        failed: () -> Unit,
    ) : SupervisorAttemptPort {
        private var death: (() -> Unit)? = null
        private val binding = SupervisorBindingAttempt({ actions.add(it); true },
            { _, died -> death = died; true }, { true }, confirmed, retry, failed)
        var started = false
        override val identity: Any get() = binding.identity
        override fun start(): SupervisorStartResult {
            started = true
            binding.bindReturned(true)
            binding.connected(Any())
            return SupervisorStartResult.STARTED
        }
        override fun connectionTimedOut(): Unit = binding.connectionTimedOut()
        fun die(): Unit = checkNotNull(death).invoke()
    }

    private class FakeLease : CoordinatorLease {
        var deaths = 0
        override fun duplicate(): SearchLeaseDuplicateV1? = null
        override fun anchorHeldForWorker(): Boolean = true
        override fun sealForRecovery(): Boolean = true
        override fun workerDeathConfirmed(): Unit { deaths++ }
        override fun quarantine(): Unit = Unit
    }
}
