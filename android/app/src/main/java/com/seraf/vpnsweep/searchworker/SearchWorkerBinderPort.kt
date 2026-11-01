package com.seraf.vpnsweep.searchworker

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerCloseV1
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import com.seraf.vpnsweep.searchprotocol.WorkerInterruptedV2
import com.seraf.vpnsweep.searchprotocol.WorkerOpenV2
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun createSearchWorkerRound(
    context: Context,
    ownerLease: SearchOwnerLeaseControl,
    owner: NetworkEpochOwner,
    identity: WorkerIdentityV1,
    network: SearchNetworkV1,
    localDrain: SearchWorkerLocalDrain,
    recoveryIntent: PhaseRecoveryIntent,
    onRecoveryFailure: (PhaseRecoveryIntent) -> Unit,
    scheduleRecoveryClose: (() -> Unit) -> Boolean,
    scheduleRecoveryStart: (() -> Unit) -> Boolean,
    onPreconnectTimeout: (PreconnectWorkerStop, SearchWorkerLocalDrain) -> Boolean,
    onCloseRecovery: (PreconnectWorkerStop, SearchWorkerLocalDrain) -> Boolean,
    provenFrameBound: Int?,
    provenReplyBound: Int?,
    reportRetirementFailure: (WorkerCandidateFailureV2) -> Unit = {},
    reportServiceFailure: (WorkerFatalV2) -> Unit = {},
    reportBindFailure: () -> Unit = {},
    reportServiceObservation: (WorkerServiceObservation) -> Unit = {},
): WorkerRoundClient? {
    if (provenFrameBound == null || provenFrameBound < FRAME_HEADER_BYTES || provenReplyBound == null || provenReplyBound < 1) return null
    val coordinator = SearchWorkerCoordinator(context, ownerLease, identity, network, owner, localDrain, scheduleRecoveryClose)
    val duplicate = coordinator.prepare() ?: return null
    return WorkerRoundClient(
        SearchWorkerBinderPort(coordinator, ownerLease, duplicate, identity, network, localDrain, { onPreconnectTimeout(coordinator, localDrain) }, provenFrameBound, provenReplyBound, reportRetirementFailure, reportServiceFailure, reportBindFailure, reportServiceObservation,
            recoveryIntent = recoveryIntent, onRecoveryFailure = onRecoveryFailure, scheduleRecoveryStart = scheduleRecoveryStart,
            onCloseRecovery = { onCloseRecovery(coordinator, localDrain) }),
        duplicate,
    )
}

internal enum class WorkerServiceObservation { BUSY, UNAVAILABLE, DEADLINE, CANCELLED }

internal fun serviceObservation(status: Long): WorkerServiceObservation? = when (status) {
    Mobile.SearchWorkerBusy -> WorkerServiceObservation.BUSY
    Mobile.SearchWorkerUnavailable -> WorkerServiceObservation.UNAVAILABLE
    Mobile.SearchWorkerDeadline -> WorkerServiceObservation.DEADLINE
    Mobile.SearchWorkerCancelled -> WorkerServiceObservation.CANCELLED
    else -> null
}

private const val FRAME_HEADER_BYTES = 16
private const val FRAME_BUFFER_BYTES = 8 * 1024
private const val MAX_CLEANUP_MILLIS = 60_000L
private const val CANCELLED_BEFORE_BIND = -1

private sealed interface OpenDispatchResult {
    class Status(val value: Int) : OpenDispatchResult
    data object WaitExpired : OpenDispatchResult
    data object RecoveryStopped : OpenDispatchResult
    data object RemoteFailure : OpenDispatchResult
}

private sealed interface CancelDispatchResult {
    class Status(val confirmed: Boolean) : CancelDispatchResult
    data object RecoveryStopped : CancelDispatchResult
    data object DeliveryUnknown : CancelDispatchResult
}

private sealed interface CloseDispatchResult {
    data object Retired : CloseDispatchResult
    data object WaitExpired : CloseDispatchResult
    data object RecoveryStopped : CloseDispatchResult
    data object RemoteFailure : CloseDispatchResult
    data object Unknown : CloseDispatchResult
}

internal interface StagedWorkerFrame : AutoCloseable {
    fun descriptor(): ParcelFileDescriptor
}

private class AndroidStagedWorkerFrame(private val value: ParcelFileDescriptor) : StagedWorkerFrame {
    override fun descriptor(): ParcelFileDescriptor = value
    override fun close(): Unit = value.close()
}

internal fun candidateOpenAfterRetirement(status: Long, reportRetirementFailure: (WorkerCandidateFailureV2) -> Unit = {}, retire: () -> Boolean): WorkerOpenV2? {
    val flag = Mobile.SearchWorkerRetryableFlag
    val base = status and flag.inv()
    val reason = when (base) {
        Mobile.SearchWorkerCandidateConfigRejected -> WorkerCandidateFailureV2.CONFIG_REJECTED
        Mobile.SearchWorkerCandidateStartFailed -> WorkerCandidateFailureV2.CORE_START_FAILED
        Mobile.SearchWorkerCandidateStartTimeout -> WorkerCandidateFailureV2.CORE_START_TIMEOUT
        Mobile.SearchWorkerCandidateExitedBeforeReady -> WorkerCandidateFailureV2.CORE_EXITED_BEFORE_READY
        else -> return null
    }
    val retired = try { retire() } catch (_: Exception) { false } catch (_: LinkageError) { false }
    return if (retired) WorkerOpenV2.CandidateFailure(reason, status and flag != 0L)
    else {
        try { reportRetirementFailure(reason) } catch (_: Exception) { } catch (_: LinkageError) { }
        WorkerOpenV2.Fatal(WorkerFatalV2.CLEANUP_INCOMPLETE)
    }
}

