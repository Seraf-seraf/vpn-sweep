package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class WorkerAnchorObservation { HELD, FENCED, UNKNOWN }
internal enum class WorkerAdmissionObservation { ADMITTED, FENCED, UNKNOWN }
internal sealed interface WorkerDuplicateObservation {
    data class Available(val value: SearchLeaseDuplicateV1) : WorkerDuplicateObservation
    data object Fenced : WorkerDuplicateObservation
    data object Unknown : WorkerDuplicateObservation
}
internal sealed interface WorkerTransferObservation {
    data class Claimed(val value: SearchLeaseDuplicateV1) : WorkerTransferObservation
    data object Fenced : WorkerTransferObservation
    data object Unknown : WorkerTransferObservation
}

internal interface CoordinatorLease {
    fun duplicate(): SearchLeaseDuplicateV1?
    fun anchorHeldForWorker(): Boolean
    fun observeAnchor(): WorkerAnchorObservation = if (anchorHeldForWorker()) WorkerAnchorObservation.HELD else WorkerAnchorObservation.UNKNOWN
    fun observeDuplicate(): WorkerDuplicateObservation = duplicate()?.let { WorkerDuplicateObservation.Available(it) } ?: WorkerDuplicateObservation.Unknown
    fun sealForRecovery(): Boolean = false
    fun workerDeathConfirmed(): Unit
    fun quarantine(): Unit
}

internal interface CoordinatorRemote {
    fun linkDeath(onDeath: () -> Unit): Boolean
    fun isAlive(): Boolean
    fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean
    fun observeAdmission(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): WorkerAdmissionObservation =
        if (admit(identity, network, duplicate)) WorkerAdmissionObservation.ADMITTED else WorkerAdmissionObservation.UNKNOWN
    fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean
    fun requestUnbind(completed: (Boolean) -> Unit): Boolean
}

// CONFIRMED describes lease admission only; it does not enable worker START.
internal enum class CoordinatorState { NEW, ADMITTING, CONFIRMED, REMOTE_PENDING, DEATH_PENDING, STOPPING, WORKER_RETIRED, QUARANTINED }

internal class CoordinatorRetirementProof internal constructor()

