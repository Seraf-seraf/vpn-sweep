package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchworker.SupervisorDeathProof

internal sealed interface SupervisorOwner {
    data class Acquired(val owner: RecoveryOwner) : SupervisorOwner
    data object Busy : SupervisorOwner
    data object Unknown : SupervisorOwner
}

internal enum class SupervisorRecoveryResult { RECOVERED, BUSY, UNKNOWN }

internal class SupervisedStartupRecovery(
    private val backend: UpgradeRecoveryBackend,
    private val markActive: () -> Boolean,
    private val preparePending: () -> Boolean,
    private val finishPending: () -> Boolean,
    private val acquire: () -> SupervisorOwner,
) {
    private var prepared = false
    private var expectedSupervisor: Any? = null

    fun prepare(): Boolean = synchronized(backend) {
        if (backend.quarantined || Thread.currentThread().isInterrupted) return@synchronized false
        backend.recoveryBlocked = true
        try {
            val initialIntent = backend.readIntent()
            val marker = backend.readRawMarker()
            if (marker == MarkerState.UNKNOWN) return@synchronized false
            if (marker == MarkerState.CLEAR) {
                if (initialIntent !is RecoveryIntentState.Armed || !markActive() ||
                    backend.readRawMarker() != MarkerState.ACTIVE
                ) return@synchronized false
            }
            if (Thread.currentThread().isInterrupted || !preparePending() ||
                backend.readIntent() != RecoveryIntentState.SupervisedPending
            ) return@synchronized false
            prepared = true
            true
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
    }

    fun expectSupervisor(identity: Any): Boolean = synchronized(backend) {
        if (!prepared || backend.quarantined) false else { expectedSupervisor = identity; true }
    }

    fun complete(proof: SupervisorDeathProof): SupervisorRecoveryResult = synchronized(backend) {
        if (!prepared || backend.quarantined || Thread.currentThread().isInterrupted) return@synchronized SupervisorRecoveryResult.UNKNOWN
        val expected = expectedSupervisor ?: return@synchronized SupervisorRecoveryResult.UNKNOWN
        if (!proof.matches(expected)) return@synchronized SupervisorRecoveryResult.UNKNOWN
        var owner: RecoveryOwner? = null
        var closeAttempted = false
        try {
            if (backend.readIntent() != RecoveryIntentState.SupervisedPending || backend.readRawMarker() != MarkerState.ACTIVE) {
                return@synchronized SupervisorRecoveryResult.UNKNOWN
            }
            val acquiredOwner = when (val acquired = acquire()) {
                SupervisorOwner.Busy -> return@synchronized SupervisorRecoveryResult.BUSY
                SupervisorOwner.Unknown -> return@synchronized SupervisorRecoveryResult.UNKNOWN
                is SupervisorOwner.Acquired -> acquired.owner
            }
            owner = acquiredOwner
            if (Thread.currentThread().isInterrupted || !backend.reconcile() || Thread.currentThread().isInterrupted) {
                return@synchronized SupervisorRecoveryResult.UNKNOWN
            }
            closeAttempted = true
            acquiredOwner.close()
            if (Thread.currentThread().isInterrupted || !finishPending() ||
                backend.readIntent() != RecoveryIntentState.Done || Thread.currentThread().isInterrupted
            ) return@synchronized SupervisorRecoveryResult.UNKNOWN
            if (!backend.clearRawMarker() || backend.readRawMarker() != MarkerState.CLEAR || Thread.currentThread().isInterrupted) {
                backend.quarantined = true
                return@synchronized SupervisorRecoveryResult.UNKNOWN
            }
            backend.recoveryBlocked = false
            prepared = false
            expectedSupervisor = null
            SupervisorRecoveryResult.RECOVERED
        } catch (_: Exception) {
            backend.quarantined = true
            SupervisorRecoveryResult.UNKNOWN
        } catch (_: LinkageError) {
            backend.quarantined = true
            SupervisorRecoveryResult.UNKNOWN
        } finally {
            if (!closeAttempted) try { owner?.close() } catch (_: Exception) { backend.quarantined = true } catch (_: LinkageError) { backend.quarantined = true }
        }
    }
}
