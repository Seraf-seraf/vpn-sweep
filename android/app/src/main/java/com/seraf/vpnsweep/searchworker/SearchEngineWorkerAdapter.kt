package com.seraf.vpnsweep.searchworker

import android.os.SystemClock
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.SearchEngineWorker
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressKindV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressOutcomeV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchWorkerPortV2
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2
import com.seraf.vpnsweep.searchprotocol.WorkerCloseV1
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import com.seraf.vpnsweep.searchprotocol.WorkerInterruptedV2
import com.seraf.vpnsweep.searchprotocol.WorkerOpenV2
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.SequenceInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface DeadlineWorkerPort : SearchWorkerPortV2 {
    fun openUntil(
        identity: WorkerIdentityV1,
        network: SearchNetworkV1,
        lease: SearchLeaseDuplicateV1,
        configuration: PrivateInputV1,
        startupDeadlineElapsedMillis: Long,
        cleanupMillis: Long,
    ): WorkerOpenV2

    fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit

    fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1

    override fun open(
        identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
        configuration: PrivateInputV1, startupMillis: Long, cleanupMillis: Long,
    ): WorkerOpenV2 = WorkerOpenV2.Fatal(WorkerFatalV2.CAPABILITY_UNAVAILABLE)

    override fun cancel(identity: WorkerIdentityV1): Unit = throw IOException("worker_deadline_required")

    override fun close(identity: WorkerIdentityV1): WorkerCloseV1 = WorkerCloseV1.QUARANTINED
}

internal class WorkerRoundClient(val port: DeadlineWorkerPort, val lease: SearchLeaseDuplicateV1)

