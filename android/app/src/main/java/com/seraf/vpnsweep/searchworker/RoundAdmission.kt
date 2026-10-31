package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1

internal fun interface RoundOwner {
    fun isCurrent(network: SearchNetworkV1): Boolean
}

// Serializes dispatch against revocation. The platform owner must implement
// isCurrent using the shared lease and the current network identity/version.
internal class RoundAdmission(
    private val sessionGeneration: Long,
    private val owner: RoundOwner,
) {
    private class Round(val identity: WorkerIdentityV1, val network: SearchNetworkV1) {
        var workerDeathLinked = false
        var ownerDeathLinked = false
        var leaseDelivered = false
        var started = false
        var revoked = false
        var cleanupConfirmed = false
        var deathConfirmed = false
    }

    private val lock = Any()
    private var round: Round? = null
    private var previousWorkerGeneration: Long? = null

    fun reserve(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = synchronized(lock) {
        if (identity.sessionGeneration != sessionGeneration || round != null ||
            previousWorkerGeneration?.let { identity.workerGeneration <= it } == true || !current(network)
        ) {
            return@synchronized false
        }
        previousWorkerGeneration = identity.workerGeneration
        round = Round(identity, network)
        true
    }

    fun workerDeathLinked(identity: WorkerIdentityV1): Boolean = update(identity) { it.workerDeathLinked = true }

    fun ownerDeathLinked(identity: WorkerIdentityV1): Boolean = update(identity) { it.ownerDeathLinked = true }

    fun leaseDelivered(identity: WorkerIdentityV1): Boolean = update(identity) { it.leaseDelivered = true }

    fun begin(identity: WorkerIdentityV1): Boolean = synchronized(lock) {
        val active = active(identity) ?: return@synchronized false
        if (!active.workerDeathLinked || !active.ownerDeathLinked || !active.leaseDelivered || active.started) {
            return@synchronized false
        }
        active.started = true
        true
    }

    fun dispatch(identity: WorkerIdentityV1, enqueue: () -> Unit): Boolean = synchronized(lock) {
        val active = active(identity) ?: return@synchronized false
        if (!active.started) return@synchronized false
        enqueue()
        true
    }

    fun revoke(identity: WorkerIdentityV1): Unit = synchronized(lock) {
        round?.takeIf { it.identity == identity }?.revoked = true
    }

    fun cleanupConfirmed(identity: WorkerIdentityV1): Unit = synchronized(lock) {
        val active = round?.takeIf { it.identity == identity } ?: return@synchronized
        active.revoked = true
        active.cleanupConfirmed = true
        retireIfSafe(active)
    }

    fun deathConfirmed(identity: WorkerIdentityV1): Unit = synchronized(lock) {
        val active = round?.takeIf { it.identity == identity } ?: return@synchronized
        active.revoked = true
        active.deathConfirmed = true
        retireIfSafe(active)
    }

    private fun update(identity: WorkerIdentityV1, action: (Round) -> Unit): Boolean = synchronized(lock) {
        val active = active(identity) ?: return@synchronized false
        if (active.started) return@synchronized false
        action(active)
        true
    }

    private fun active(identity: WorkerIdentityV1): Round? {
        val active = round?.takeIf { it.identity == identity && !it.revoked } ?: return null
        if (!current(active.network)) {
            active.revoked = true
            return null
        }
        return active
    }

    private fun current(network: SearchNetworkV1): Boolean = try {
        owner.isCurrent(network)
    } catch (_: Exception) {
        false
    }

    private fun retireIfSafe(active: Round): Unit {
        if (active.cleanupConfirmed && active.deathConfirmed) round = null
    }
}
