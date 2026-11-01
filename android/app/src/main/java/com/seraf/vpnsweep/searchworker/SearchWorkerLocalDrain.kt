package com.seraf.vpnsweep.searchworker

import java.io.IOException

internal class SearchWorkerLocalDrain(
    private val sessionGeneration: Long,
    private val lock: Any = Any(),
) {
    private var sealed = false
    private var failed = false
    private var active = 0
    private var completion: ((Proof?) -> Unit)? = null
    private var issuedProof: Proof? = null

    internal class Proof internal constructor(private val source: SearchWorkerLocalDrain) {
        fun belongsTo(owner: SearchWorkerLocalDrain, generation: Long): Boolean =
            source === owner && source.owns(this, generation)
    }

    internal sealed interface Ticket {
        fun retain(): Ticket?
        fun closed(confirmed: Boolean): Unit
    }

    private inner class ResourceTicket : Ticket {
        private var open = true

        override fun retain(): Ticket? = synchronized(lock) {
            if (!open) null else newTicket()
        }

        override fun closed(confirmed: Boolean): Unit {
            val notification = synchronized(lock) {
                if (!open) return
                open = false
                active--
                if (!confirmed) failed = true
                notification()
            }
            notification?.invoke()
        }
    }

    fun begin(generation: Long): Ticket? = synchronized(lock) {
        if (generation <= 0 || generation != sessionGeneration || sealed) null else newTicket()
    }

    fun seal(generation: Long, completed: (Proof?) -> Unit): Boolean {
        val notification = synchronized(lock) {
            if (generation <= 0 || generation != sessionGeneration || sealed) return false
            sealed = true
            completion = completed
            notification()
        }
        notification?.invoke()
        return true
    }

    private fun newTicket(): Ticket {
        active++
        return ResourceTicket()
    }

    private fun owns(proof: Proof, generation: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration && issuedProof === proof && sealed && !failed && active == 0
    }

    private fun notification(): (() -> Unit)? {
        if (!sealed || (!failed && active != 0)) return null
        val callback = completion ?: return null
        completion = null
        val proof = if (failed) null else Proof(this).also { issuedProof = it }
        return {
            try { callback(proof) } catch (_: Exception) { } catch (_: LinkageError) { }
        }
    }
}

internal class SearchWorkerDispatch(private val ticket: SearchWorkerLocalDrain.Ticket) {
    private val lock = Any()
    private var claimed = false
    private var cancelled = false

    fun claim(): Boolean = synchronized(lock) {
        if (claimed || cancelled) false else {
            claimed = true
            true
        }
    }

    fun takeBeforeClaim(): Boolean = synchronized(lock) {
        if (claimed || cancelled) false else {
            cancelled = true
            true
        }
    }

    fun cancelBeforeClaim(): Unit {
        if (takeBeforeClaim()) ticket.closed(true)
    }
}

internal inline fun <T : Any, R> trackedWorkerResource(
    ticket: SearchWorkerLocalDrain.Ticket,
    acquire: () -> T,
    close: (T) -> Unit,
    noResourceOnFailure: (Throwable) -> Boolean = { false },
    action: (T) -> R,
): R {
    var closed = false
    try {
        val resource = try { acquire() } catch (failure: Throwable) {
            closed = noResourceOnFailure(failure)
            throw failure
        }
        try {
            return action(resource)
        } finally {
            close(resource)
            closed = true
        }
    } finally {
        ticket.closed(closed)
    }
}

internal inline fun <T> trackedWorkerTransaction(
    operationTicket: SearchWorkerLocalDrain.Ticket?,
    action: () -> T,
    recycle: () -> Boolean,
): T {
    val parcelTicket = operationTicket?.retain()
    if (operationTicket != null && parcelTicket == null) throw IOException("worker_drain_unknown")
    var recycled = false
    try {
        return action()
    } finally {
        try { recycled = recycle() } finally { parcelTicket?.closed(recycled) }
    }
}
