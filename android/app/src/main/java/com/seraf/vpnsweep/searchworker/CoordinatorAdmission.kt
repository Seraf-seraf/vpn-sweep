package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface CoordinatorLease {
    fun duplicate(): SearchLeaseDuplicateV1?
    fun anchorHeldForWorker(): Boolean
    fun workerDeathConfirmed(): Unit
    fun quarantine(): Unit
}

internal interface CoordinatorRemote {
    fun linkDeath(onDeath: () -> Unit): Boolean
    fun isAlive(): Boolean
    fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean
    fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean
    fun requestUnbind(completed: (Boolean) -> Unit): Boolean
}

// CONFIRMED describes lease admission only; it does not enable worker START.
internal enum class CoordinatorState { NEW, ADMITTING, CONFIRMED, STOPPING, WORKER_RETIRED, QUARANTINED }

internal class CoordinatorAdmission(
    private val lease: CoordinatorLease,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val owner: RoundOwner,
) {
    private val lock = Any()
    private var state = CoordinatorState.NEW
    private var remote: CoordinatorRemote? = null
    private var inFlight = false
    private var duplicateClosed = true
    private var deathSeen = false
    private var unbindRequested = false
    private var unbindConfirmed = false
    private val retirementFinished = CountDownLatch(1)

    fun state(): CoordinatorState = synchronized(lock) { state }

    fun awaitRetirement(remainingNanos: Long): Boolean {
        if (remainingNanos <= 0) return false
        return try {
            retirementFinished.await(remainingNanos, TimeUnit.NANOSECONDS) && state() == CoordinatorState.WORKER_RETIRED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun connect(worker: CoordinatorRemote): Boolean {
        synchronized(lock) {
            if (state != CoordinatorState.NEW) return false
            state = CoordinatorState.ADMITTING
            remote = worker
            inFlight = true
        }
        try {
            if (!lease.anchorHeldForWorker() || !current()) return reject()
            if (!worker.linkDeath { workerDied(worker) }) return reject()
            if (!admitting()) return false
            if (!worker.isAlive() || !current()) return reject()
            val duplicate = lease.duplicate()
            if (duplicate == null) {
                if (admitting()) return reject()
                return false
            }
            synchronized(lock) {
                duplicateClosed = false
            }
            val admitted = try {
                if (!admitting()) return false
                if (!lease.anchorHeldForWorker() || !worker.isAlive() || !current()) return reject()
                worker.admit(identity, network, duplicate)
            } finally {
                try {
                    duplicate.close()
                    synchronized(lock) { duplicateClosed = true }
                } catch (_: Exception) {
                    quarantine()
                }
            }
            if (!admitting()) return false
            if (!admitted || !lease.anchorHeldForWorker() || !worker.isAlive() || !current()) return reject()
            val confirmed = worker.confirm(identity, network)
            if (!admitting()) return false
            if (!confirmed || !lease.anchorHeldForWorker() || !worker.isAlive() || !current()) return reject()
            return synchronized(lock) {
                if (state == CoordinatorState.ADMITTING) {
                    state = CoordinatorState.CONFIRMED
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            return reject()
        } catch (_: LinkageError) {
            return reject()
        } finally {
            synchronized(lock) {
                inFlight = false
                retireIfSafe()
            }
        }
    }

    fun stop(): Unit {
        synchronized(lock) {
            when (state) {
                CoordinatorState.NEW -> {
                    state = CoordinatorState.QUARANTINED
                    lease.quarantine()
                    retirementFinished.countDown()
                }
                CoordinatorState.ADMITTING, CoordinatorState.CONFIRMED -> state = CoordinatorState.STOPPING
                else -> return
            }
        }
        requestUnbind()
    }

    fun disconnected(): Unit = quarantine()

    private fun workerDied(worker: CoordinatorRemote): Unit {
        synchronized(lock) {
            if (remote !== worker || state == CoordinatorState.WORKER_RETIRED) return
            if (state == CoordinatorState.STOPPING) {
                deathSeen = true
                retireIfSafe()
            } else {
                state = CoordinatorState.QUARANTINED
                lease.quarantine()
                retirementFinished.countDown()
            }
        }
        requestUnbind()
    }

    private fun admitting(): Boolean = synchronized(lock) { state == CoordinatorState.ADMITTING }

    private fun current(): Boolean = try {
        owner.isCurrent(network)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun reject(): Boolean {
        quarantine()
        return false
    }

    private fun quarantine(): Unit {
        synchronized(lock) {
            if (state == CoordinatorState.WORKER_RETIRED) return
            state = CoordinatorState.QUARANTINED
            lease.quarantine()
            retirementFinished.countDown()
        }
        requestUnbind()
    }

    private fun requestUnbind(): Unit {
        val worker = synchronized(lock) {
            if (unbindRequested) return
            val current = remote ?: return
            unbindRequested = true
            current
        }
        val scheduled = try {
            worker.requestUnbind(::unbindCompleted)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!scheduled) unbindCompleted(false)
    }

    private fun unbindCompleted(success: Boolean): Unit = synchronized(lock) {
        if (success) {
            unbindConfirmed = true
            retireIfSafe()
        } else if (state != CoordinatorState.WORKER_RETIRED) {
            state = CoordinatorState.QUARANTINED
            lease.quarantine()
            retirementFinished.countDown()
        }
    }

    private fun retireIfSafe(): Unit {
        if (state != CoordinatorState.STOPPING || !deathSeen || !unbindConfirmed || inFlight || !duplicateClosed) return
        try {
            lease.workerDeathConfirmed()
            state = CoordinatorState.WORKER_RETIRED
            retirementFinished.countDown()
        } catch (_: Exception) {
            state = CoordinatorState.QUARANTINED
            lease.quarantine()
            retirementFinished.countDown()
        }
    }
}
