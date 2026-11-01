package com.seraf.vpnsweep.searchworker

internal sealed interface SupervisorDeathProof {
    fun matches(identity: Any): Boolean
}

private class ConfirmedSupervisorDeath(private val identity: Any) : SupervisorDeathProof {
    override fun matches(identity: Any): Boolean = this.identity === identity
}

internal class SupervisorTerminationEvidence(private val identity: Any, private val confirmed: (SupervisorDeathProof) -> Unit) {
    private val lock = Any()
    private var linkStarted = false
    private var linked = false
    private var unbound = false
    private var died = false
    private var failed = false
    private var delivered = false

    fun beginLink(): Boolean = synchronized(lock) {
        if (linkStarted || failed || delivered) false else { linkStarted = true; true }
    }

    fun linkResult(success: Boolean): Unit = update {
        if (!linkStarted || linked || !success) failed = true else linked = true
    }

    fun unbindResult(success: Boolean): Unit = update {
        if (!linkStarted || unbound || !success) failed = true else unbound = true
    }

    fun binderDied(): Unit = update {
        if (!linkStarted || died) failed = true else died = true
    }

    fun bindingDied(): Unit = Unit

    fun reject(): Unit = update { failed = true }

    private fun update(change: () -> Unit): Unit {
        val deliver = synchronized(lock) {
            if (delivered) return
            change()
            if (!failed && linked && unbound && died) { delivered = true; true } else false
        }
        if (deliver) confirmed(ConfirmedSupervisorDeath(identity))
    }
}
