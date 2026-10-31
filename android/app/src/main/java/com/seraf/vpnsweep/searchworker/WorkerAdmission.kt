package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1

internal interface ClientDeathHandle {
    fun link(onDeath: () -> Unit): Boolean
    fun isAlive(): Boolean
}

internal interface ReceivedWorkerLease : AutoCloseable {
    fun validate(): Boolean
}

internal class WorkerAdmission(private val terminate: () -> Unit) {
    private enum class State { NEW, ADMITTING, PENDING_CONFIRMATION, ADMITTED, REVOKED }

    private val lock = Any()
    private var state = State.NEW
    private var identity: WorkerIdentityV1? = null
    private var network: SearchNetworkV1? = null
    private var owner: ClientDeathHandle? = null
    private val leases = mutableListOf<ReceivedWorkerLease>()
    private var terminationScheduled = false

    fun admit(
        requested: WorkerIdentityV1,
        selected: SearchNetworkV1,
        incoming: ReceivedWorkerLease,
        client: ClientDeathHandle,
    ): Boolean {
        val mayAdmit = synchronized(lock) {
            leases.add(incoming)
            if (state != State.NEW) return@synchronized false
            state = State.ADMITTING
            owner = client
            identity = requested
            network = selected
            true
        }
        if (!mayAdmit) {
            revoke()
            return false
        }
        val valid = try {
            client.link(::revoke) && client.isAlive() && incoming.validate() && client.isAlive()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        val accepted = synchronized(lock) {
            if (state == State.ADMITTING && valid) {
                state = State.PENDING_CONFIRMATION
                true
            } else {
                false
            }
        }
        if (!accepted) revoke()
        return accepted
    }

    fun confirm(requested: WorkerIdentityV1, selected: SearchNetworkV1): Boolean {
        val current = synchronized(lock) {
            if (state == State.PENDING_CONFIRMATION && identity == requested && network == selected) owner else null
        } ?: return false
        val alive = try {
            current.isAlive()
        } catch (_: Exception) {
            false
        }
        if (!alive) {
            revoke()
            return false
        }
        return synchronized(lock) {
            if (state == State.PENDING_CONFIRMATION && identity == requested && network == selected) {
                state = State.ADMITTED
                true
            } else {
                false
            }
        }
    }

    fun permits(requested: WorkerIdentityV1, selected: SearchNetworkV1): Boolean {
        val current = synchronized(lock) {
            if (state == State.ADMITTED && identity == requested && network == selected) owner else null
        } ?: return false
        val alive = try {
            current.isAlive()
        } catch (_: Exception) {
            false
        }
        if (!alive) {
            revoke()
            return false
        }
        return synchronized(lock) { state == State.ADMITTED && identity == requested && network == selected }
    }

    fun reject(incoming: ReceivedWorkerLease?): Unit {
        synchronized(lock) {
            if (incoming != null) leases.add(incoming)
            if (state == State.NEW) {
                state = State.ADMITTING
            }
        }
        revoke()
    }

    fun revoke(): Unit {
        val mustTerminate = synchronized(lock) {
            state = State.REVOKED
            if (terminationScheduled) false else {
                terminationScheduled = true
                true
            }
        }
        if (mustTerminate) terminate()
    }
}
