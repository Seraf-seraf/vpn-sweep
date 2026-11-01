package com.seraf.vpnsweep.searchworker

internal class OwnerCallbackGate(
    private val lock: Any,
    private val drain: SearchWorkerLocalDrain,
    private val sessionGeneration: Long,
) {
    internal class Entry internal constructor(internal val ticket: SearchWorkerLocalDrain.Ticket) {
        internal var responded = false
        internal var responseConfirmed = false
    }

    private var sealed = false
    private val active = mutableSetOf<Entry>()

    fun begin(): Entry? = synchronized(lock) {
        if (sealed) return null
        val ticket = drain.begin(sessionGeneration) ?: return null
        Entry(ticket).also { active.add(it) }
    }

    fun seal(): Unit = synchronized(lock) { sealed = true }

    fun respond(entry: Entry, allowed: Boolean, write: (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (entry !in active || entry.responded) return false
        entry.responded = true
        try {
            write(allowed && !sealed)
            entry.responseConfirmed = true
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    fun finish(entry: Entry): Unit {
        val confirmed = synchronized(lock) {
            if (!active.remove(entry)) return
            entry.responseConfirmed
        }
        entry.ticket.closed(confirmed)
    }
}
