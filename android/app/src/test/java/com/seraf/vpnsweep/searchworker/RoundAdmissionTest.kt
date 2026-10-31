package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundAdmissionTest {
    private val network = SearchNetworkV1(identity = 7, version = 3)
    private val first = WorkerIdentityV1(sessionGeneration = 11, attempt = 1uL, round = 1, workerGeneration = 1)
    private val next = WorkerIdentityV1(sessionGeneration = 11, attempt = 1uL, round = 2, workerGeneration = 2)

    @Test
    fun startRequiresBothDeathRegistrationsLeaseAndCurrentOwner(): Unit {
        var valid = true
        val gate = RoundAdmission(11) { valid }
        assertTrue(gate.reserve(first, network))
        assertFalse(gate.begin(first))
        assertTrue(gate.workerDeathLinked(first))
        assertFalse(gate.begin(first))
        assertTrue(gate.ownerDeathLinked(first))
        assertFalse(gate.begin(first))
        assertTrue(gate.leaseDelivered(first))
        valid = false
        assertFalse(gate.begin(first))
        valid = true
        assertFalse(gate.begin(first))
        assertFalse(gate.reserve(next, network))
    }

    @Test
    fun cleanupAndDeathAreBothRequiredBeforeAnotherWorker(): Unit {
        val gate = RoundAdmission(11) { true }
        assertTrue(gate.reserve(first, network))
        assertTrue(gate.workerDeathLinked(first))
        assertTrue(gate.ownerDeathLinked(first))
        assertTrue(gate.leaseDelivered(first))
        assertTrue(gate.begin(first))
        assertFalse(gate.begin(first))
        var delivered = false
        assertTrue(gate.dispatch(first) { delivered = true })
        assertTrue(delivered)
        gate.revoke(first)
        assertFalse(gate.dispatch(first) { error("revoked generation dispatched") })
        gate.cleanupConfirmed(first)
        assertFalse(gate.reserve(next, network))
        gate.deathConfirmed(first)
        assertFalse(gate.reserve(first, network))
        assertTrue(gate.reserve(next, network))
        assertFalse(gate.dispatch(first) { error("old generation dispatched") })
    }

    @Test
    fun deathWithoutCleanupAndNetworkChangeStayClosed(): Unit {
        var activeNetwork = network
        val gate = RoundAdmission(11) { it == activeNetwork }
        assertTrue(gate.reserve(first, network))
        assertTrue(gate.workerDeathLinked(first))
        assertTrue(gate.ownerDeathLinked(first))
        assertTrue(gate.leaseDelivered(first))
        assertTrue(gate.begin(first))
        activeNetwork = network.copy(version = 4)
        assertFalse(gate.dispatch(first) { error("stale network dispatched") })
        gate.deathConfirmed(first)
        assertFalse(gate.reserve(next, activeNetwork))
        gate.cleanupConfirmed(first)
        assertTrue(gate.reserve(next, activeNetwork))
    }

    @Test
    fun wrongSessionAndOwnerFailureDenyAdmission(): Unit {
        val gate = RoundAdmission(11) { throw IllegalStateException("synthetic") }
        assertFalse(gate.reserve(first, network))
        val other = RoundAdmission(12) { true }
        assertFalse(other.reserve(first, network))
    }

    @Test
    fun sharedLeaseModelKeepsVpnOutUntilWorkerDuplicateDies(): Unit {
        val lease = FakeLease()
        assertTrue(lease.acquireVpn())
        val gate = RoundAdmission(11) { lease.searchOwnerAlive }
        assertFalse(gate.reserve(first, network))
        lease.closeVpn()
        assertTrue(lease.acquireSearch())
        assertTrue(gate.reserve(first, network))
        assertTrue(gate.workerDeathLinked(first))
        assertTrue(gate.ownerDeathLinked(first))
        lease.deliverDuplicate()
        assertTrue(gate.leaseDelivered(first))
        assertTrue(gate.begin(first))
        lease.closeSearchOwner()
        assertFalse(gate.dispatch(first) { error("lost owner dispatched") })
        assertFalse(lease.acquireVpn())
        gate.cleanupConfirmed(first)
        assertFalse(gate.reserve(next, network))
        lease.closeWorkerOnDeath()
        gate.deathConfirmed(first)
        assertTrue(lease.acquireVpn())
        assertFalse(gate.reserve(next, network))
    }

    private class FakeLease {
        var searchOwnerAlive = false
            private set
        private var vpn = false
        private var workerDuplicate = false

        fun acquireVpn(): Boolean {
            if (vpn || searchOwnerAlive || workerDuplicate) return false
            vpn = true
            return true
        }

        fun closeVpn(): Unit { vpn = false }

        fun acquireSearch(): Boolean {
            if (vpn || searchOwnerAlive || workerDuplicate) return false
            searchOwnerAlive = true
            return true
        }

        fun deliverDuplicate(): Unit { check(searchOwnerAlive); workerDuplicate = true }
        fun closeSearchOwner(): Unit { searchOwnerAlive = false }
        fun closeWorkerOnDeath(): Unit { workerDuplicate = false }
    }
}
