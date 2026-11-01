package com.seraf.vpnsweep.searchworker

internal class SupervisorBindingAttempt(
    private val dispatch: (() -> Unit) -> Boolean,
    private val link: (Any, () -> Unit) -> Boolean,
    private val unbind: () -> Boolean,
    confirmed: (SupervisorDeathProof) -> Unit,
    private val retry: () -> Unit,
    private val failed: () -> Unit = {},
) {
    private val lock = Any()
    val identity: Any = Any()
    private val evidence = SupervisorTerminationEvidence(identity, confirmed)
    private var bound: Boolean? = null
    private var remote: Any? = null
    private var observed = false
    private var linkStarted = false
    private var stopRequested = false
    private var finished = false
    private var invalid = false

    fun bindReturned(success: Boolean): Unit {
        val action = synchronized(lock) {
            if (bound != null || finished) return
            bound = success
            if (!success) {
                finished = true
                if (observed) { evidence.reject(); 4 } else 3
            } else nextLocked()
        }
        runAction(action)
    }

    fun connected(value: Any?): Unit {
        val action = synchronized(lock) {
            if (finished) return
            if (observed || value == null) {
                evidence.reject()
                invalid = true
                observed = true
                if (!linkStarted) stopRequested = true
            } else {
                observed = true
                remote = value
            }
            nextLocked()
        }
        runAction(action)
    }

    fun bindingDied(): Unit = stopIfUnconnected()

    fun connectionTimedOut(): Unit = stopIfUnconnected()

    fun bindUnknown(): Unit = synchronized(lock) { finished = true; evidence.reject() }

    private fun stopIfUnconnected(): Unit {
        val action = synchronized(lock) {
            if (finished || linkStarted) return
            stopRequested = true
            nextLocked()
        }
        runAction(action)
    }

    private fun nextLocked(): Int {
        if (bound != true || finished) return 0
        if (stopRequested) { finished = true; return 2 }
        if (remote != null && !linkStarted) { linkStarted = true; return 1 }
        return 0
    }

    private fun runAction(action: Int): Unit {
        when (action) {
            1 -> if (!dispatch(::linkAndUnbind)) { evidence.reject(); failed() }
            2 -> if (!dispatch(::unbindAndRetry)) { evidence.reject(); failed() }
            3 -> retry()
            4 -> failed()
        }
    }

    private fun linkAndUnbind(): Unit {
        val candidate = synchronized(lock) { remote } ?: return evidence.reject()
        if (!evidence.beginLink()) {
            synchronized(lock) { finished = true }
            return unbindAndRetry()
        }
        val linked = try { link(candidate, evidence::binderDied) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        evidence.linkResult(linked)
        val released = try { unbind() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        val rejected = synchronized(lock) { finished = true; invalid }
        if (linked && !rejected) {
            evidence.unbindResult(released)
            if (!released) failed()
        } else if (released) retry() else { evidence.reject(); failed() }
    }

    private fun unbindAndRetry(): Unit {
        val released = try { unbind() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (released) retry() else { evidence.reject(); failed() }
    }
}
