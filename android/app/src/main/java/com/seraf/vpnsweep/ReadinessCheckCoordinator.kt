package com.seraf.vpnsweep

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReadinessCheckState(
    val inProgress: Boolean = false,
    val sequence: Long = 0,
    val result: SearchReadiness? = null,
)

internal class ReadinessCheckCoordinator(
    private val executor: Executor,
    private val check: () -> SearchReadiness,
    private val onComplete: (SearchReadiness) -> Unit,
    private val beforeFinishPublish: () -> Unit = {},
) {
    private val statusLock = Any()
    private var busy = false
    private val mutableState = MutableStateFlow(ReadinessCheckState())
    val state: StateFlow<ReadinessCheckState> = mutableState.asStateFlow()

    fun request(synchronous: () -> Boolean = { false }): Boolean {
        val runSynchronously = synchronized(statusLock) {
            if (busy) return false
            busy = true
            mutableState.value = mutableState.value.copy(inProgress = true)
            synchronous()
        }
        val run = Runnable {
            val result = try { check() } catch (_: Exception) { SearchReadiness.UNKNOWN } catch (_: LinkageError) { SearchReadiness.UNKNOWN }
            try { onComplete(result) } catch (_: Exception) { }
            finish(result)
        }
        if (runSynchronously) {
            run.run()
            return true
        }
        try {
            executor.execute(run)
        } catch (_: Exception) {
            finish(SearchReadiness.UNKNOWN)
            return false
        }
        return true
    }

    private fun finish(result: SearchReadiness): Unit = synchronized(statusLock) {
        busy = false
        try { beforeFinishPublish() } finally {
            mutableState.value = ReadinessCheckState(false, mutableState.value.sequence + 1, result)
        }
    }
}

internal class StartupRecoveryMode(initiallyBlocked: Boolean) {
    @Volatile private var blocked = initiallyBlocked
    fun blocked(): Boolean = blocked
    fun complete(): Unit { blocked = false }
}

internal class OwnerBusyAutoRecovery(
    private val schedule: (() -> Unit) -> Boolean,
    private val pending: () -> Boolean,
    private val recover: () -> SearchStartV3,
    private val beforeRelease: () -> Unit = {},
) {
    private val scheduled = AtomicBoolean()
    private val requested = AtomicBoolean()

    fun request(): Unit {
        if (!pending()) return
        requested.set(true)
        if (!scheduled.compareAndSet(false, true)) return
        val accepted = try {
            schedule {
                requested.set(false)
                val outcome = try { if (pending()) recover() else null } catch (_: Exception) { null } catch (_: LinkageError) { null }
                try { beforeRelease() } finally {
                    scheduled.set(false)
                    if (requested.get() || outcome == SearchStartV3.BUSY) request()
                }
            }
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!accepted) scheduled.set(false)
    }
}