// The caller owns the session anchor and creates this only after pre-gojni
// admission. A null reply bound disables Open before any worker is created.
// The factory must not bind; the concrete port must enforce its deadlines,
// sticky cancel and FD custody.
internal class SearchEngineWorkerAdapter(
    private val sessionGeneration: Long,
    private val network: SearchNetworkV1,
    private val cleanupMillis: Long,
    private val provenReplyBound: Int?,
    private val createRound: (WorkerIdentityV1) -> WorkerRoundClient?,
    private val reportProgress: (SearchProgressV1) -> Unit,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : SearchEngineWorker {
    private enum class State { OPENING, READY, RETIRING, CLOSING }
    private class Round(val identity: WorkerIdentityV1) {
        var client: WorkerRoundClient? = null
        var state = State.OPENING
        var dispatched = false
        var cancelled = false
        var cancelling: CountDownLatch? = null
        var cleanupStarted: Long? = null
        var cleanupScheduled = false
        var inFlight = false
        var openingWake: CountDownLatch? = null
        var callWake: CountDownLatch? = null
        var heldOpeningInput: PrivateInputV1? = null
        var heldLease: SearchLeaseDuplicateV1? = null
        var heldReply: PrivateInputV1? = null
    }

    private val lock = Any()
    private var active: Round? = null
    private var lastGeneration: Long? = null
    private var pendingCancelledGeneration: Long? = null
    private var fatal: Long? = null

    override fun open(
        session: Long,
        attempt: Long,
        round: Long,
        generation: Long,
        profile: ByteArray?,
        params: ByteArray?,
        attemptRemainingMillis: Long,
        startupRemainingMillis: Long,
    ): Long {
        val started = nowNanos()
        if (provenReplyBound == null || provenReplyBound <= 0) return Mobile.SearchWorkerCapabilityUnavailable
        if (session != sessionGeneration || attempt == 0L || round !in 1..Int.MAX_VALUE.toLong() || generation <= 0 ||
            profile == null || profile.isEmpty() || profile.size > MAX_PROFILE_BYTES || params == null || params.isEmpty() ||
            cleanupMillis !in 1..MAX_CLEANUP_MILLIS
        ) return Mobile.SearchWorkerUnavailable
        val identity = WorkerIdentityV1(session, attempt.toULong(), round.toInt(), generation)
        val attemptDeadline = elapsedDeadlineMillis(started, attemptRemainingMillis)
        val startupDeadline = elapsedDeadlineMillis(started, startupRemainingMillis)
        val current = synchronized(lock) {
            fatal?.let { return it }
            if (active != null || lastGeneration?.let { generation <= it } == true) return Mobile.SearchWorkerBusy
            val pending = pendingCancelledGeneration
            if (pending != null && generation > pending) {
                fatal = Mobile.SearchWorkerDeliveryUnknown
                return fatal!!
            }
            lastGeneration = generation
            if (pending == generation) {
                pendingCancelledGeneration = null
                return Mobile.SearchWorkerCancelled
            }
            if (attemptRemainingMillis <= 0 || startupRemainingMillis <= 0) return Mobile.SearchWorkerDeadline
            if (attemptDeadline == null || startupDeadline == null) return Mobile.SearchWorkerUnavailable
            Round(identity).also { active = it }
        }
        val client = try {
            createRound(identity)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (client == null) return fail(current, Mobile.SearchWorkerOwnerLost)
        val remainingAttempt = remainingMillis(started, attemptRemainingMillis)
        val remainingStartup = remainingMillis(started, startupRemainingMillis)
        val input = FrameInput(openHeader(attemptDeadline!!, profile.size, params.size), profile, params)
        if (remainingAttempt <= 0 || remainingStartup <= 0) {
            return if (closeInputsBounded(current, input, client.lease, started, minOf(startupRemainingMillis, attemptRemainingMillis))) finish(current, Mobile.SearchWorkerDeadline, preferCancelled = true)
            else fail(current, Mobile.SearchWorkerCleanupIncomplete)
        }
        val shouldDispatch = synchronized(lock) {
            current.client = client
            if (current.cancelled || fatal != null) false else {
                current.dispatched = true
                true
            }
        }
        if (!shouldDispatch) {
            return if (closeInputsBounded(current, input, client.lease, started, minOf(startupRemainingMillis, attemptRemainingMillis))) finishInterrupted(current) else fail(current, Mobile.SearchWorkerCleanupIncomplete)
        }
        val outcome = try {
            client.port.openUntil(identity, network, client.lease, input, startupDeadline!!, cleanupMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (outcome == null) synchronized(lock) { if (fatal == null) fatal = Mobile.SearchWorkerDeliveryUnknown }
        if (!closeInputsBounded(current, input, client.lease, started, minOf(startupRemainingMillis, attemptRemainingMillis))) return failAndCancel(current, Mobile.SearchWorkerCleanupIncomplete)
        if (outcome == null) return failAndCancel(current, Mobile.SearchWorkerDeliveryUnknown)
        val cancelled = synchronized(lock) { current.cancelled }
        if (cancelled && outcome is WorkerOpenV2.Ready) {
            val start = cleanupStart(current)
            if (!cancelCompleted(current, start) || !cleanClose(current, start)) return fail(current, Mobile.SearchWorkerCleanupIncomplete)
            return finishInterrupted(current)
        }
        return when (outcome) {
            WorkerOpenV2.Ready -> {
                val admitted = synchronized(lock) {
                    if (current.cancelled || fatal != null) false else {
                        current.state = State.READY
                        true
                    }
                }
                if (admitted) Mobile.SearchWorkerReady else {
                    val start = cleanupStart(current)
                    if (!cancelCompleted(current, start) || !cleanClose(current, start)) fail(current, Mobile.SearchWorkerCleanupIncomplete)
                    else finishInterrupted(current)
                }
            }
            is WorkerOpenV2.CandidateFailure -> {
                finishKnownOpen(current, candidateCode(outcome))
            }
            is WorkerOpenV2.Interrupted -> {
                finishKnownOpen(current, if (outcome.reason == WorkerInterruptedV2.CANCELLED) Mobile.SearchWorkerCancelled else Mobile.SearchWorkerDeadline)
            }
            is WorkerOpenV2.Fatal -> failAndCancel(current, fatalCode(outcome.reason))
        }
    }

    override fun call(generation: Long, operation: Long, request: ByteArray?, round: Long, remainingMillis: Long): ByteArray {
        val started = nowNanos()
        val op = when (operation) {
            Mobile.SearchWorkerCheck -> WorkerOperationV1.CHECK
            Mobile.SearchWorkerUDP -> WorkerOperationV1.UDP
            Mobile.SearchWorkerMedia -> WorkerOperationV1.MEDIA
            else -> return fault(Mobile.SearchWorkerUnavailable)
        }
        if (request == null || remainingMillis <= 0 || round !in 0 until Int.MAX_VALUE.toLong()) return fault(Mobile.SearchWorkerUnavailable)
        val callDeadline = elapsedDeadlineMillis(started, remainingMillis) ?: return fault(Mobile.SearchWorkerUnavailable)
        val current = synchronized(lock) {
            val value = active
            if (fatal != null || value == null || value.identity.workerGeneration != generation || value.identity.round.toLong() != round + 1 ||
                value.state != State.READY || value.cancelled || value.inFlight
            ) return fault(fatal ?: Mobile.SearchWorkerUnavailable)
            value.inFlight = true
            value
        }
        val remaining = remainingMillis(started, remainingMillis)
        if (remaining <= 0) {
            val code = synchronized(lock) {
                current.inFlight = false
                fatal ?: Mobile.SearchWorkerDeadline
            }
            return fault(code)
        }
        val input = FrameInput(callHeader(callDeadline, round.toInt(), request.size), request)
        val reply = try {
            current.client!!.port.execute(current.identity, op, input)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        } finally {
            input.close()
        }
        if (reply is WorkerReplyV1.Interrupted) {
            val code = synchronized(lock) {
                if (active !== current || current.state != State.READY) {
                    if (fatal == null) fatal = Mobile.SearchWorkerDeliveryUnknown
                }
                current.inFlight = false
                fatal ?: if (reply.reason == WorkerInterruptedV2.CANCELLED) Mobile.SearchWorkerCancelled else Mobile.SearchWorkerDeadline
            }
            return fault(code)
        }
        val result = when (reply) {
            is WorkerReplyV1.Complete -> readReplyBounded(current, reply.result, provenReplyBound!!, started, remainingMillis)
            WorkerReplyV1.Rejected -> ReplyRead.Failed
            WorkerReplyV1.Quarantined, null -> ReplyRead.Failed
            is WorkerReplyV1.Interrupted -> error("handled_interruption")
        }
        val disposition = synchronized(lock) {
            val stale = active !== current || current.state != State.READY
            when {
                !stale && fatal == null && current.cancelled && (result == ReplyRead.Interrupted || result is ReplyRead.Bytes) ->
                    CallDisposition.Cancelled
                !stale && fatal == null && !current.cancelled && result is ReplyRead.Bytes -> {
                    current.inFlight = false
                    CallDisposition.Success(result.value)
                }
                else -> {
                    val code = when {
                        result == ReplyRead.CleanupUnknown -> Mobile.SearchWorkerCleanupIncomplete
                        result == ReplyRead.Pending || stale -> Mobile.SearchWorkerDeliveryUnknown
                        reply is WorkerReplyV1.Quarantined || reply == null -> Mobile.SearchWorkerDeliveryUnknown
                        else -> Mobile.SearchWorkerUnavailable
                    }
                    if (fatal == null) fatal = code
                    if (result != ReplyRead.Pending && result != ReplyRead.CleanupUnknown) current.inFlight = false
                    CallDisposition.Failure(fatal!!)
                }
            }
        }
        return when (disposition) {
            is CallDisposition.Success -> resultEnvelope(disposition.bytes)
            is CallDisposition.Failure -> fault(failAndCancel(current, disposition.code))
            CallDisposition.Cancelled -> {
                val start = cleanupStart(current)
                if (!cancelCompleted(current, start)) return fault(failAndCancel(current, Mobile.SearchWorkerDeliveryUnknown))
                val code = synchronized(lock) {
                    if (active !== current || current.state != State.READY) {
                        if (fatal == null) fatal = Mobile.SearchWorkerDeliveryUnknown
                    }
                    current.inFlight = false
                    fatal ?: Mobile.SearchWorkerCancelled
                }
                fault(code)
            }
        }
    }

    override fun failure(generation: Long): Long = synchronized(lock) {
        fatal ?: if (active?.identity?.workerGeneration == generation) Mobile.SearchWorkerReady else Mobile.SearchWorkerUnavailable
    }

    override fun close(generation: Long, cleanupMillis: Long): Long {
        val started = nowNanos()
        val current = synchronized(lock) {
            val value = active
            if (value == null || value.identity.workerGeneration != generation || value.state != State.READY) return Mobile.SearchWorkerCleanupIncomplete
            recordCleanupStart(value, started)
            if (cleanupMillis == this.cleanupMillis && !value.inFlight && fatal == null) value.state = State.CLOSING
            value
        }
        if (synchronized(lock) { current.state != State.CLOSING }) {
            return failAndCancel(current, Mobile.SearchWorkerCleanupIncomplete)
        }
        val start = cleanupStart(current)
        if (!cancelCompleted(current, start) || !cleanClose(current, start)) return fail(current, Mobile.SearchWorkerCleanupIncomplete)
        return finish(current, Mobile.SearchWorkerReady)
    }

    override fun cancel(generation: Long): Unit {
        val started = nowNanos()
        val current = synchronized(lock) {
            if (generation <= 0) return
            val value = active
            if (value == null || value.identity.workerGeneration != generation) {
                if (lastGeneration?.let { generation <= it } == true) return
                val pending = pendingCancelledGeneration
                if (pending == null || pending == generation) pendingCancelledGeneration = generation
                else if (fatal == null) fatal = Mobile.SearchWorkerDeliveryUnknown
                return
            }
            value.cancelled = true
            recordCleanupStart(value, started)
            value.openingWake?.countDown()
            value.callWake?.countDown()
            if (!value.dispatched || value.state == State.CLOSING || value.state == State.RETIRING || value.cancelling != null) return
            value.cancelling = CountDownLatch(1)
            value
        }
        dispatchCancel(current)
    }

    // The real coordinator must call this on owner loss or worker death.
    fun revoked(generation: Long, reason: WorkerFatalV2): Unit {
        val pending = synchronized(lock) {
            val value = active ?: return
            if (value.identity.workerGeneration != generation) return
            if (fatal == null) fatal = fatalCode(reason)
            value.cancelled = true
            value.openingWake?.countDown()
            value.callWake?.countDown()
            val shouldCancel = value.dispatched && value.state != State.CLOSING && value.cancelling == null
            if (shouldCancel) value.cancelling = CountDownLatch(1)
            value to shouldCancel
        }
        try {
            Thread({
                try {
                    val started = nowNanos()
                    synchronized(lock) { recordCleanupStart(pending.first, started) }
                    if (pending.second) dispatchCancel(pending.first)
                    scheduleCleanup(pending.first)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) { } catch (_: LinkageError) { }
            }, "search-revoke-cleanup").apply { isDaemon = true }.start()
        } catch (_: Exception) { } catch (_: LinkageError) { }
    }

    override fun progress(kind: Long, outcome: Long, attempt: Long, profile: Long, round: Long, probe: Long, bytes: Long, durationMillis: Long): Unit {
        if (kind !in 1..SearchProgressKindV1.entries.size.toLong() || outcome !in 0 until SearchProgressOutcomeV1.entries.size.toLong()) return
        val kindValue = SearchProgressKindV1.entries.getOrNull((kind - 1).toInt()) ?: return
        val outcomeValue = SearchProgressOutcomeV1.entries.getOrNull(outcome.toInt()) ?: return
        if (profile !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || round !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            probe !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || bytes < 0 || durationMillis < 0
        ) return
        try {
            reportProgress(SearchProgressV1(sessionGeneration, kindValue, outcomeValue, attempt.toULong(), profile.toInt(), round.toInt(), probe.toInt(), bytes, durationMillis))
        } catch (_: Exception) {
            // Progress carries no resource and is not a worker acknowledgement.
        }
    }

    private fun dispatchCancel(round: Round): Unit {
        val done = round.cancelling ?: return
        val start = cleanupStart(round)
        val deadline = elapsedDeadlineMillis(start, cleanupMillis)
        if (deadline == null || remainingCleanupNanos(start) <= 0) {
            failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
            done.countDown()
            return
        }
        try {
            Thread({
                try {
                    round.client!!.port.cancelUntil(round.identity, deadline)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
                } catch (_: Exception) {
                    failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
                } catch (_: LinkageError) {
                    failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
                } finally {
                    done.countDown()
                }
            }, "search-round-cancel").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
            done.countDown()
        } catch (_: LinkageError) {
            failAndCancel(round, Mobile.SearchWorkerDeliveryUnknown)
            done.countDown()
        }
    }

    private fun recordCleanupStart(round: Round, started: Long): Long =
        round.cleanupStarted ?: started.also { round.cleanupStarted = it }

    private fun cleanupStart(round: Round): Long {
        synchronized(lock) { round.cleanupStarted }?.let { return it }
        val sampled = nowNanos()
        return synchronized(lock) { recordCleanupStart(round, sampled) }
    }

    private fun remainingMillis(start: Long, budget: Long): Long {
        val elapsed = nowNanos() - start
        if (elapsed < 0) return 0
        val millis = TimeUnit.NANOSECONDS.toMillis(elapsed) + if (elapsed % 1_000_000L == 0L) 0 else 1
        return if (millis >= budget) 0 else budget - millis
    }

    private fun elapsedDeadlineMillis(startNanos: Long, budgetMillis: Long): Long? {
        if (startNanos < 0 || budgetMillis <= 0) return null
        val startMillis = TimeUnit.NANOSECONDS.toMillis(startNanos)
        if (budgetMillis > Long.MAX_VALUE - startMillis) return null
        return startMillis + budgetMillis
    }

    private fun remainingNanos(start: Long, budget: Long): Long {
        val elapsed = nowNanos() - start
        if (elapsed < 0 || budget > Long.MAX_VALUE / 1_000_000L) return 0
        return TimeUnit.MILLISECONDS.toNanos(budget) - elapsed
    }

    private fun closeInputsBounded(round: Round, input: PrivateInputV1, lease: SearchLeaseDuplicateV1, start: Long, budget: Long): Boolean {
        val done = CountDownLatch(1)
        val wake = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val interruptRequested = AtomicBoolean(false)
        synchronized(lock) {
            round.openingWake = wake
            round.heldOpeningInput = input
            round.heldLease = lease
            if (round.cancelled) wake.countDown()
        }
        var closer: Thread? = null
        try {
            closer = Thread({
                try {
                    val closed = closeInputs(input, lease)
                    result.set(closed)
                    interrupted.set(Thread.currentThread().isInterrupted)
                    if (closed) synchronized(lock) { round.heldOpeningInput = null; round.heldLease = null }
                } finally { done.countDown(); wake.countDown() }
            }, "search-open-close").apply { isDaemon = true }
            closer.start()
            val remaining = remainingNanos(start, budget)
            if (remaining > 0) wake.await(remaining, TimeUnit.NANOSECONDS)
            if (remainingNanos(start, budget) <= 0) cancel(round.identity.workerGeneration)
            if (done.count != 0L) {
                cancel(round.identity.workerGeneration)
                interruptRequested.set(true)
                closer.interrupt()
                val cleanup = remainingCleanupNanos(cleanupStart(round))
                if (cleanup <= 0 || !done.await(cleanup, TimeUnit.NANOSECONDS)) return false
            }
            if (interrupted.get() && !interruptRequested.get()) Thread.currentThread().interrupt()
            return result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            interruptRequested.set(true)
            closer?.interrupt()
            cancel(round.identity.workerGeneration)
            return false
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        } finally {
            synchronized(lock) { if (round.openingWake === wake) round.openingWake = null }
        }
    }

    private fun readReplyBounded(round: Round, input: PrivateInputV1, bound: Int, start: Long, budget: Long): ReplyRead {
        val done = CountDownLatch(1)
        val wake = CountDownLatch(1)
        val result = AtomicReference<ReplyRead>(ReplyRead.Failed)
        val interrupted = AtomicBoolean(false)
        val interruptRequested = AtomicBoolean(false)
        synchronized(lock) {
            round.callWake = wake
            round.heldReply = input
            if (round.cancelled) wake.countDown()
        }
        var reader: Thread? = null
        try {
            reader = Thread({
                try {
                    val read = readReply(input, bound)
                    result.set(read)
                    interrupted.set(Thread.currentThread().isInterrupted)
                    if (read != ReplyRead.CleanupUnknown) synchronized(lock) { round.heldReply = null }
                } finally { done.countDown(); wake.countDown() }
            }, "search-reply-read").apply { isDaemon = true }
            reader.start()
            val remaining = remainingNanos(start, budget)
            if (remaining > 0) wake.await(remaining, TimeUnit.NANOSECONDS)
            if (remainingNanos(start, budget) <= 0) cancel(round.identity.workerGeneration)
            if (done.count != 0L) {
                cancel(round.identity.workerGeneration)
                interruptRequested.set(true)
                reader.interrupt()
                val cleanup = remainingCleanupNanos(cleanupStart(round))
                if (cleanup <= 0 || !done.await(cleanup, TimeUnit.NANOSECONDS)) return ReplyRead.Pending
            }
            if (interrupted.get() && !interruptRequested.get()) Thread.currentThread().interrupt()
            return result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            interruptRequested.set(true)
            reader?.interrupt()
            cancel(round.identity.workerGeneration)
            return ReplyRead.Pending
        } catch (_: Exception) {
            return ReplyRead.Pending
        } catch (_: LinkageError) {
            return ReplyRead.Pending
        } finally {
            synchronized(lock) { if (round.callWake === wake) round.callWake = null }
        }
    }

    private fun remainingCleanupNanos(start: Long): Long {
        val elapsed = nowNanos() - start
        return if (elapsed < 0) 0 else TimeUnit.MILLISECONDS.toNanos(cleanupMillis) - elapsed
    }

    private fun cancelCompleted(round: Round, start: Long): Boolean {
        val pending = synchronized(lock) { round.cancelling } ?: return true
        return try {
            val remaining = remainingCleanupNanos(start)
            remaining > 0 && pending.await(remaining, TimeUnit.NANOSECONDS) && remainingCleanupNanos(start) > 0 &&
                synchronized(lock) { fatal == null }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun cleanClose(round: Round, start: Long): Boolean {
        val done = CountDownLatch(1)
        val result = AtomicReference<WorkerCloseV1?>()
        val deadline = elapsedDeadlineMillis(start, cleanupMillis) ?: return false
        if (remainingCleanupNanos(start) <= 0) return false
        try {
            Thread({
                try { result.set(round.client!!.port.closeUntil(round.identity, deadline)) } catch (_: Exception) { } catch (_: LinkageError) { }
                finally { done.countDown() }
            }, "search-round-close").apply { isDaemon = true }.start()
            val remaining = remainingCleanupNanos(start)
            return remaining > 0 && done.await(remaining, TimeUnit.NANOSECONDS) && remainingCleanupNanos(start) > 0 &&
                result.get() == WorkerCloseV1.CLEAN_AND_DEAD
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }
    }

    private fun failAndCancel(round: Round, code: Long): Long {
        val shouldCancel = synchronized(lock) {
            if (fatal == null) fatal = code
            round.cancelled = true
            round.openingWake?.countDown()
            round.callWake?.countDown()
            if (!round.dispatched || round.cancelling != null) false else {
                round.cancelling = CountDownLatch(1)
                true
            }
        }
        val started = nowNanos()
        synchronized(lock) { recordCleanupStart(round, started) }
        if (shouldCancel) dispatchCancel(round)
        scheduleCleanup(round)
        return synchronized(lock) { fatal ?: code }
    }

    private fun scheduleCleanup(round: Round): Unit {
        val schedule = synchronized(lock) {
            if (round.cleanupScheduled || !round.dispatched || round.client == null) false else {
                round.cleanupScheduled = true
                true
            }
        }
        if (!schedule) return
        val deadline = elapsedDeadlineMillis(cleanupStart(round), cleanupMillis) ?: return
        try {
            Thread({
                try { round.client!!.port.closeUntil(round.identity, deadline) } catch (_: Exception) { } catch (_: LinkageError) { }
            }, "search-round-quarantine").apply { isDaemon = true }.start()
        } catch (_: Exception) { } catch (_: LinkageError) { }
    }

    private fun fail(round: Round, code: Long): Long = synchronized(lock) {
        if (fatal == null) fatal = code
        round.cancelled = true
        fatal!!
    }

    private fun finishInterrupted(round: Round): Long = finish(round, Mobile.SearchWorkerCancelled)

    private fun finishKnownOpen(round: Round, code: Long): Long {
        synchronized(lock) {
            if (fatal != null) return fatal!!
            if (active !== round || round.inFlight) {
                fatal = Mobile.SearchWorkerCleanupIncomplete
                return fatal!!
            }
            round.state = State.RETIRING
        }
        if (!cancelCompleted(round, cleanupStart(round))) return fail(round, Mobile.SearchWorkerCleanupIncomplete)
        return finish(round, code, preferCancelled = true)
    }
    private fun finish(round: Round, code: Long, preferCancelled: Boolean = false): Long = synchronized(lock) {
        if (fatal != null) return fatal!!
        if (active !== round || round.inFlight) {
            fatal = Mobile.SearchWorkerCleanupIncomplete
            return fatal!!
        }
        active = null
        if (preferCancelled && round.cancelled) Mobile.SearchWorkerCancelled else code
    }

    private companion object {
        const val MAX_PROFILE_BYTES = 8 * 1024 * 1024
        const val MAX_CLEANUP_MILLIS = 60_000L
    }
}

private fun candidateCode(outcome: WorkerOpenV2.CandidateFailure): Long {
    val code = when (outcome.reason) {
        WorkerCandidateFailureV2.CONFIG_REJECTED -> Mobile.SearchWorkerCandidateConfigRejected
        WorkerCandidateFailureV2.CORE_START_FAILED -> Mobile.SearchWorkerCandidateStartFailed
        WorkerCandidateFailureV2.CORE_START_TIMEOUT -> Mobile.SearchWorkerCandidateStartTimeout
        WorkerCandidateFailureV2.CORE_EXITED_BEFORE_READY -> Mobile.SearchWorkerCandidateExitedBeforeReady
    }
    return if (outcome.retryable) code or Mobile.SearchWorkerRetryableFlag else code
}

private fun fatalCode(reason: WorkerFatalV2): Long = when (reason) {
    WorkerFatalV2.BUSY -> Mobile.SearchWorkerBusy
    WorkerFatalV2.OWNER_LOST -> Mobile.SearchWorkerOwnerLost
    WorkerFatalV2.CAPABILITY_UNAVAILABLE -> Mobile.SearchWorkerCapabilityUnavailable
    WorkerFatalV2.UNKNOWN_DELIVERY -> Mobile.SearchWorkerDeliveryUnknown
    WorkerFatalV2.WORKER_DIED -> Mobile.SearchWorkerDied
    WorkerFatalV2.CLEANUP_INCOMPLETE -> Mobile.SearchWorkerCleanupIncomplete
}

private fun openHeader(attemptDeadlineMillis: Long, profileLength: Int, paramsLength: Int): ByteArray =
    ByteArrayOutputStream(16).also { output -> DataOutputStream(output).use { it.writeLong(attemptDeadlineMillis); it.writeInt(profileLength); it.writeInt(paramsLength) } }.toByteArray()

private fun callHeader(deadlineMillis: Long, round: Int, length: Int): ByteArray =
    ByteArrayOutputStream(16).also { output -> DataOutputStream(output).use { it.writeLong(deadlineMillis); it.writeInt(round); it.writeInt(length) } }.toByteArray()

private fun closeInputs(input: PrivateInputV1, lease: SearchLeaseDuplicateV1): Boolean {
    var success = true
    try { input.close() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); success = false }
    catch (_: Exception) { success = false } catch (_: LinkageError) { success = false }
    try { lease.close() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); success = false }
    catch (_: Exception) { success = false } catch (_: LinkageError) { success = false }
    return success
}

private sealed interface CallDisposition {
    data class Success(val bytes: ByteArray) : CallDisposition
    data class Failure(val code: Long) : CallDisposition
    data object Cancelled : CallDisposition
}

private sealed interface ReplyRead {
    data class Bytes(val value: ByteArray) : ReplyRead
    data object Failed : ReplyRead
    data object CleanupUnknown : ReplyRead
    data object Pending : ReplyRead
    data object Interrupted : ReplyRead
}

private fun readReply(input: PrivateInputV1, bound: Int): ReplyRead {
    var stream: InputStream? = null
    var result: ByteArray? = null
    var valid = true
    var cleanupUnknown = false
    var interrupted = false
    try {
        stream = input.open()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            val available = bound.toLong() - count + 1
            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), available).toInt())
            if (n < 0) break
            if (n == 0 || n > bound - count) { valid = false; break }
            output.write(buffer, 0, n)
            count += n
        }
        if (valid) result = output.toByteArray()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        interrupted = true
        valid = false
    } catch (_: InterruptedIOException) {
        interrupted = true
        valid = false
    } catch (_: Exception) {
        valid = false
    } catch (_: LinkageError) {
        valid = false
    } finally {
        try { stream?.close() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); cleanupUnknown = true }
        catch (_: Exception) { cleanupUnknown = true } catch (_: LinkageError) { cleanupUnknown = true }
        try { input.close() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); cleanupUnknown = true }
        catch (_: Exception) { cleanupUnknown = true } catch (_: LinkageError) { cleanupUnknown = true }
    }
    return when {
        cleanupUnknown -> ReplyRead.CleanupUnknown
        interrupted -> ReplyRead.Interrupted
        valid && result != null -> ReplyRead.Bytes(result)
        else -> ReplyRead.Failed
    }
}

private fun fault(code: Long): ByteArray = "{\"fault\":$code}".toByteArray(Charsets.UTF_8)
private fun resultEnvelope(result: ByteArray): ByteArray = "{\"fault\":0,\"result\":".toByteArray(Charsets.UTF_8) + result + byteArrayOf('}'.code.toByte())

private class FrameInput(vararg chunks: ByteArray) : PrivateInputV1 {
    private val lock = Any()
    private var parts: Array<out ByteArray>? = chunks
    private var stream: InputStream? = null
    private var opened = false

    override fun open(): InputStream = synchronized(lock) {
        val bytes = parts ?: throw IOException("worker_input_closed")
        if (opened) throw IOException("worker_input_already_opened")
        opened = true
        SequenceInputStream(Collections.enumeration(bytes.map { ByteArrayInputStream(it) })).also { stream = it }
    }

    override fun close(): Unit = synchronized(lock) {
        parts = null
        stream?.close()
        stream = null
    }
}