internal class SearchWorkerBinderPort(
    private val coordinator: WorkerCoordinatorPort,
    private val ownerLease: SearchOwnerLeaseControl,
    private val duplicate: PreparedWorkerLeaseDuplicate,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val localDrain: SearchWorkerLocalDrain,
    private val onPreconnectTimeout: () -> Boolean,
    private val provenFrameBound: Int,
    private val provenReplyBound: Int,
    private val reportRetirementFailure: (WorkerCandidateFailureV2) -> Unit = {},
    private val reportServiceFailure: (WorkerFatalV2) -> Unit = {},
    private val reportBindFailure: () -> Unit = {},
    private val reportServiceObservation: (WorkerServiceObservation) -> Unit = {},
    private val stageFrameOverride: ((FrameInput, Long) -> StagedWorkerFrame)? = null,
    private val statusTransactionOverride: ((IBinder, Int, (Parcel) -> Unit) -> Int)? = null,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val awaitBounded: (CountDownLatch, Long) -> Boolean = { done, millis -> done.await(millis, TimeUnit.MILLISECONDS) },
    private val callTransactionOverride: ((IBinder, WorkerOperationV1, FrameInput, SearchWorkerLocalDrain.Ticket) -> WorkerReplyV1)? = null,
    private val afterCallSnapshot: () -> Unit = {},
    private val afterCallOutcome: () -> Unit = {},
    private val afterCallBinder: () -> Unit = {},
    private val afterOpenBinder: () -> Unit = {},
    private val afterBoundedOutcome: () -> Unit = {},
    private val beforeRecoveryWait: () -> Unit = {},
    private val recoveryIntent: PhaseRecoveryIntent? = null,
    private val onRecoveryFailure: (PhaseRecoveryIntent) -> Unit = {},
    private val scheduleRecoveryStart: (() -> Unit) -> Boolean = { action ->
        try { Thread(action, "search-recovery-start").apply { isDaemon = true }.start(); true }
        catch (_: Exception) { false } catch (_: LinkageError) { false }
    },
    private val onCloseRecovery: () -> Boolean = { false },
    private val closeTransactionOverride: ((IBinder, SearchWorkerLocalDrain.Ticket) -> Int)? = null,
    private val beforeConnectedRecoveryAdmission: () -> Unit = {},
    private val beforeCloseAdmission: () -> Unit = {},
) : DeadlineWorkerPort {
    private enum class State { NEW, OPENING, READY, CLOSING, RECOVERY_PENDING, RETIRED, QUARANTINED }
    private enum class CallDisposition { ACCEPT, DEADLINE, CANCELLED, RECOVERY, UNKNOWN }
    private val lock = Any()
    private var state = State.NEW
    private var cancelled = false
    private var cancelPending = false
    private var cancelConfirmed = false
    private var cleanupDeadline: Long? = null
    private var cleanupBudgetMillis = 0L
    private var callWake: CountDownLatch? = null
    private var bindingStarted = false
    private var inFlight = false
    private var pendingReply: ReceivedFdInput? = null
    private var connectedRecovery = false
    private var closeRecovery = false
    private class RecoveryStart(val deadline: Long) {
        val done = CountDownLatch(1)
        @Volatile var accepted = false
    }
    private var recoveryStart: RecoveryStart? = null

    init { coordinator.registerConnectedRecovery { beginConnectedRecovery(identity, pendingCancel = true) } }

    override fun beginConnectedCancelRecovery(identity: WorkerIdentityV1): Boolean = beginConnectedRecovery(identity, pendingCancel = true)

    private fun beginConnectedRecovery(identity: WorkerIdentityV1, pendingCancel: Boolean): Boolean {
        if (identity != this.identity) return false
        beforeConnectedRecoveryAdmission()
        var first = false
        var useCloseRecovery = false
        val start = synchronized(lock) {
            if (state == State.CLOSING || (state == State.RECOVERY_PENDING && closeRecovery)) {
                useCloseRecovery = true
                return@synchronized null
            }
            if (state == State.RECOVERY_PENDING && !connectedRecovery) return recoveryIntent?.requested() == true
            if (state == State.RECOVERY_PENDING && connectedRecovery) return@synchronized recoveryStart
            val permitted = (!cancelled && !cancelPending) || (pendingCancel && cancelled)
            if ((state != State.READY && (state != State.OPENING || !bindingStarted)) || !permitted) null else {
                val now = nowMillis()
                val deadline = if (now >= 0 && cleanupBudgetMillis > 0 && now <= Long.MAX_VALUE - cleanupBudgetMillis) {
                    now + cleanupBudgetMillis
                } else return@synchronized null
                if (recoveryIntent?.request(identity.sessionGeneration) == false) return@synchronized null
                first = true
                state = State.RECOVERY_PENDING
                connectedRecovery = true
                cancelled = true
                val value = RecoveryStart(deadline).also { recoveryStart = it }
                callWake?.countDown()
                value
            }
        }
        if (useCloseRecovery) return beginCloseRecovery(identity)
        if (start == null) return false
        if (!first) return true
        val scheduled = try {
            scheduleRecoveryStart {
                val active = synchronized(lock) { state == State.RECOVERY_PENDING && recoveryStart === start }
                if (!active) return@scheduleRecoveryStart
                val started = try { onPreconnectTimeout() } catch (_: Exception) { false } catch (_: LinkageError) { false }
                val accepted = synchronized(lock) { started && state == State.RECOVERY_PENDING && recoveryStart === start }
                start.accepted = accepted
                start.done.countDown()
                if (!accepted) failClosed()
            }
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!scheduled) { start.done.countDown(); failClosed() }
        return scheduled
    }

    override fun beginCloseRecovery(identity: WorkerIdentityV1): Boolean {
        if (identity != this.identity) return false
        synchronized(lock) {
            if (state == State.RECOVERY_PENDING) return (closeRecovery || connectedRecovery) && recoveryIntent?.requested() == true
            if (state != State.READY && state != State.CLOSING && state != State.RETIRED) return false
            val intent = recoveryIntent ?: return false
            if (!intent.request(identity.sessionGeneration)) return false
            state = State.RECOVERY_PENDING
            closeRecovery = true
            cancelled = true
            callWake?.countDown()
        }
        val scheduled = try {
            scheduleRecoveryStart {
                val active = synchronized(lock) { state == State.RECOVERY_PENDING && closeRecovery }
                if (!active) return@scheduleRecoveryStart
                val started = try { onCloseRecovery() } catch (_: Exception) { false } catch (_: LinkageError) { false }
                if (!started) failClosed()
            }
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!scheduled) failClosed()
        return scheduled
    }

    private fun recoveryWaitDeadline(): Long = synchronized(lock) {
        recoveryStart?.deadline ?: cleanupDeadline ?: run {
            val now = nowMillis()
            if (now < 0 || cleanupBudgetMillis <= 0 || now > Long.MAX_VALUE - cleanupBudgetMillis) -1L
            else now + cleanupBudgetMillis
        }
    }

    private fun awaitRecoveryStart(start: RecoveryStart, deadline: Long): Boolean {
        val wait = remaining(minOf(deadline, start.deadline))
        val accepted = try {
            val signalled = wait > 0 && start.done.await(wait, TimeUnit.MILLISECONDS)
            val after = nowMillis()
            signalled && after >= 0 && after < minOf(deadline, start.deadline) && start.accepted &&
                synchronized(lock) { state == State.RECOVERY_PENDING && recoveryStart === start }
        }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        return accepted
    }

    private fun awaitRecoveryStartIfNeeded(deadline: Long): Boolean {
        val start = synchronized(lock) { if (state == State.RECOVERY_PENDING && connectedRecovery) recoveryStart else null }
        if (start == null) return true
        beforeRecoveryWait()
        return awaitRecoveryStart(start, deadline)
    }

    override fun openUntil(
        identity: WorkerIdentityV1,
        network: SearchNetworkV1,
        lease: SearchLeaseDuplicateV1,
        configuration: PrivateInputV1,
        startupDeadlineElapsedMillis: Long,
        cleanupMillis: Long,
    ): WorkerOpenV2 {
        val frame = configuration as? FrameInput ?: return WorkerOpenV2.Fatal(WorkerFatalV2.CAPABILITY_UNAVAILABLE)
        if (identity != this.identity || network != this.network || lease !== duplicate ||
            cleanupMillis !in 1..MAX_CLEANUP_MILLIS || !validFrame(frame)
        ) return WorkerOpenV2.Fatal(WorkerFatalV2.CAPABILITY_UNAVAILABLE)
        val deadline = minOf(startupDeadlineElapsedMillis, frame.deadlineElapsedMillis)
        val reservation = synchronized(lock) {
            when {
                state != State.NEW -> 0
                cancelled -> {
                    state = State.CLOSING
                    1
                }
                else -> {
                    state = State.OPENING
                    cleanupBudgetMillis = cleanupMillis
                    2
                }
            }
        }
        if (reservation == 0) return WorkerOpenV2.Fatal(WorkerFatalV2.BUSY)
        if (reservation == 1) {
            val end = synchronized(lock) { cleanupDeadline } ?: return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY).also { failClosed() }
            val closed = bounded(end, {}) { duplicate.close(); true }
            val retired = synchronized(lock) {
                if (closed != true || state != State.CLOSING) false else {
                    state = State.RETIRED
                    true
                }
            }
            return if (retired) WorkerOpenV2.Interrupted(WorkerInterruptedV2.CANCELLED)
            else WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY).also { failClosed() }
        }
        val openTicket = localDrain.begin(identity.sessionGeneration)
            ?: return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY).also { failClosed() }
        val openResult = bounded(deadline, {}, openTicket, { timedOut -> failOpen(deadline, timedOut) },
            { it == OpenDispatchResult.WaitExpired || it == OpenDispatchResult.RecoveryStopped }) {
            try { OpenDispatchResult.Status(trackedPreconnectOpen(openTicket) { resourceTicket -> trackedWorkerResource(resourceTicket, {
                stageFrameOverride?.invoke(frame, deadline) ?: AndroidStagedWorkerFrame(stageFrame(frame, deadline))
            }, { it.close() }, { it is WorkerFrameDeadlineBeforeAcquire }) { descriptor ->
                val bindDecision = synchronized(lock) {
                    when {
                        state != State.OPENING -> 0
                        cancelled -> 1
                        else -> {
                            bindingStarted = true
                            2
                        }
                    }
                }
                if (bindDecision == 1) {
                    duplicate.close()
                    return@trackedWorkerResource CANCELLED_BEFORE_BIND
                }
                if (bindDecision != 2) throw IOException("worker_admission_unknown")
                if (!coordinator.bind()) {
                    try { reportBindFailure() } catch (_: Exception) { } catch (_: LinkageError) { }
                    throw IOException("worker_admission_unknown")
                }
                val binder = when (val observation = coordinator.awaitConfirmation(deadline)) {
                    is WorkerConfirmationWait.Confirmed -> observation.binder
                    is WorkerConfirmationWait.DeathPending -> throw WorkerConfirmedDeathPending()
                    is WorkerConfirmationWait.NotAlive -> throw WorkerBinderNotAlive()
                    WorkerConfirmationWait.Deadline -> throw ExpectedPreconnectWaitStop()
                    WorkerConfirmationWait.SupervisedStop -> {
                        if (synchronized(lock) { state == State.RECOVERY_PENDING }) throw ExpectedConnectedRecovery()
                        throw IOException("worker_admission_unknown")
                    }
                    WorkerConfirmationWait.Unknown -> throw IOException("worker_admission_unknown")
                }
                afterOpenBinder()
                requireTime(deadline)
                val dispatch = synchronized(lock) {
                    when {
                        state == State.RECOVERY_PENDING -> 2
                        state == State.OPENING && !cancelled -> 1
                        else -> 0
                    }
                }
                if (dispatch == 2) throw ExpectedConnectedRecovery()
                if (dispatch != 1) throw IOException("worker_delivery_unknown")
                val code = statusTransaction(binder, SearchWorkerIpcV2.OPEN_FD, openTicket) { request ->
                    request.writeWorkerIdentity(identity, network)
                    request.writeLong(startupDeadlineElapsedMillis)
                    request.writeLong(cleanupMillis)
                    request.writeLong(frame.length)
                    request.writeTypedObject(descriptor.descriptor(), 0)
                }
                requireTime(deadline)
                if (!binderStillConfirmedForCall(binder)) throw IOException("worker_owner_unknown")
                code
            } }) } catch (_: ExpectedPreconnectWaitStop) { OpenDispatchResult.WaitExpired }
            catch (_: ExpectedConnectedRecovery) { OpenDispatchResult.RecoveryStopped }
            catch (_: WorkerRemoteDeliveryFailure) { OpenDispatchResult.RemoteFailure }
            catch (_: WorkerConfirmedDeathPending) { OpenDispatchResult.RemoteFailure }
            catch (_: WorkerBinderNotAlive) { OpenDispatchResult.RemoteFailure }
        } ?: return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        val status = when (openResult) {
            is OpenDispatchResult.Status -> openResult.value
            OpenDispatchResult.WaitExpired -> {
                failOpen(deadline, true)
                return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
            }
            OpenDispatchResult.RecoveryStopped -> return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
            OpenDispatchResult.RemoteFailure -> {
                try { reportServiceFailure(WorkerFatalV2.UNKNOWN_DELIVERY) } catch (_: Exception) { } catch (_: LinkageError) { }
                if (!beginConnectedRecovery(identity, pendingCancel = true)) failClosed()
                return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY, observedStatusReported = true)
            }
        }
        if (synchronized(lock) { state == State.RECOVERY_PENDING }) return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        if (status == CANCELLED_BEFORE_BIND) {
            val retired = synchronized(lock) {
                if (state != State.OPENING || !cancelled || bindingStarted) false else {
                    state = State.RETIRED
                    true
                }
            }
            return if (retired) WorkerOpenV2.Interrupted(WorkerInterruptedV2.CANCELLED)
            else WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY).also { failClosed() }
        }
        if (status.toLong() != Mobile.SearchWorkerReady) {
            val observedFailure = when (status.toLong()) {
                Mobile.SearchWorkerOwnerLost -> WorkerFatalV2.OWNER_LOST
                Mobile.SearchWorkerCleanupIncomplete -> WorkerFatalV2.CLEANUP_INCOMPLETE
                Mobile.SearchWorkerDied -> WorkerFatalV2.WORKER_DIED
                Mobile.SearchWorkerCapabilityUnavailable -> WorkerFatalV2.CAPABILITY_UNAVAILABLE
                else -> null
            }
            var observationReported = false
            if (observedFailure != null) try { reportServiceFailure(observedFailure); observationReported = true } catch (_: Exception) { } catch (_: LinkageError) { }
            serviceObservation(status.toLong())?.let { observation ->
                try { reportServiceObservation(observation); observationReported = true } catch (_: Exception) { } catch (_: LinkageError) { }
            }
            val known = candidateOpenAfterRetirement(status.toLong(), reportRetirementFailure) {
                val canRetire = synchronized(lock) {
                    if (state != State.OPENING || cancelled || cancelPending) false else {
                        state = State.READY
                        true
                    }
                }
                if (!canRetire) return@candidateOpenAfterRetirement false
                val now = nowMillis()
                val cleanupDeadline = try { minOf(deadline, Math.addExact(now, cleanupMillis)) } catch (_: ArithmeticException) { return@candidateOpenAfterRetirement false }
                now >= 0 && cleanupDeadline > now && closeUntil(identity, cleanupDeadline) == WorkerCloseV1.CLEAN_AND_DEAD
            }
            if (known != null) {
                if (known is WorkerOpenV2.Fatal) failClosed()
                return known
            }
            failClosed()
            return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY, observedStatusReported = observationReported)
        }
        val ready = synchronized(lock) {
            if (state != State.OPENING || cancelled) false else {
                state = State.READY
                true
            }
        }
        if (!ready) {
            failClosed()
            return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        }
        return WorkerOpenV2.Ready
    }

    override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 {
        val result = executeUnchecked(identity, operation, input)
        if (!awaitRecoveryStartIfNeeded(recoveryWaitDeadline())) {
            discardReplyAsync(result)
            return if (result == WorkerReplyV1.Quarantined) result else WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
        }
        val stateAfter = synchronized(lock) { state }
        if (stateAfter == State.RECOVERY_PENDING) {
            discardReplyAsync(result)
            return WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
        }
        if (stateAfter == State.QUARANTINED) {
            discardReplyAsync(result)
            return WorkerReplyV1.Quarantined
        }
        return result
    }

    private fun executeUnchecked(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 {
        val frame = input as? FrameInput ?: return WorkerReplyV1.Quarantined.also { failClosed() }
        if (identity != this.identity || !validFrame(frame)) return WorkerReplyV1.Quarantined.also { failClosed() }
        val reservation = synchronized(lock) {
            when {
                state == State.RECOVERY_PENDING -> 2
                state != State.READY || inFlight || pendingReply != null || cancelled -> 0
                else -> { inFlight = true; 1 }
            }
        }
        if (reservation == 2) return WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
        if (reservation != 1) return WorkerReplyV1.Quarantined.also { failClosed() }
        val callTicket = localDrain.begin(identity.sessionGeneration)
            ?: return if (synchronized(lock) { state == State.RECOVERY_PENDING }) WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
            else WorkerReplyV1.Quarantined.also { failClosed() }
        val operationDeadline = frame.deadlineElapsedMillis
        var fencedWait = false
        var remoteFailure = false
        val reply = awaitCall(operationDeadline, ::discardReply, callTicket, { fencedWait = true }, { remoteFailure = true }) {
            var result: WorkerReplyV1? = null
            try {
                trackedWorkerResource(callTicket, {
                    stageFrameOverride?.invoke(frame, operationDeadline) ?: AndroidStagedWorkerFrame(stageFrame(frame, operationDeadline))
                }, { it.close() }, { it is WorkerFrameDeadlineBeforeAcquire }) { descriptor ->
                    val binder = confirmedBinderForCall()
                    afterCallBinder()
                    try {
                        requireTime(operationDeadline)
                    } catch (_: WorkerFrameDeadline) {
                        return@awaitCall WorkerReplyV1.Interrupted(WorkerInterruptedV2.DEADLINE)
                    }
                    val dispatch = synchronized(lock) {
                        when {
                            state == State.RECOVERY_PENDING -> 3
                            state != State.READY || !inFlight -> 0
                            cancelled -> 1
                            else -> 2
                        }
                    }
                    if (dispatch == 3) throw ExpectedConnectedRecovery()
                    if (dispatch == 0) throw IOException("worker_delivery_unknown")
                    val produced = if (dispatch == 1) WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
                    else callTransactionOverride?.let { override ->
                        workerTransact { override(binder, operation, frame, callTicket) }
                    }
                        ?: callTransaction(binder, operation, frame, descriptor.descriptor(), callTicket)
                    result = produced
                    if (!binderStillConfirmedForCall(binder)) {
                        discardReply(produced)
                        result = null
                        throw IOException("worker_owner_unknown")
                    }
                    produced
                }
            } catch (_: WorkerFrameDeadline) {
                WorkerReplyV1.Interrupted(WorkerInterruptedV2.DEADLINE)
            } catch (failure: Throwable) {
                result?.let(::discardReply)
                throw failure
            }
        } ?: return when {
            remoteFailure && (synchronized(lock) { state == State.RECOVERY_PENDING } || beginConnectedRecovery(identity, pendingCancel = true)) ->
                WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
            remoteFailure -> WorkerReplyV1.Quarantined.also { failClosed() }
            fencedWait -> WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
            else -> WorkerReplyV1.Quarantined
        }
        if (nowMillis() < 0) {
            discardReplyAsync(reply)
            return WorkerReplyV1.Quarantined.also { failClosed() }
        }
        var disposed: WorkerReplyV1 = reply
        while (true) {
            val finishedAt = nowMillis()
            val disposition = synchronized(lock) {
                val overflow = finishedAt >= operationDeadline &&
                    (cleanupBudgetMillis <= 0 || operationDeadline > Long.MAX_VALUE - cleanupBudgetMillis)
                if (finishedAt >= operationDeadline && !overflow) {
                    tightenCleanupDeadline(operationDeadline + cleanupBudgetMillis)
                }
                when {
                    finishedAt < 0 || overflow || disposed == WorkerReplyV1.Quarantined ||
                        (cleanupDeadline != null && finishedAt >= cleanupDeadline!!) -> {
                        state = State.QUARANTINED
                        ownerLease.quarantine()
                        CallDisposition.UNKNOWN
                    }
                    state == State.RECOVERY_PENDING -> CallDisposition.RECOVERY
                    state != State.READY -> {
                        state = State.QUARANTINED
                        ownerLease.quarantine()
                        CallDisposition.UNKNOWN
                    }
                    disposed is WorkerReplyV1.Complete && cancelled -> CallDisposition.CANCELLED
                    disposed is WorkerReplyV1.Complete && finishedAt >= operationDeadline -> CallDisposition.DEADLINE
                    else -> {
                        inFlight = false
                        CallDisposition.ACCEPT
                    }
                }
            }
            when (disposition) {
                CallDisposition.ACCEPT -> return disposed
                CallDisposition.RECOVERY -> {
                    discardReplyAsync(disposed)
                    return WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
                }
                CallDisposition.UNKNOWN -> {
                    discardReplyAsync(disposed)
                    return WorkerReplyV1.Quarantined.also { failClosed() }
                }
                CallDisposition.DEADLINE, CallDisposition.CANCELLED -> {
                    val input = (disposed as WorkerReplyV1.Complete).result
                    val closed = awaitCall(operationDeadline, {}) { input.close(); true }
                    if (closed != true) return WorkerReplyV1.Quarantined
                    disposed = WorkerReplyV1.Interrupted(
                        if (disposition == CallDisposition.CANCELLED) WorkerInterruptedV2.CANCELLED else WorkerInterruptedV2.DEADLINE,
                    )
                }
            }
        }
    }

    override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
        var failure: Throwable? = null
        try {
            cancelUntilUnchecked(identity, cleanupDeadlineElapsedMillis)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (!awaitRecoveryStartIfNeeded(recoveryWaitDeadline()) && failure == null) throw ConnectedCancelRecoveryPending()
        }
    }

    private fun cancelUntilUnchecked(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
        if (identity != this.identity) {
            failClosed()
            throw IOException("worker_identity_unknown")
        }
        if (synchronized(lock) { if (state == State.RECOVERY_PENDING) { cancelled = true; true } else false }) return
        if (remaining(cleanupDeadlineElapsedMillis) <= 0) {
            failClosed()
            throw IOException("worker_deadline")
        }
        var conflict = false
        var unavailable = false
        var cancelTicket: SearchWorkerLocalDrain.Ticket? = null
        var effectiveDeadline = cleanupDeadlineElapsedMillis
        val send = synchronized(lock) {
            if (state == State.RECOVERY_PENDING) { cancelled = true; return }
            cancelled = true
            effectiveDeadline = tightenCleanupDeadline(cleanupDeadlineElapsedMillis)
            if (cancelPending) {
                conflict = true
                false
            } else if (cancelConfirmed) {
                false
            } else {
                val needed = when (state) {
                    State.NEW, State.CLOSING, State.RETIRED -> false
                    State.OPENING -> bindingStarted
                    State.READY -> true
                    State.RECOVERY_PENDING, State.QUARANTINED -> throw IOException("worker_delivery_unknown")
                }
                if (needed) {
                    cancelTicket = localDrain.begin(identity.sessionGeneration)
                    if (cancelTicket == null) unavailable = true else cancelPending = true
                } else cancelConfirmed = true
                needed && !unavailable
            }
        }
        if (conflict || unavailable) {
            failClosed()
            throw IOException("worker_delivery_unknown")
        }
        if (!send) return
        val custody = checkNotNull(cancelTicket)
        var completed = false
        try {
            val task = custody.retain() ?: throw IOException("worker_drain_unknown")
            var deferred = false
            val result = bounded(effectiveDeadline, {}, task, onFailure = { timedOut ->
                deferred = timedOut && (synchronized(lock) { state == State.RECOVERY_PENDING } ||
                    beginConnectedRecovery(identity, pendingCancel = true))
                if (!deferred) failClosed()
            }, expectedTimeout = { it == CancelDispatchResult.RecoveryStopped }) {
                try {
                    trackedPreconnectCancel(task) {
                        val binder = when (val observation = coordinator.awaitConfirmation(effectiveDeadline)) {
                            is WorkerConfirmationWait.Confirmed -> observation.binder
                            is WorkerConfirmationWait.DeathPending -> throw WorkerConfirmedDeathPending()
                            is WorkerConfirmationWait.NotAlive -> throw WorkerBinderNotAlive()
                            WorkerConfirmationWait.SupervisedStop -> {
                                if (synchronized(lock) { state == State.RECOVERY_PENDING }) throw ExpectedPreconnectWaitStop()
                                throw IOException("worker_owner_unknown")
                            }
                            WorkerConfirmationWait.Deadline, WorkerConfirmationWait.Unknown -> throw IOException("worker_owner_unknown")
                        }
                        requireTime(effectiveDeadline)
                        val status = try {
                            statusTransaction(binder, SearchWorkerIpcV2.CANCEL_ROUND, task) { it.writeWorkerIdentity(identity, network) }
                        } catch (_: WorkerRemoteDeliveryFailure) { return@trackedPreconnectCancel CancelDispatchResult.DeliveryUnknown }
                        requireTime(effectiveDeadline)
                        if (status.toLong() != Mobile.SearchWorkerReady) CancelDispatchResult.DeliveryUnknown
                        else CancelDispatchResult.Status(true)
                    }
                } catch (_: ExpectedPreconnectWaitStop) { CancelDispatchResult.RecoveryStopped }
                catch (_: WorkerConfirmedDeathPending) { CancelDispatchResult.DeliveryUnknown }
                catch (_: WorkerBinderNotAlive) { CancelDispatchResult.DeliveryUnknown }
            }
            if (result == null && deferred) {
                completed = true
                if (synchronized(lock) { connectedRecovery }) throw ConnectedCancelRecoveryPending()
                return
            }
            if (result == CancelDispatchResult.DeliveryUnknown && beginConnectedRecovery(identity, pendingCancel = true)) {
                completed = true
                throw ConnectedCancelRecoveryPending()
            }
            if (result == CancelDispatchResult.RecoveryStopped) {
                val fenced = synchronized(lock) {
                    if (state == State.RECOVERY_PENDING) { cancelPending = false; true } else false
                }
                if (fenced) { completed = true; return }
            }
            if (result !is CancelDispatchResult.Status || !result.confirmed) {
                failClosed()
                throw IOException("worker_delivery_unknown")
            }
            val accepted = synchronized(lock) {
                if (state == State.QUARANTINED) false else {
                    cancelPending = false
                    cancelConfirmed = true
                    true
                }
            }
            if (!accepted) throw IOException("worker_delivery_unknown")
            completed = true
        } finally {
            try { if (!completed) failClosed() } finally { custody.closed(completed) }
        }
    }

    override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 {
        if (identity != this.identity) return WorkerCloseV1.QUARANTINED.also { failClosed() }
        beforeCloseAdmission()
        var effectiveDeadline = cleanupDeadlineElapsedMillis
        val disposition = synchronized(lock) {
            when {
                state == State.RECOVERY_PENDING -> return WorkerCloseV1.QUARANTINED
                state == State.RETIRED -> 1
                state != State.READY || inFlight || pendingReply != null || cancelPending -> 0
                else -> {
                    effectiveDeadline = tightenCleanupDeadline(cleanupDeadlineElapsedMillis)
                    state = State.CLOSING
                    2
                }
            }
        }
        if (disposition == 1) return WorkerCloseV1.CLEAN_AND_DEAD
        if (disposition != 2) return WorkerCloseV1.QUARANTINED.also { failClosed() }
        val closeTicket = localDrain.begin(identity.sessionGeneration)
            ?: return if (synchronized(lock) { state == State.RECOVERY_PENDING }) WorkerCloseV1.QUARANTINED
            else WorkerCloseV1.QUARANTINED.also { failClosed() }
        val retired = bounded(effectiveDeadline, {}, closeTicket, onFailure = { timedOut ->
            val now = nowMillis()
            val pending = synchronized(lock) { state == State.RECOVERY_PENDING }
            if (!pending && !(now >= 0 && (timedOut || now >= effectiveDeadline) && beginCloseRecovery(identity))) failClosed()
        }, expectedTimeout = { it == CloseDispatchResult.WaitExpired }) {
            try {
                trackedCloseOperation(closeTicket) {
                    val binder = synchronized(lock) {
                        if (state == State.RECOVERY_PENDING) throw ExpectedConnectedRecovery()
                        when (val observation = coordinator.observeBinder()) {
                            is WorkerBinderObservation.Confirmed -> observation.binder
                            is WorkerBinderObservation.DeathPending -> throw WorkerConfirmedDeathPending()
                            is WorkerBinderObservation.NotAlive -> throw WorkerBinderNotAlive()
                            WorkerBinderObservation.Unknown -> throw IOException("worker_owner_unknown")
                        }
                    }
                    requireTime(effectiveDeadline)
                    val status = closeTransactionOverride?.let { override -> workerTransact { override(binder, closeTicket) } }
                        ?: statusTransaction(binder, SearchWorkerIpcV2.CLOSE_ROUND, closeTicket) {
                            it.writeWorkerIdentity(identity, network)
                            it.writeLong(effectiveDeadline)
                        }
                    if (status.toLong() != Mobile.SearchWorkerReady) throw IOException("worker_cleanup_unknown")
                    requireTime(effectiveDeadline)
                    if (synchronized(lock) { state == State.RECOVERY_PENDING }) throw ExpectedConnectedRecovery()
                    coordinator.stop()
                    if (!coordinator.awaitRetired(effectiveDeadline)) {
                        if (nowMillis() >= effectiveDeadline) CloseDispatchResult.WaitExpired else CloseDispatchResult.Unknown
                    } else {
                        requireTime(effectiveDeadline)
                        CloseDispatchResult.Retired
                    }
                }
            } catch (_: ExpectedConnectedRecovery) { CloseDispatchResult.RecoveryStopped }
            catch (_: WorkerRemoteDeliveryFailure) { CloseDispatchResult.RemoteFailure }
            catch (_: WorkerConfirmedDeathPending) { CloseDispatchResult.RemoteFailure }
            catch (_: WorkerBinderNotAlive) { CloseDispatchResult.RemoteFailure }
            catch (_: WorkerFrameDeadline) { CloseDispatchResult.WaitExpired }
        }
        if (retired == CloseDispatchResult.RemoteFailure && !beginCloseRecovery(identity)) failClosed()
        val completed = synchronized(lock) {
            if (retired != CloseDispatchResult.Retired || state != State.CLOSING) false else {
                state = State.RETIRED
                true
            }
        }
        if (!completed) return if (synchronized(lock) { state == State.RECOVERY_PENDING }) WorkerCloseV1.QUARANTINED
        else WorkerCloseV1.QUARANTINED.also { failClosed() }
        return WorkerCloseV1.CLEAN_AND_DEAD
    }

    private fun validFrame(frame: FrameInput): Boolean =
        provenFrameBound >= FRAME_HEADER_BYTES && provenReplyBound > 0 && frame.length in FRAME_HEADER_BYTES.toLong()..provenFrameBound.toLong() &&
            frame.deadlineElapsedMillis > 0

    private fun stageFrame(frame: FrameInput, deadline: Long): ParcelFileDescriptor {
        try { requireTime(deadline) } catch (_: WorkerFrameDeadline) { throw WorkerFrameDeadlineBeforeAcquire() }
        val raw = Os.memfd_create("search-frame", OsConstants.MFD_CLOEXEC)
        var duplicate: ParcelFileDescriptor? = null
        var failure: Throwable? = null
        try {
            val buffer = ByteArray(FRAME_BUFFER_BYTES)
            try {
                frame.open().use { input ->
                    var count = 0L
                    while (count < frame.length) {
                        requireTime(deadline)
                        val wanted = minOf(buffer.size.toLong(), frame.length - count).toInt()
                        val read = input.read(buffer, 0, wanted)
                        if (read <= 0 || read > wanted) throw IOException("worker_frame_invalid")
                        var offset = 0
                        while (offset < read) {
                            requireTime(deadline)
                            val written = Os.write(raw, buffer, offset, read - offset)
                            if (written <= 0 || written > read - offset) throw IOException("worker_frame_invalid")
                            offset += written
                        }
                        count += read
                    }
                    if (input.read() != -1) throw IOException("worker_frame_invalid")
                }
            } finally { buffer.fill(0) }
            val stat = Os.fstat(raw)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != frame.length) throw IOException("worker_frame_invalid")
            Os.lseek(raw, 0, OsConstants.SEEK_SET)
            requireTime(deadline)
            duplicate = ParcelFileDescriptor.dup(raw)
        } catch (t: Throwable) {
            failure = t
        }
        try {
            Os.close(raw)
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            failure = IOException("worker_fd_close_unknown")
        }
        if (failure != null) {
            try { duplicate?.close() } catch (_: Exception) { throw IOException("worker_fd_close_unknown") }
            throw failure
        }
        return duplicate ?: throw IOException("worker_fd_unknown")
    }

    private fun callTransaction(
        binder: IBinder,
        operation: WorkerOperationV1,
        frame: FrameInput,
        descriptor: ParcelFileDescriptor,
        operationTicket: SearchWorkerLocalDrain.Ticket,
    ): WorkerReplyV1 {
        val replyTicket = operationTicket.retain() ?: throw IOException("worker_drain_unknown")
        var received: ParcelFileDescriptor? = null
        var transferred = false
        try {
            val status = transaction(binder, SearchWorkerIpcV2.CALL_FD, operationTicket, { request ->
                request.writeWorkerIdentity(identity, network)
                request.writeInt(operationNumber(operation))
                request.writeLong(frame.length)
                request.writeTypedObject(descriptor, 0)
            }) { response ->
                val code = response.readInt()
                if (code.toLong() == Mobile.SearchWorkerReady) {
                    val length = response.readLong()
                    received = response.readTypedObject(ParcelFileDescriptor.CREATOR)
                    if (length !in 1..provenReplyBound.toLong() || received == null) throw IOException("worker_reply_invalid")
                    val stat = Os.fstat(received!!.fileDescriptor)
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != length) throw IOException("worker_reply_invalid")
                    Os.lseek(received!!.fileDescriptor, 0, OsConstants.SEEK_SET)
                }
                response.enforceNoDataAvail()
                code
            }
            val result = when (status.toLong()) {
                Mobile.SearchWorkerReady -> {
                    val input = ReceivedFdInput(received ?: throw IOException("worker_reply_invalid")) { value, closed ->
                        try { replyDisposed(value, closed) } finally { replyTicket.closed(closed) }
                    }
                    received = null
                    transferred = true
                    val accepted = synchronized(lock) {
                        if ((state != State.READY && state != State.RECOVERY_PENDING) || !inFlight || pendingReply != null) false else {
                            pendingReply = input
                            true
                        }
                    }
                    if (!accepted) {
                        input.close()
                        throw IOException("worker_reply_unknown")
                    }
                    WorkerReplyV1.Complete(input)
                }
                else -> nonReadyWorkerReply(status.toLong())
            }
            received = null
            return result
        } finally {
            if (!transferred) {
                var closed = false
                try {
                    received?.close()
                    closed = true
                } finally { replyTicket.closed(closed) }
            }
        }
    }

    private fun statusTransaction(
        binder: IBinder,
        code: Int,
        operationTicket: SearchWorkerLocalDrain.Ticket? = null,
        write: (Parcel) -> Unit,
    ): Int =
        statusTransactionOverride?.let { override -> workerTransact { override(binder, code, write) } }
            ?: transaction(binder, code, operationTicket, write) { response -> response.readInt().also { response.enforceNoDataAvail() } }

    private fun confirmedBinderForCall(): IBinder = synchronized(lock) {
        if (state == State.RECOVERY_PENDING) throw ExpectedConnectedRecovery()
        when (val observed = coordinator.observeBinder()) {
            is WorkerBinderObservation.Confirmed -> observed.binder
            is WorkerBinderObservation.DeathPending -> throw WorkerConfirmedDeathPending()
            is WorkerBinderObservation.NotAlive -> throw WorkerBinderNotAlive()
            WorkerBinderObservation.Unknown -> throw IOException("worker_owner_unknown")
        }
    }

    private fun binderStillConfirmedForCall(binder: IBinder): Boolean = synchronized(lock) {
        if (state == State.RECOVERY_PENDING) throw ExpectedConnectedRecovery()
        when (val observed = coordinator.observeBinder()) {
            is WorkerBinderObservation.Confirmed -> observed.binder === binder
            is WorkerBinderObservation.DeathPending -> if (observed.binder === binder) throw WorkerConfirmedDeathPending() else false
            is WorkerBinderObservation.NotAlive -> if (observed.binder === binder) throw WorkerBinderNotAlive() else false
            WorkerBinderObservation.Unknown -> false
        }
    }

    private fun <T> transaction(
        binder: IBinder,
        code: Int,
        operationTicket: SearchWorkerLocalDrain.Ticket?,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        var request: Parcel? = null
        var response: Parcel? = null
        return trackedWorkerTransaction(operationTicket, action = {
            request = Parcel.obtain()
            response = Parcel.obtain()
            val acquiredRequest = checkNotNull(request)
            val acquiredResponse = checkNotNull(response)
            acquiredRequest.writeInterfaceToken(SearchWorkerIpcV2.DESCRIPTOR)
            write(acquiredRequest)
            if (!workerTransact { binder.transact(code, acquiredRequest, acquiredResponse, 0) }) throw IOException("worker_delivery_unknown")
            acquiredResponse.readException()
            read(acquiredResponse)
        }, recycle = {
            try { request?.recycle() } finally { response?.recycle() }
            request != null && response != null
        })
    }

    // Called only while holding lock. A later cleanup request can shorten, never extend, the budget.
    private fun tightenCleanupDeadline(proposed: Long): Long {
        val previous = cleanupDeadline
        val chosen = if (previous == null) proposed else minOf(previous, proposed)
        if (previous == null || chosen < previous) {
            cleanupDeadline = chosen
            callWake?.countDown()
        }
        return chosen
    }

    private fun <T : Any> awaitCall(
        operationDeadline: Long,
        discard: (T) -> Unit,
        ticket: SearchWorkerLocalDrain.Ticket? = null,
        onFenced: () -> Unit = {},
        onRemoteFailure: () -> Unit = {},
        action: () -> T,
    ): T? {
        val dispatch = ticket?.let(::SearchWorkerDispatch)
        val resultLock = Any()
        var completed = false
        var abandoned = false
        var result: T? = null
        var recoveryStopped = false
        var remoteFailed = false
        var installedWake: CountDownLatch? = null

        fun abandon(fenced: Boolean = false, remoteFailure: Boolean = false): T? {
            val stale = synchronized(resultLock) {
                abandoned = true
                result.also { result = null }
            }
            dispatch?.cancelBeforeClaim()
            if (!fenced && !remoteFailure) failClosed()
            if (fenced) onFenced()
            if (remoteFailure) onRemoteFailure()
            if (stale != null) {
                try {
                    Thread({ try { discard(stale) } catch (_: Exception) { } }, "search-worker-discard")
                        .apply { isDaemon = true }.start()
                } catch (_: Exception) { }
            }
            return null
        }

        try {
            Thread({
                if (dispatch != null && !dispatch.claim()) return@Thread
                val outcomeTicket = ticket?.retain()
                var stopped = false
                var remote = false
                var known = false
                val value = try {
                    if (ticket != null && outcomeTicket == null) throw IOException("worker_drain_unknown")
                    action().also { known = true }
                } catch (_: ExpectedConnectedRecovery) {
                    stopped = true
                    known = true
                    null
                } catch (_: WorkerRemoteDeliveryFailure) {
                    remote = true
                    known = true
                    try { reportServiceFailure(WorkerFatalV2.UNKNOWN_DELIVERY) } catch (_: Exception) { } catch (_: LinkageError) { }
                    null
                } catch (_: WorkerConfirmedDeathPending) {
                    remote = true
                    known = true
                    try { reportServiceFailure(WorkerFatalV2.WORKER_DIED) } catch (_: Exception) { } catch (_: LinkageError) { }
                    null
                } catch (_: WorkerBinderNotAlive) {
                    remote = true
                    known = true
                    try { reportServiceFailure(WorkerFatalV2.UNKNOWN_DELIVERY) } catch (_: Exception) { } catch (_: LinkageError) { }
                    null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } catch (_: Exception) { null } catch (_: LinkageError) { null }
                try {
                    val stale = synchronized(resultLock) {
                        if (abandoned) value else {
                            result = value
                            completed = true
                            recoveryStopped = stopped
                            remoteFailed = remote
                            null
                        }
                    }
                    afterCallOutcome()
                    synchronized(lock) { callWake?.countDown() }
                    if (stale != null) discard(stale)
                } catch (_: Exception) {
                    known = false
                } catch (_: LinkageError) {
                    known = false
                } finally {
                    outcomeTicket?.closed(known)
                }
            }, "search-worker-call").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            dispatch?.cancelBeforeClaim()
            return abandon()
        } catch (_: LinkageError) {
            dispatch?.cancelBeforeClaim()
            return abandon()
        }

        try {
            while (true) {
                val now = nowMillis()
                if (now < 0) return abandon()
                if (now >= operationDeadline) {
                    val budget = synchronized(lock) { cleanupBudgetMillis }
                    if (budget <= 0 || operationDeadline > Long.MAX_VALUE - budget) return abandon()
                    synchronized(lock) { tightenCleanupDeadline(operationDeadline + budget) }
                }
                val snapshot = synchronized(lock) { state to cleanupDeadline }
                if (snapshot.first == State.QUARANTINED) return abandon()
                if (snapshot.first == State.RECOVERY_PENDING) {
                    val outcome = synchronized(resultLock) {
                        when {
                            !completed || result != null || recoveryStopped -> 1
                            remoteFailed -> 2
                            else -> 0
                        }
                    }
                    return abandon(fenced = outcome == 1, remoteFailure = outcome == 2)
                }
                afterCallSnapshot()
                val limit = if (now < operationDeadline) minOf(operationDeadline, snapshot.second ?: operationDeadline)
                else snapshot.second ?: return abandon()
                if (now >= limit) return abandon()

                var done = false
                var value: T? = null
                var stopped = false
                var remote = false
                val wake = synchronized(resultLock) {
                    if (completed) {
                        done = true
                        value = result
                        stopped = recoveryStopped
                        remote = remoteFailed
                        result = null
                        null
                    } else {
                        synchronized(lock) {
                            CountDownLatch(1).also {
                                callWake = it
                                installedWake = it
                                if (state == State.QUARANTINED || state == State.RECOVERY_PENDING || cleanupDeadline != snapshot.second) it.countDown()
                            }
                        }
                    }
                }
                if (done) return value ?: abandon(fenced = stopped, remoteFailure = remote)
                try {
                    wake!!.await(limit - now, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return abandon()
                }
            }
        } finally {
            synchronized(lock) { if (callWake === installedWake) callWake = null }
        }
    }

    private fun <T : Any> bounded(
        deadline: Long,
        discard: (T) -> Unit,
        ticket: SearchWorkerLocalDrain.Ticket? = null,
        onFailure: (Boolean) -> Unit = { failClosed() },
        expectedTimeout: (T) -> Boolean = { false },
        action: () -> T,
    ): T? {
        val dispatch = ticket?.let(::SearchWorkerDispatch)
        if (remaining(deadline) <= 0) {
            dispatch?.cancelBeforeClaim()
            onFailure(false)
            return null
        }
        val done = CountDownLatch(1)
        val resultLock = Any()
        var abandoned = false
        var completed = false
        var result: T? = null
        var resultCustody: SearchWorkerLocalDrain.Ticket? = null
        try {
            Thread({
                if (dispatch != null && !dispatch.claim()) { done.countDown(); return@Thread }
                val taskTicket = ticket?.retain()
                val disposition = taskTicket?.retain()
                if (ticket != null && (taskTicket == null || disposition == null)) {
                    disposition?.closed(false)
                    taskTicket?.closed(false)
                    ticket.closed(false)
                    done.countDown()
                    return@Thread
                }
                var taskComplete = false
                var dispositionTransferred = false
                try {
                    val value = try { action() } catch (_: Exception) { null } catch (_: LinkageError) { null }
                    val discardNow = synchronized(resultLock) {
                        if (abandoned) value else {
                            result = value
                            resultCustody = if (value != null) disposition else null
                            dispositionTransferred = value != null
                            completed = true
                            null
                        }
                    }
                    afterBoundedOutcome()
                    if (discardNow != null) {
                        var disposed = false
                        try { discard(discardNow); disposed = true } finally { disposition?.closed(disposed) }
                        dispositionTransferred = true
                    } else if (!dispositionTransferred) {
                        disposition?.closed(true)
                        dispositionTransferred = true
                    }
                    taskComplete = true
                } catch (_: Exception) { } catch (_: LinkageError) { }
                finally {
                    if (!dispositionTransferred) disposition?.closed(false)
                    taskTicket?.closed(taskComplete)
                    done.countDown()
                }
            }, "search-worker-ipc").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            dispatch?.cancelBeforeClaim()
            onFailure(false)
            return null
        } catch (_: LinkageError) {
            dispatch?.cancelBeforeClaim()
            onFailure(false)
            return null
        }
        var interrupted = false
        val waited = try {
            val remaining = remaining(deadline)
            remaining > 0 && awaitBounded(done, remaining)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            interrupted = true
            false
        }
        val timely = waited && remaining(deadline) > 0
        var discarded: T? = null
        var disposition: SearchWorkerLocalDrain.Ticket? = null
        var pending = false
        val accepted = synchronized(resultLock) {
            if (timely && completed && result != null) result.also {
                result = null
                disposition = resultCustody
                resultCustody = null
            } else {
                pending = !completed || (result?.let(expectedTimeout) == true)
                abandoned = true
                discarded = result
                disposition = resultCustody
                resultCustody = null
                result = null
                null
            }
        }
        if (accepted != null) disposition?.closed(true)
        if (accepted == null) {
            dispatch?.cancelBeforeClaim()
            val now = nowMillis()
            onFailure(!interrupted && pending && now >= 0 && now >= deadline)
            discarded?.let { value ->
                try {
                    Thread({
                        var disposed = false
                        try { discard(value); disposed = true } catch (_: Exception) { } catch (_: LinkageError) { }
                        finally { disposition?.closed(disposed) }
                    }, "search-worker-discard").apply { isDaemon = true }.start()
                } catch (_: Exception) { disposition?.closed(false) } catch (_: LinkageError) { disposition?.closed(false) }
            }
        }
        return accepted
    }

    private fun discardReply(reply: WorkerReplyV1): Unit {
        if (reply is WorkerReplyV1.Complete) reply.result.close()
    }

    private fun replyDisposed(input: ReceivedFdInput, confirmed: Boolean): Unit {
        val unknown = synchronized(lock) {
            if (!confirmed || pendingReply !== input) {
                state = State.QUARANTINED
                ownerLease.quarantine()
                true
            } else {
                pendingReply = null
                false
            }
        }
        if (unknown) coordinator.quarantine()
    }

    private fun discardReplyAsync(reply: WorkerReplyV1): Unit {
        try { Thread({ try { discardReply(reply) } catch (_: Exception) { } }, "search-worker-discard").apply { isDaemon = true }.start() }
        catch (_: Exception) { }
    }

    private fun failClosed(): Unit {
        synchronized(lock) {
            state = State.QUARANTINED
            ownerLease.quarantine()
            callWake?.countDown()
        }
        coordinator.quarantine()
        recoveryIntent?.let { intent ->
            try { onRecoveryFailure(intent) } catch (_: Exception) { } catch (_: LinkageError) { }
        }
    }

    private fun failOpen(deadline: Long, timedOut: Boolean): Unit {
        val now = nowMillis()
        if (timedOut && now >= 0 && now >= deadline && synchronized(lock) { state == State.RECOVERY_PENDING }) return
        handlePreconnectOpenFailure(timedOut, now, deadline, reserve = {
            synchronized(lock) {
                if (state != State.OPENING || !bindingStarted || (cancelled && !cancelPending) ||
                    recoveryIntent?.request(identity.sessionGeneration) == false) false else {
                    state = State.RECOVERY_PENDING
                    true
                }
            }
        }, start = {
            try { onPreconnectTimeout() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        }, fail = {
            synchronized(lock) { if (state == State.RECOVERY_PENDING) state = State.OPENING }
            failClosed()
        })
    }

    private fun remaining(deadline: Long): Long {
        val now = nowMillis()
        return if (now < 0 || deadline <= now) 0 else deadline - now
    }

    private fun requireTime(deadline: Long): Unit {
        val now = nowMillis()
        if (now < 0) throw IOException("worker_clock_unknown")
        if (deadline <= now) throw WorkerFrameDeadline()
    }
}

internal class ExpectedPreconnectWaitStop : IOException("worker_preconnect_stopped")
internal class ConnectedCancelRecoveryPending : IOException("worker_cancel_cleanup_pending")
internal class ExpectedConnectedRecovery : IOException("worker_connected_stopped")
internal class WorkerConfirmedDeathPending : IOException("worker_confirmed_death_pending")
internal class WorkerBinderNotAlive : IOException("worker_binder_not_alive")
internal class WorkerRemoteDeliveryFailure(cause: RemoteException) : IOException("worker_remote_delivery_unknown", cause)

private inline fun <T> trackedCloseOperation(ticket: SearchWorkerLocalDrain.Ticket, action: () -> T): T {
    var returned = false
    try {
        return action().also { returned = true }
    } catch (failure: ExpectedConnectedRecovery) { returned = true; throw failure }
    catch (failure: WorkerRemoteDeliveryFailure) { returned = true; throw failure }
    catch (failure: WorkerConfirmedDeathPending) { returned = true; throw failure }
    catch (failure: WorkerBinderNotAlive) { returned = true; throw failure }
    catch (failure: WorkerFrameDeadline) { returned = true; throw failure }
    finally { ticket.closed(returned) }
}

internal inline fun <T> workerTransact(action: () -> T): T = try { action() }
catch (failure: RemoteException) { throw WorkerRemoteDeliveryFailure(failure) }

internal inline fun <T> trackedPreconnectOpen(
    ticket: SearchWorkerLocalDrain.Ticket,
    action: (SearchWorkerLocalDrain.Ticket) -> T,
): T {
    var confirmed = false
    try {
        val resource = ticket.retain() ?: throw IOException("worker_resource_unknown")
        val result = action(resource)
        confirmed = true
        return result
    } catch (failure: ExpectedPreconnectWaitStop) {
        confirmed = true
        throw failure
    } catch (failure: ExpectedConnectedRecovery) {
        confirmed = true
        throw failure
    } catch (failure: WorkerRemoteDeliveryFailure) {
        confirmed = true
        throw failure
    } catch (failure: WorkerConfirmedDeathPending) {
        confirmed = true
        throw failure
    } catch (failure: WorkerBinderNotAlive) {
        confirmed = true
        throw failure
    } finally {
        ticket.closed(confirmed)
    }
}

internal inline fun <T> trackedPreconnectCancel(ticket: SearchWorkerLocalDrain.Ticket, action: () -> T): T {
    try {
        return action()
    } finally {
        ticket.closed(true)
    }
}

private open class WorkerFrameDeadline : IOException("worker_deadline")
private class WorkerFrameDeadlineBeforeAcquire : WorkerFrameDeadline()

internal fun operationNumber(operation: WorkerOperationV1): Int = when (operation) {
    WorkerOperationV1.CHECK -> Mobile.SearchWorkerCheck.toInt()
    WorkerOperationV1.UDP -> Mobile.SearchWorkerUDP.toInt()
    WorkerOperationV1.MEDIA -> Mobile.SearchWorkerMedia.toInt()
}

internal fun nonReadyWorkerReply(status: Long): WorkerReplyV1 = when (status) {
    Mobile.SearchWorkerCancelled -> WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
    Mobile.SearchWorkerDeadline -> WorkerReplyV1.Interrupted(WorkerInterruptedV2.DEADLINE)
    else -> WorkerReplyV1.Quarantined
}

private fun Parcel.writeWorkerIdentity(identity: WorkerIdentityV1, network: SearchNetworkV1): Unit {
    writeLong(identity.sessionGeneration)
    writeLong(identity.attempt.toLong())
    writeInt(identity.round)
    writeLong(identity.workerGeneration)
    writeLong(network.identity)
    writeLong(network.version)
}

private class ReceivedFdInput(
    private val descriptor: ParcelFileDescriptor,
    private val disposed: (ReceivedFdInput, Boolean) -> Unit,
) : PrivateInputV1 {
    private val lock = Any()
    private var stream: InputStream? = null
    private var closed = false
    private val closeOnce = LeaseCloseOnce {
        val owned = synchronized(lock) {
            closed = true
            stream
        }
        var confirmed = false
        try {
            if (owned == null) descriptor.close() else owned.close()
            confirmed = true
        } finally {
            disposed(this, confirmed)
        }
    }

    override fun open(): InputStream = synchronized(lock) {
        if (closed || stream != null) throw IOException("worker_reply_unavailable")
        val opened = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        stream = opened
        object : FilterInputStream(opened) {
            override fun close(): Unit = closeOnce.close()
        }
    }

    override fun close(): Unit = closeOnce.close()
}
