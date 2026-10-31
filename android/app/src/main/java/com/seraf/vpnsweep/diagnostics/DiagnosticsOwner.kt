package com.seraf.vpnsweep.diagnostics

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private object DiagnosticsWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun schedule(action: () -> Unit): Unit { scope.launch { action() } }
}

internal class DiagnosticRunToken(internal val session: Any)

internal data class DiagnosticsState(
    val revision: Long = 0,
    val testing: Boolean = false,
    val results: List<String> = emptyList(),
)

internal object DiagnosticsStatus {
    private val coordinator = DiagnosticsCoordinator()
    fun newOwner(): DiagnosticsOwner = coordinator.newOwner()
    fun reserve(session: Any): Unit = coordinator.reserve(session)
    fun running(session: Any): Unit = coordinator.running(session)
    fun stopping(session: Any): Unit = coordinator.stopping(session)
    fun publishIfCurrent(session: Any, publish: () -> Unit): Boolean =
        coordinator.publishIfCurrent(session, publish)
    fun <T> withDeferredDelivery(action: () -> T): T = coordinator.withDeferredDelivery(action)
}

internal class DiagnosticsCoordinator(
    private val schedule: (() -> Unit) -> Unit = DiagnosticsWork::schedule,
) {
    private val lock = Any()
    private val owners = mutableSetOf<DiagnosticsOwner>()
    private val effects = ArrayDeque<() -> Unit>()
    private var session: Any? = null
    private var running = false
    private var deliveryDeferrals = 0

    fun newOwner(): DiagnosticsOwner = DiagnosticsOwner(this)

    fun reserve(value: Any): Unit {
        locked {
            session = value
            running = false
            for (owner in owners) owner.sessionChangedLocked()
        }
        flush()
    }

    fun running(value: Any): Unit = locked {
        if (session === value) running = true
    }

    fun stopping(value: Any): Unit {
        locked {
            if (session === value) {
                running = false
                for (owner in owners) owner.stoppedLocked()
            }
        }
        flush()
    }

    fun isCurrent(value: Any): Boolean = locked { session === value }

    // Call only from main-serialized cleanup or while holding the registry lock.
    fun publishIfCurrent(value: Any, publish: () -> Unit): Boolean {
        if (!isCurrent(value)) return false
        publish()
        return true
    }

    fun <T> withDeferredDelivery(action: () -> T): T {
        locked { deliveryDeferrals++ }
        try {
            return action()
        } finally {
            locked { deliveryDeferrals-- }
            flush()
        }
    }

    fun flush(): Unit {
        val batch = locked {
            if (deliveryDeferrals != 0) emptyList() else {
                buildList {
                    while (effects.isNotEmpty()) add(effects.removeFirst())
                }
            }
        }
        for (effect in batch) schedule(effect)
    }

    internal fun <T> locked(action: () -> T): T = synchronized(lock, action)
    internal fun sessionLocked(): Any? = session
    internal fun runningLocked(): Boolean = running
    internal fun addLocked(owner: DiagnosticsOwner): Unit { owners.add(owner) }
    internal fun removeLocked(owner: DiagnosticsOwner): Unit { owners.remove(owner) }
    internal fun signalLocked(effect: () -> Unit): Unit { effects.addLast(effect) }
    internal fun cancelLocked(job: Job): Unit { effects.addFirst { job.cancel() } }
}

internal class DiagnosticsOwner(private val coordinator: DiagnosticsCoordinator) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val signals = MutableStateFlow(0L)
    private var current = DiagnosticsState()
    private var attached = false
    private var closed = false
    private var active: DiagnosticRunToken? = null
    private var job: Job? = null
    val currentState: DiagnosticsState get() = coordinator.locked { current }
    val state: Flow<DiagnosticsState> = signals.map { currentState }
        .onStart { emit(currentState) }
        .distinctUntilChanged()

    fun current(observed: DiagnosticsState): DiagnosticsState = coordinator.locked {
        if (observed === current) observed else current
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(start = CoroutineStart.LAZY, block = block)

    fun attach(): Unit {
        coordinator.locked {
            if (!attached && !closed) {
                attached = true
                coordinator.addLocked(this)
                publishLocked(DiagnosticsState(revision = current.revision + 1))
            }
        }
        coordinator.flush()
    }

    fun close(): Unit {
        coordinator.locked {
            if (!closed) {
                closed = true
                attached = false
                coordinator.removeLocked(this)
                active = null
                publishLocked(current.copy(revision = current.revision + 1, testing = false))
                job?.let { coordinator.cancelLocked(it) }
                job = null
                coordinator.cancelLocked(scopeJob)
            }
        }
        coordinator.flush()
    }

    fun begin(): DiagnosticRunToken? {
        val token = coordinator.locked {
            val session = coordinator.sessionLocked()
            if (!attached || session == null || !coordinator.runningLocked() || current.testing) {
                return@locked null
            }
            DiagnosticRunToken(session).also {
                active = it
                publishLocked(DiagnosticsState(revision = current.revision + 1, testing = true))
            }
        }
        coordinator.flush()
        return token
    }

    fun attachJob(token: DiagnosticRunToken, value: Job): Boolean = coordinator.locked {
        if (!attached || active !== token || !current.testing ||
            coordinator.sessionLocked() !== token.session || !coordinator.runningLocked()
        ) return@locked false
        job = value
        true
    }

    fun admit(token: DiagnosticRunToken): Boolean = coordinator.locked {
        attached && active === token && current.testing && coordinator.sessionLocked() === token.session &&
            coordinator.runningLocked()
    }

    fun cancel(value: Job): Unit {
        coordinator.locked { coordinator.cancelLocked(value) }
        coordinator.flush()
    }

    fun report(token: DiagnosticRunToken, result: String): Boolean {
        val accepted = coordinator.locked {
            if (!attached || active !== token || coordinator.sessionLocked() !== token.session ||
                !coordinator.runningLocked()
            ) return@locked false
            publishLocked(current.copy(revision = current.revision + 1, results = current.results + result))
            true
        }
        coordinator.flush()
        return accepted
    }

    fun finish(token: DiagnosticRunToken, successful: Boolean): Unit {
        coordinator.locked {
            if (active === token) {
                if (!successful) active = null
                job = null
                publishLocked(current.copy(revision = current.revision + 1, testing = false))
            }
        }
        coordinator.flush()
    }

    internal fun sessionChangedLocked(): Unit {
        active = null
        job?.let { coordinator.cancelLocked(it) }
        job = null
        publishLocked(DiagnosticsState(revision = current.revision + 1))
    }

    internal fun stoppedLocked(): Unit {
        active = null
        job?.let { coordinator.cancelLocked(it) }
        job = null
        publishLocked(DiagnosticsState(revision = current.revision + 1))
    }

    private fun publishLocked(value: DiagnosticsState): Unit {
        current = value
        coordinator.signalLocked {
            signals.update { previous -> if (previous < value.revision) value.revision else previous }
        }
    }
}