internal class CoordinatorAdmission(
    private val lease: CoordinatorLease,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val owner: RoundOwner,
    private val onConfirmedDeath: () -> Boolean = { false },
) {
    private val lock = Any()
    private var state = CoordinatorState.NEW
    private var remote: CoordinatorRemote? = null
    private var inFlight = false
    private var duplicateClosed = true
    private var deathSeen = false
    private var deathLinked = false
    private var deathBeforeLink = false
    private val linkFinished = CountDownLatch(1)
    private var unbindRequested = false
    private var unbindConfirmed = false
    private val retirementFinished = CountDownLatch(1)
    private var retirementObserved = false
    private var retirementProof: CoordinatorRetirementProof? = null
    private var retirementCallback: ((CoordinatorRetirementProof?) -> Unit)? = null
    private var supervisedStop = false
    private var supervisorAttempt: Any? = null
    private var supervisorDeathSeen = false
    private var supervisedDuplicateClosed = false
    private var supervisedRemoteDeathRequired = false

    fun state(): CoordinatorState = synchronized(lock) { state }

    fun hasLinkedRemote(candidate: CoordinatorRemote): Boolean = synchronized(lock) {
        remote === candidate && deathLinked && state != CoordinatorState.QUARANTINED && state != CoordinatorState.WORKER_RETIRED
    }

    fun awaitLinkedRemote(candidate: CoordinatorRemote): Boolean {
        val wait = synchronized(lock) {
            !deathLinked && ((remote === candidate && state == CoordinatorState.ADMITTING) || (remote == null && state == CoordinatorState.NEW))
        }
        if (wait) {
            var interrupted = Thread.interrupted()
            try {
                while (true) {
                    try { linkFinished.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        return hasLinkedRemote(candidate)
    }

    fun awaitRetirement(remainingNanos: Long): Boolean {
        if (remainingNanos <= 0) return false
        return try {
            retirementFinished.await(remainingNanos, TimeUnit.NANOSECONDS) && state() == CoordinatorState.WORKER_RETIRED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean {
        val delivery = synchronized(lock) {
            if (retirementObserved ||
                (state != CoordinatorState.STOPPING && state != CoordinatorState.WORKER_RETIRED && state != CoordinatorState.QUARANTINED)
            ) return false
            retirementObserved = true
            retirementCallback = completed
            takeRetirementDelivery()
        }
        delivery?.invoke()
        return true
    }

    fun ownsRetirement(proof: CoordinatorRetirementProof, expected: WorkerIdentityV1): Boolean = synchronized(lock) {
        state == CoordinatorState.WORKER_RETIRED && identity == expected && retirementProof === proof
    }

    fun observeTransfer(duplicate: PreparedWorkerLeaseDuplicate): WorkerTransferObservation = synchronized(lock) {
        when {
            state == CoordinatorState.STOPPING || state == CoordinatorState.REMOTE_PENDING || state == CoordinatorState.DEATH_PENDING -> WorkerTransferObservation.Fenced
            state != CoordinatorState.ADMITTING -> WorkerTransferObservation.Unknown
            else -> duplicate.beginTransfer()?.let { WorkerTransferObservation.Claimed(it) } ?: WorkerTransferObservation.Unknown
        }
    }

    fun beginSupervisorStop(attempt: Any): Boolean {
        synchronized(lock) {
            if (state != CoordinatorState.NEW && state != CoordinatorState.ADMITTING && state != CoordinatorState.CONFIRMED &&
                state != CoordinatorState.REMOTE_PENDING && state != CoordinatorState.DEATH_PENDING) return false
            if (!lease.sealForRecovery()) return false
            supervisedRemoteDeathRequired = deathLinked
            state = CoordinatorState.STOPPING
            supervisedStop = true
            supervisorAttempt = attempt
        }
        requestUnbind()
        return true
    }

    fun replaceSupervisorAttempt(attempt: Any): Boolean = synchronized(lock) {
        if (!supervisedStop || state != CoordinatorState.STOPPING || supervisorDeathSeen) false else {
            supervisorAttempt = attempt
            true
        }
    }

    fun supervisedUnbindCompleted(success: Boolean): Unit {
        synchronized(lock) {
            if (!supervisedStop || state != CoordinatorState.STOPPING) return
            if (success) {
                unbindConfirmed = true
                retireIfSafe()
            } else {
                state = CoordinatorState.QUARANTINED
                lease.quarantine()
                retirementFinished.countDown()
            }
        }
        deliverRetirement()
    }

    fun supervisedDuplicateClosed(success: Boolean): Unit {
        synchronized(lock) {
            if (!supervisedStop || state != CoordinatorState.STOPPING) return
            if (success) {
                supervisedDuplicateClosed = true
                retireIfSafe()
            } else {
                state = CoordinatorState.QUARANTINED
                lease.quarantine()
                retirementFinished.countDown()
            }
        }
        deliverRetirement()
    }

    fun supervisorDied(proof: SupervisorDeathProof): Boolean {
        val accepted = synchronized(lock) {
            val expected = supervisorAttempt
            val matches = if (expected == null) false else try { proof.matches(expected) } catch (_: Exception) { false } catch (_: LinkageError) { false }
            if (!supervisedStop || state != CoordinatorState.STOPPING || supervisorDeathSeen || !matches) false else {
                supervisorDeathSeen = true
                retireIfSafe()
                true
            }
        }
        deliverRetirement()
        return accepted
    }

    fun connect(worker: CoordinatorRemote): Boolean {
        synchronized(lock) {
            if (state != CoordinatorState.NEW) return false
            state = CoordinatorState.ADMITTING
            remote = worker
            inFlight = true
        }
        try {
            if (!anchorCurrent()) return false
            if (!current()) return reject()
            var linked = false
            try { linked = worker.linkDeath { workerDied(worker) } }
            finally {
                val recover = synchronized(lock) {
                    deathLinked = linked
                    if (linked && deathBeforeLink && state == CoordinatorState.ADMITTING) {
                        deathSeen = true
                        state = CoordinatorState.DEATH_PENDING
                        true
                    } else false
                }
                linkFinished.countDown()
                if (recover) requestRecovery()
            }
            if (!linked) return reject()
            if (!admitting()) return false
            if (!worker.isAlive()) return remoteUnavailable(worker)
            if (!current()) return reject()
            val duplicate = when (val observed = lease.observeDuplicate()) {
                is WorkerDuplicateObservation.Available -> observed.value
                WorkerDuplicateObservation.Fenced -> return abortFenced(supervisorOnly = true)
                WorkerDuplicateObservation.Unknown -> return if (normalStop()) false else reject()
            }
            synchronized(lock) {
                duplicateClosed = false
            }
            val admitted = try {
                if (!admitting()) return false
                if (!anchorCurrent()) return false
                if (!worker.isAlive()) return remoteUnavailable(worker)
                if (!current()) return reject()
                worker.observeAdmission(identity, network, duplicate)
            } finally {
                try {
                    duplicate.close()
                    synchronized(lock) { duplicateClosed = true }
                } catch (_: Exception) {
                    quarantine()
                }
            }
            when (admitted) {
                WorkerAdmissionObservation.FENCED -> return abortFenced(supervisorOnly = false)
                WorkerAdmissionObservation.UNKNOWN -> return if (normalStop()) false else reject()
                WorkerAdmissionObservation.ADMITTED -> Unit
            }
            if (!admitting()) return false
            if (!anchorCurrent()) return false
            if (!worker.isAlive()) return remoteUnavailable(worker)
            if (!current()) return reject()
            val confirmed = worker.confirm(identity, network)
            if (!confirmed && !normalStop()) return reject()
            if (!admitting()) return false
            if (!anchorCurrent()) return false
            if (!worker.isAlive()) return remoteUnavailable(worker)
            if (!current()) return reject()
            return synchronized(lock) {
                if (state == CoordinatorState.ADMITTING) {
                    state = CoordinatorState.CONFIRMED
                    true
                } else {
                    false
                }
            }
        } catch (_: WorkerRemoteDeliveryFailure) {
            return remoteUnavailable(worker)
        } catch (_: Exception) {
            return reject()
        } catch (_: LinkageError) {
            return reject()
        } finally {
            linkFinished.countDown()
            synchronized(lock) {
                inFlight = false
                retireIfSafe()
            }
            deliverRetirement()
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
        deliverRetirement()
        requestUnbind()
    }

    fun disconnected(): Unit = quarantine()

    private fun workerDied(worker: CoordinatorRemote): Unit {
        var recover = false
        synchronized(lock) {
            if (remote !== worker || state == CoordinatorState.WORKER_RETIRED) return
            when (state) {
                CoordinatorState.STOPPING -> { deathSeen = true; retireIfSafe() }
                CoordinatorState.CONFIRMED -> { deathSeen = true; state = CoordinatorState.DEATH_PENDING; recover = true }
                CoordinatorState.ADMITTING -> {
                    if (!deathLinked) { deathBeforeLink = true; return }
                    deathSeen = true; state = CoordinatorState.DEATH_PENDING; recover = true
                }
                CoordinatorState.REMOTE_PENDING -> { deathSeen = true; state = CoordinatorState.DEATH_PENDING }
                CoordinatorState.DEATH_PENDING -> return
                else -> {
                    state = CoordinatorState.QUARANTINED
                    lease.quarantine()
                    retirementFinished.countDown()
                }
            }
        }
        deliverRetirement()
        if (recover) requestRecovery() else if (state() == CoordinatorState.STOPPING) requestUnbind()
    }

    private fun remoteUnavailable(worker: CoordinatorRemote): Boolean {
        val outcome = synchronized(lock) {
            if (remote !== worker || !deathLinked) -1 else when (state) {
                CoordinatorState.ADMITTING -> { state = CoordinatorState.REMOTE_PENDING; 1 }
                CoordinatorState.REMOTE_PENDING, CoordinatorState.DEATH_PENDING -> 0
                CoordinatorState.STOPPING -> if (supervisedStop) 0 else -1
                else -> -1
            }
        }
        if (outcome < 0) return reject()
        if (outcome > 0) requestRecovery()
        return false
    }

    private fun requestRecovery(): Unit {
        val started = try { onConfirmedDeath() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!started) quarantine()
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

    private fun anchorCurrent(): Boolean = when (lease.observeAnchor()) {
        WorkerAnchorObservation.HELD -> true
        WorkerAnchorObservation.FENCED -> abortFenced(supervisorOnly = true)
        WorkerAnchorObservation.UNKNOWN -> reject()
    }

    private fun normalStop(): Boolean = synchronized(lock) { !supervisedStop && state == CoordinatorState.STOPPING }

    private fun abortFenced(supervisorOnly: Boolean): Boolean {
        val expected = synchronized(lock) {
            state == CoordinatorState.STOPPING && (!supervisorOnly || supervisedStop) ||
                deathLinked && (state == CoordinatorState.REMOTE_PENDING || state == CoordinatorState.DEATH_PENDING)
        }
        if (!expected) quarantine()
        return false
    }

    private fun quarantine(): Unit {
        synchronized(lock) {
            if (state == CoordinatorState.WORKER_RETIRED) return
            state = CoordinatorState.QUARANTINED
            lease.quarantine()
            linkFinished.countDown()
            retirementFinished.countDown()
        }
        deliverRetirement()
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

    private fun unbindCompleted(success: Boolean): Unit {
        synchronized(lock) {
            if (success) {
                unbindConfirmed = true
                retireIfSafe()
            } else if (state != CoordinatorState.WORKER_RETIRED) {
                state = CoordinatorState.QUARANTINED
                lease.quarantine()
                retirementFinished.countDown()
            }
        }
        deliverRetirement()
    }

    private fun deliverRetirement(): Unit {
        val delivery = synchronized(lock) { takeRetirementDelivery() }
        delivery?.invoke()
    }

    private fun takeRetirementDelivery(): (() -> Unit)? {
        if (state != CoordinatorState.WORKER_RETIRED && state != CoordinatorState.QUARANTINED) return null
        val callback = retirementCallback ?: return null
        retirementCallback = null
        val proof = if (state == CoordinatorState.WORKER_RETIRED) retirementProof else null
        return { try { callback(proof) } catch (_: Exception) { } catch (_: LinkageError) { } }
    }

    private fun retireIfSafe(): Unit {
        if (state != CoordinatorState.STOPPING || !unbindConfirmed || inFlight || !duplicateClosed) return
        if (supervisedStop) {
            if (!supervisorDeathSeen || !supervisedDuplicateClosed || (supervisedRemoteDeathRequired && !deathSeen)) return
        } else if (!deathSeen) return
        try {
            lease.workerDeathConfirmed()
            state = CoordinatorState.WORKER_RETIRED
            retirementProof = CoordinatorRetirementProof()
            retirementFinished.countDown()
        } catch (_: Exception) {
            state = CoordinatorState.QUARANTINED
            lease.quarantine()
            retirementFinished.countDown()
        }
    }
}
