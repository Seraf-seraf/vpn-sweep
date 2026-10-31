package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerAdmissionTest {
    private val identity = WorkerIdentityV1(5, 1uL, 1, 7)
    private val network = SearchNetworkV1(10, 3)

    @Test
    fun acceptsOneGenerationOnlyAfterDeathLinkAndLeaseValidation(): Unit {
        var exits = 0
        val admission = WorkerAdmission { exits++ }
        val owner = FakeClient()
        val lease = FakeLease()
        assertTrue(admission.admit(identity, network, lease, owner))
        assertTrue(owner.linked)
        assertTrue(lease.validated)
        assertFalse(admission.permits(identity, network))
        assertTrue(admission.confirm(identity, network))
        assertTrue(admission.permits(identity, network))
        assertFalse(admission.permits(identity.copy(workerGeneration = 8), network))
        assertFalse(admission.permits(identity, network.copy(version = 4)))
        owner.die()
        assertFalse(admission.permits(identity, network))
        assertFalse(lease.closed)
        admission.revoke()
        assertEquals(1, exits)
    }

    @Test
    fun repeatedAdmissionRevokesTheWorker(): Unit {
        var exits = 0
        val admission = WorkerAdmission { exits++ }
        assertTrue(admission.admit(identity, network, FakeLease(), FakeClient()))
        assertFalse(admission.admit(identity.copy(workerGeneration = 8), network, FakeLease(), FakeClient()))
        assertFalse(admission.permits(identity, network))
        assertEquals(1, exits)
    }

    @Test
    fun deathDuringLinkCannotBecomeAdmitted(): Unit {
        var exits = 0
        val admission = WorkerAdmission { exits++ }
        val owner = FakeClient(dieDuringLink = true)
        assertFalse(admission.admit(identity, network, FakeLease(), owner))
        assertFalse(admission.permits(identity, network))
        assertEquals(1, exits)
    }

    @Test
    fun unknownOrInvalidLeaseTerminatesWithoutClosingItsDescriptor(): Unit {
        for (owner in listOf(FakeClient(linkOk = false), FakeClient(alive = false), FakeClient())) {
            var exits = 0
            val lease = FakeLease(valid = false)
            val admission = WorkerAdmission { exits++ }
            assertFalse(admission.admit(identity, network, lease, owner))
            assertFalse(admission.permits(identity, network))
            assertEquals(1, exits)
            assertFalse(lease.closed)
        }
    }

    @Test
    fun losingClientBetweenDispatchesRevokesAdmission(): Unit {
        var exits = 0
        val admission = WorkerAdmission { exits++ }
        val owner = FakeClient()
        assertTrue(admission.admit(identity, network, FakeLease(), owner))
        assertTrue(admission.confirm(identity, network))
        owner.alive = false
        assertFalse(admission.permits(identity, network))
        assertEquals(1, exits)
    }

    @Test
    fun coordinatorDeathBetweenFlockAndConfirmationCannotAdmit(): Unit {
        var exits = 0
        val owner = FakeClient()
        val admission = WorkerAdmission { exits++ }
        assertTrue(admission.admit(identity, network, FakeLease(), owner))
        owner.die()
        assertFalse(admission.confirm(identity, network))
        assertFalse(admission.permits(identity, network))
        assertEquals(1, exits)
    }

    @Test
    fun coordinatorDeathDoesNotWaitForBlockedLeaseValidation(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val exits = AtomicInteger()
        val result = AtomicBoolean(true)
        val owner = FakeClient()
        val admission = WorkerAdmission { exits.incrementAndGet(); terminated.countDown() }
        val lease = object : ReceivedWorkerLease {
            override fun validate(): Boolean {
                entered.countDown()
                release.await()
                return true
            }
            override fun close(): Unit = Unit
        }
        val worker = Thread { result.set(admission.admit(identity, network, lease, owner)) }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            owner.die()
            assertTrue(terminated.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.join(1000)
        }
        assertFalse(worker.isAlive)
        assertFalse(result.get())
        assertEquals(1, exits.get())
    }

    private class FakeLease(private val valid: Boolean = true) : ReceivedWorkerLease {
        var validated = false
        var closed = false
        override fun validate(): Boolean { validated = true; return valid }
        override fun close(): Unit { closed = true }
    }

    private class FakeClient(
        private val linkOk: Boolean = true,
        @Volatile var alive: Boolean = true,
        private val dieDuringLink: Boolean = false,
    ) : ClientDeathHandle {
        var linked = false
        private var death: (() -> Unit)? = null

        override fun link(onDeath: () -> Unit): Boolean {
            linked = linkOk
            death = onDeath
            if (dieDuringLink) die()
            return linkOk
        }

        override fun isAlive(): Boolean = alive

        fun die(): Unit { alive = false; death?.invoke() }
    }
}
