package com.seraf.vpnsweep

import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.searchstate.SupervisedStartupRecovery
import com.seraf.vpnsweep.searchstate.SupervisorRecoveryResult
import com.seraf.vpnsweep.searchstate.UpgradeRecoveryBackend
import com.seraf.vpnsweep.searchworker.SupervisorAttemptPort
import com.seraf.vpnsweep.searchworker.SupervisorDeathProof
import com.seraf.vpnsweep.searchworker.SupervisorStartResult

internal enum class SupervisedRecoveryStatus { PENDING, COMPLETE, UNKNOWN }

internal fun finishSupervisedRecovery(
    subscriptions: SubscriptionController,
    controller: SearchController,
    backend: UpgradeRecoveryBackend,
    recovery: SupervisedStartupRecovery,
    mode: StartupRecoveryMode,
    proof: SupervisorDeathProof,
): SupervisorRecoveryResult {
    val result = subscriptions.withRecovery {
        if (subscriptions.state.value.requestActive) return@withRecovery SupervisorRecoveryResult.BUSY
        synchronized(controller) {
            val outcome = recovery.complete(proof)
            if (outcome == SupervisorRecoveryResult.RECOVERED && !controller.completeUpgradeRecovery()) {
                synchronized(backend) { backend.quarantined = true }
                SupervisorRecoveryResult.UNKNOWN
            } else outcome
        }
    }
    if (result == SupervisorRecoveryResult.RECOVERED) mode.complete()
    return result
}

internal class SupervisedRecoveryCoordinator(
    private val recovery: SupervisedStartupRecovery,
    private val blocked: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> Boolean,
    private val newAttempt: ((SupervisorDeathProof) -> Unit, () -> Unit, () -> Unit) -> SupervisorAttemptPort,
    private val complete: (SupervisorDeathProof) -> SupervisorRecoveryResult,
    private val report: (SupervisedRecoveryStatus) -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var finished = false
    private var current: Any? = null

    fun start(): Boolean {
        synchronized(lock) { if (started) return false; started = true }
        report(SupervisedRecoveryStatus.PENDING)
        return enqueue(0) { if (recovery.prepare()) beginAttempt() else fail() }
    }

    private fun beginAttempt(): Unit {
        if (!blocked()) return
        val token = Any()
        synchronized(lock) { if (finished) return; current = token }
        val attempt = try {
            newAttempt(
                { proof -> if (current(token)) enqueue(0) { finish(token, proof) } },
                { if (current(token)) enqueue(RETRY_MILLIS) { if (current(token)) beginAttempt() } },
                { if (current(token)) fail() },
            )
        } catch (_: Exception) { fail(); return } catch (_: LinkageError) { fail(); return }
        if (!recovery.expectSupervisor(attempt.identity)) return fail()
        when (try { attempt.start() } catch (_: Exception) { SupervisorStartResult.UNKNOWN } catch (_: LinkageError) { SupervisorStartResult.UNKNOWN }) {
            SupervisorStartResult.STARTED -> enqueue(CONNECTION_TIMEOUT_MILLIS) { if (current(token)) attempt.connectionTimedOut() }
            SupervisorStartResult.RETRYING -> Unit
            SupervisorStartResult.UNKNOWN -> fail()
        }
    }

    private fun finish(token: Any, proof: SupervisorDeathProof): Unit {
        if (!current(token)) return
        when (try { complete(proof) } catch (_: Exception) { SupervisorRecoveryResult.UNKNOWN } catch (_: LinkageError) { SupervisorRecoveryResult.UNKNOWN }) {
            SupervisorRecoveryResult.RECOVERED -> {
                synchronized(lock) { if (current !== token || finished) return; finished = true }
                report(SupervisedRecoveryStatus.COMPLETE)
            }
            SupervisorRecoveryResult.BUSY -> enqueue(RETRY_MILLIS) { finish(token, proof) }
            SupervisorRecoveryResult.UNKNOWN -> fail()
        }
    }

    private fun current(token: Any): Boolean = synchronized(lock) { !finished && current === token }

    private fun enqueue(delayMillis: Long, action: () -> Unit): Boolean {
        val accepted = try { schedule(delayMillis, action) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!accepted) fail()
        return accepted
    }

    private fun fail(): Unit {
        val notify = synchronized(lock) { if (finished) false else { finished = true; true } }
        if (notify) report(SupervisedRecoveryStatus.UNKNOWN)
    }

    private companion object {
        const val RETRY_MILLIS = 1_000L
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
    }
}
