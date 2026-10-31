package com.seraf.vpnsweep.searchworker

import android.os.SystemClock
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchFailureV1
import com.seraf.vpnsweep.searchprotocol.SearchPauseReasonV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV4
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The factory owns one session and the lease/network resources used by it.
// It must consume profiles synchronously; the producer closes the input.
// Pause and release states require confirmed worker death and FD disposition;
// quarantine must stop further work and forbid unconfirmed lease release.
// A factory retains acquired resources until ownership transfers and must
// quarantine them itself if it fails before returning a session.
internal interface SearchPhaseSession {
    val releaseOwner: SearchOwnerLease?
    fun preparePhase(epoch: Long): Unit
    fun runPhase(epoch: Long): String
    fun cancelPhase(epoch: Long): Boolean
    fun openVerifiedResult(): LocalOpenedResult
    fun closeSession(deadlineElapsedMillis: Long): Unit
    fun pauseResources(deadlineElapsedMillis: Long): SearchResourceStateV2
    fun retireResources(deadlineElapsedMillis: Long): SearchResourceStateV2
    fun releaseResources(deadlineElapsedMillis: Long): SearchResourceStateV2
    fun quarantine(): Unit
}

internal class SearchSessionProducer(
    private val cleanupMillis: Long,
    private val createSession: (SearchSnapshotV1, (Long, SearchProgressV1) -> Unit) -> SearchPhaseSession,
    // Must be a local, nonblocking elapsed-realtime clock, including in tests.
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) : SearchSessionPortV4 {
    private enum class State { STARTING, RUNNING, FINISHING, EMITTING_PAUSE, PAUSED, TERMINAL, UNKNOWN }
    private enum class Control { NONE, PAUSE, CANCEL }
    private class Cancellation(val epoch: Long) {
        val done = CountDownLatch(1)
        @Volatile var accepted: Boolean? = null
    }
    private class ControlWatch(val epoch: Long, val deadline: Long) {
        val done = CountDownLatch(1)
    }
    private class PauseDelivery {
        val done = CountDownLatch(1)
    }
    private class UnknownActions(
        val session: SearchPhaseSession?,
        val phase: SearchLocalPhase?,
        val resources: SearchResourceStateV2,
        val deliver: Boolean,
    )
    private class Active(val snapshot: SearchSnapshotV1, val observer: SearchSessionObserverV3, epoch: Long) {
        var state = State.STARTING
        var epoch = epoch
        var lastEpoch = epoch
        var control = Control.NONE
        var deadline: Long? = null
        var session: SearchPhaseSession? = null
        var releaseOwner: SearchOwnerLease? = null
        var prepared = false
        var phase: SearchLocalPhase? = null
        var cancellation: Cancellation? = null
        var watch: ControlWatch? = null
        var pauseDelivery: PauseDelivery? = null
        var terminal: SearchTerminalV3? = null
        var resources = SearchResourceStateV2.QUARANTINED
    }

    private val lock = Any()
    private var active: Active? = null
    private var blocked = false

    init { require(cleanupMillis in 1..60_000) }

    override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
        if (snapshot.sessionGeneration <= 0 || phaseEpoch <= 0) return SearchStartV3.REJECTED
        val current = synchronized(lock) {
            if (blocked) return SearchStartV3.BUSY
            if (active != null) return SearchStartV3.BUSY
            Active(snapshot, observer, phaseEpoch).also { active = it }
        }
        if (!launch { createAndRun(current) }) {
            synchronized(lock) { blocked = true; current.state = State.UNKNOWN }
            return SearchStartV3.UNKNOWN
        }
        return SearchStartV3.ACCEPTED
    }

    override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = control(sessionGeneration, phaseEpoch, Control.PAUSE)
    override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = control(sessionGeneration, phaseEpoch, Control.CANCEL)

    override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
        var failed: Pair<Active, UnknownActions>? = null
        val accepted = synchronized(lock) {
            val current = active ?: return false
            if (current.state != State.TERMINAL || current.terminal !== terminal ||
                terminal.sessionGeneration != current.snapshot.sessionGeneration || terminal.phaseEpoch != current.epoch
            ) return false
            val now = try { nowMillis() } catch (_: Exception) { -1L } catch (_: LinkageError) { -1L }
            val deadline = current.deadline
            if (now < 0 || deadline == null || now >= deadline || current.releaseOwner?.acceptRelease() != true) {
                markUnknown(current, notify = false, watch = null)?.let { failed = current to it }
                false
            } else {
                current.watch?.done?.countDown()
                current.watch = null
                active = null
                true
            }
        }
        failed?.let { (current, actions) -> dispatchUnknown(current, actions) }
        return accepted
    }

    fun ownerLost(sessionGeneration: Long): Unit {
        val pending = synchronized(lock) {
            val current = active ?: return
            if (current.snapshot.sessionGeneration != sessionGeneration) return
            val actions = markUnknown(current, notify = true, watch = null) ?: return
            current to actions
        }
        dispatchUnknown(pending.first, pending.second)
    }

    override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 {
        var pendingPause: PauseDelivery? = null
        var startWatch: Pair<Active, ControlWatch>? = null
        var invalid: Active? = null
        val current = synchronized(lock) {
            val value = active ?: return SearchStartV3.REJECTED
            if (blocked || value.state == State.UNKNOWN) return SearchStartV3.UNKNOWN
            if (value.snapshot.sessionGeneration != sessionGeneration ||
                (value.state != State.PAUSED && value.state != State.EMITTING_PAUSE) ||
                value.control == Control.CANCEL || phaseEpoch <= value.lastEpoch || phaseEpoch <= 0
            ) return SearchStartV3.REJECTED
            val pending = value.pauseDelivery
            if (value.state == State.EMITTING_PAUSE && (pending == null || value.deadline == null)) {
                invalid = value
                null
            } else {
                pendingPause = pending
                if (pending != null && value.watch == null) {
                    val watch = ControlWatch(value.epoch, value.deadline!!)
                    value.watch = watch
                    startWatch = value to watch
                }
                if (pending == null) {
                    value.watch?.done?.countDown()
                    value.watch = null
                    value.deadline = null
                }
                value.lastEpoch = phaseEpoch
                value.epoch = phaseEpoch
                value.control = Control.NONE
                value.prepared = false
                value.phase = null
                value.cancellation = null
                value.state = State.STARTING
                value
            }
        } ?: run {
            invalid?.let { unknown(it) }
            return SearchStartV3.UNKNOWN
        }
        startWatch?.let { (owner, watch) -> dispatchWatch(owner, watch) }
        if (!launch {
                val pause = pendingPause
                if (pause != null) {
                    try { pause.done.await() } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        unknown(current)
                        return@launch
                    }
                }
                runPhase(current, phaseEpoch)
            }) {
            unknown(current)
            return SearchStartV3.UNKNOWN
        }
        return SearchStartV3.ACCEPTED
    }

    private fun createAndRun(current: Active): Unit {
        val profiles = OwnedProfiles(current.snapshot.profiles)
        val snapshot = SearchSnapshotV1(
            current.snapshot.sessionGeneration, current.snapshot.sourceRevision, current.snapshot.selectionRevision, profiles,
        )
        val session = try {
            createSession(snapshot) { epoch, progress -> progress(current, epoch, progress) }
        } catch (failure: Throwable) {
            try { profiles.close() } catch (_: Exception) { }
            unknown(current)
            rethrowProducerFatal(failure)
            return
        }
        try {
            profiles.close()
        } catch (_: Exception) {
            try { session.quarantine() } catch (_: Exception) { }
            unknown(current)
            return
        }
        val control = synchronized(lock) {
            if (active !== current || current.state == State.UNKNOWN) null else {
                current.session = session
                current.control
            }
        }
        if (control == null) {
            try { session.quarantine() } catch (_: Exception) { }
            return
        }
        val releaseOwner = try { session.releaseOwner } catch (_: Exception) { null } catch (_: LinkageError) { null }
        if (releaseOwner == null) return unknown(current)
        val attached = synchronized(lock) {
            if (active !== current || current.state == State.UNKNOWN) false else {
                current.releaseOwner = releaseOwner
                true
            }
        }
        if (!attached) {
            try { session.quarantine() } catch (_: Exception) { }
            return
        }
        when (control) {
            Control.CANCEL -> finishWithoutRun(current, cancelled = true)
            Control.PAUSE -> emitPause(current, SearchPauseReasonV1.USER_REQUEST)
            Control.NONE -> runPhase(current, current.epoch)
        }
    }

    private fun runPhase(current: Active, epoch: Long): Unit {
        val session = synchronized(lock) {
            if (active !== current || current.epoch != epoch || current.state != State.STARTING) return
            current.session ?: return
        }
        try {
            session.preparePhase(epoch)
        } catch (failure: Throwable) {
            unknown(current)
            rethrowProducerFatal(failure)
            return
        }
        var launchCancel: Cancellation? = null
        val existing = synchronized(lock) {
            if (active !== current || current.epoch != epoch || current.state == State.UNKNOWN) return
            current.prepared = true
            if (current.control != Control.NONE && current.cancellation == null) {
                Cancellation(epoch).also { current.cancellation = it; launchCancel = it }
            } else current.cancellation
        }
        launchCancel?.let { dispatchCancel(current, session, it) }
        if (existing != null && !awaitCancel(current, existing, requireAccepted = true)) {
            unknown(current)
            return
        }
        val phase = SearchLocalPhase(
            current.snapshot.sessionGeneration, epoch, current.snapshot.sourceRevision, current.snapshot.selectionRevision,
            current.observer, { session.runPhase(epoch) }, session::openVerifiedResult,
            { if (cleanup(current) { deadline -> session.closeSession(deadline); true } != true) throw IOException("session_cleanup_unknown") },
            { cleanup(current) { deadline -> session.retireResources(deadline) } ?: SearchResourceStateV2.QUARANTINED },
            { (cleanup(current) { deadline -> session.releaseResources(deadline) } ?: SearchResourceStateV2.QUARANTINED)
                .also { result -> synchronized(lock) { current.resources = result } } },
        )
        val cancelBeforeRun = synchronized(lock) {
            if (active !== current || current.epoch != epoch || current.state == State.UNKNOWN) return
            current.phase = phase
            current.state = State.RUNNING
            current.control == Control.CANCEL
        }
        if (cancelBeforeRun) phase.revoke()
        val outcome = phase.execute()
        if (outcome == LocalPhaseOutcome.NotStarted) {
            val pending = synchronized(lock) { current.cancellation }
            if (pending == null || !awaitCancel(current, pending, requireAccepted = true)) {
                unknown(current)
                return
            }
            try { session.runPhase(epoch) } catch (failure: Throwable) {
                unknown(current)
                rethrowProducerFatal(failure)
                return
            }
            finishWithoutRun(current, cancelled = true)
            return
        }
        val cancellation = synchronized(lock) {
            if (active !== current || current.epoch != epoch || current.state == State.UNKNOWN) return
            current.state = State.FINISHING
            current.cancellation
        }
        if (cancellation != null && !awaitCancel(current, cancellation, requireAccepted = false)) {
            unknown(current)
            return
        }
        when (outcome) {
            LocalPhaseOutcome.Paused -> emitPause(current, SearchPauseReasonV1.BUDGET)
            LocalPhaseOutcome.Interrupted -> {
                val (control, accepted) = synchronized(lock) { current.control to (current.cancellation?.accepted == true) }
                when {
                    control == Control.PAUSE && accepted -> emitPause(current, SearchPauseReasonV1.USER_REQUEST)
                    control == Control.CANCEL && accepted -> finishWithoutRun(current, cancelled = true)
                    else -> finishWithoutRun(current, cancelled = false)
                }
            }
            LocalPhaseOutcome.Stopped -> finishWithoutRun(current, cancelled = false)
            LocalPhaseOutcome.Unknown, LocalPhaseOutcome.NotStarted -> unknown(current)
            is LocalPhaseOutcome.Handoff -> finishHandoff(current, outcome)
        }
    }

    private fun control(generation: Long, epoch: Long, requested: Control): Unit {
        val now = nowMillis()
        var phase: SearchLocalPhase? = null
        var dispatch: Triple<Active, SearchPhaseSession, Cancellation>? = null
        var finishPaused: Active? = null
        var failed: Active? = null
        var startWatch: Pair<Active, ControlWatch>? = null
        synchronized(lock) {
            val current = active ?: return
            if (current.snapshot.sessionGeneration != generation || current.epoch != epoch ||
                current.state == State.TERMINAL || current.state == State.UNKNOWN
            ) return
            if (current.control == Control.CANCEL || current.control == requested) return
            if (now < 0 || now > Long.MAX_VALUE - cleanupMillis) {
                failed = current
            } else {
                if (current.deadline == null) current.deadline = now + cleanupMillis
                current.control = requested
                if (current.watch == null) {
                    val watch = ControlWatch(epoch, current.deadline!!)
                    current.watch = watch
                    startWatch = current to watch
                }
                if (requested == Control.CANCEL) phase = current.phase
                if (current.state == State.PAUSED && requested == Control.CANCEL) {
                    current.state = State.FINISHING
                    finishPaused = current
                } else if (current.prepared && (current.state == State.STARTING || current.state == State.RUNNING) && current.cancellation == null) {
                    val owner = current.session
                    if (owner == null) failed = current else {
                        val cancellation = Cancellation(epoch)
                        current.cancellation = cancellation
                        dispatch = Triple(current, owner, cancellation)
                    }
                }
            }
        }
        phase?.revoke()
        startWatch?.let { (current, watch) -> dispatchWatch(current, watch) }
        failed?.let { unknown(it) }
        dispatch?.let { (current, session, cancellation) -> dispatchCancel(current, session, cancellation) }
        finishPaused?.let { current -> if (!launch { finishWithoutRun(current, cancelled = true) }) unknown(current) }
    }

    private fun dispatchWatch(current: Active, watch: ControlWatch): Unit {
        if (!launch {
                while (true) {
                    val now = nowMillis()
                    if (now < 0 || now >= watch.deadline) {
                        unknown(current, watch = watch)
                        return@launch
                    }
                    val finished = try { watch.done.await(watch.deadline - now, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        unknown(current, watch = watch)
                        return@launch
                    }
                    if (finished) return@launch
                }
            }) unknown(current, watch = watch)
    }

    private fun dispatchCancel(current: Active, session: SearchPhaseSession, cancellation: Cancellation): Unit {
        if (!launch {
                try {
                    cancellation.accepted = session.cancelPhase(cancellation.epoch)
                } catch (_: Exception) {
                    cancellation.accepted = null
                } finally {
                    cancellation.done.countDown()
                }
            }) {
            cancellation.done.countDown()
            unknown(current)
        }
    }

    private fun awaitCancel(current: Active, cancellation: Cancellation, requireAccepted: Boolean): Boolean {
        val deadline = synchronized(lock) { current.deadline } ?: return false
        val now = nowMillis()
        if (now < 0 || deadline <= now) return false
        val done = try { cancellation.done.await(deadline - now, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val after = nowMillis()
        return done && after >= 0 && after < deadline && cancellation.accepted != null && (!requireAccepted || cancellation.accepted == true)
    }

    private fun emitPause(current: Active, reason: SearchPauseReasonV1): Unit {
        val session = synchronized(lock) { current.session } ?: return unknown(current)
        val resources = cleanup(current) { deadline -> session.pauseResources(deadline) } ?: SearchResourceStateV2.QUARANTINED
        if (resources != SearchResourceStateV2.PAUSED_LEASE_HELD) return unknown(current)
        var delivery: PauseDelivery? = null
        val epoch = synchronized(lock) { current.epoch }
        val cancelInstead = synchronized(lock) {
            if (active !== current || current.state == State.UNKNOWN) return
            if (current.control == Control.CANCEL) true else {
                delivery = PauseDelivery()
                current.pauseDelivery = delivery
                current.state = State.EMITTING_PAUSE
                false
            }
        }
        if (cancelInstead) return finishWithoutRun(current, cancelled = true)
        val terminal = SearchTerminalV3.Paused(current.snapshot.sessionGeneration, epoch, reason, resources)
        try {
            if (!emit(current, terminal)) return
            val completedAt = nowMillis()
            var expired = false
            val cancelAfter = synchronized(lock) {
                if (active !== current || current.state == State.UNKNOWN || current.pauseDelivery !== delivery) return
                val deadline = current.deadline
                if (completedAt < 0 || deadline == null || completedAt >= deadline) {
                    expired = true
                    false
                } else if (current.epoch != epoch) {
                    current.pauseDelivery = null
                    if (current.control == Control.NONE) {
                        current.deadline = null
                        current.watch?.done?.countDown()
                        current.watch = null
                    }
                    false
                } else {
                    if (current.state != State.EMITTING_PAUSE) return
                    current.pauseDelivery = null
                    if (current.control == Control.CANCEL) {
                        current.state = State.FINISHING
                        true
                    } else {
                        current.state = State.PAUSED
                        current.deadline = null
                        current.watch?.done?.countDown()
                        current.watch = null
                        false
                    }
                }
            }
            if (expired) unknown(current)
            else if (cancelAfter) finishWithoutRun(current, cancelled = true)
        } finally {
            delivery!!.done.countDown()
        }
    }

    private fun finishWithoutRun(current: Active, cancelled: Boolean): Unit {
        val session = synchronized(lock) { current.session } ?: return unknown(current)
        val resources = cleanup(current) { deadline ->
            if (session.retireResources(deadline) != SearchResourceStateV2.PAUSED_LEASE_HELD) {
                SearchResourceStateV2.QUARANTINED
            } else {
                session.closeSession(deadline)
                session.releaseResources(deadline)
            }
        } ?: SearchResourceStateV2.QUARANTINED
        if (resources != SearchResourceStateV2.RELEASED) return unknown(current)
        val terminal = if (cancelled) SearchTerminalV3.Cancelled(current.snapshot.sessionGeneration, current.epoch, resources, SearchResultFenceV3.CONFIRMED)
        else SearchTerminalV3.Stopped(current.snapshot.sessionGeneration, current.epoch, SearchFailureV1.INTERRUPTED, resources, SearchResultFenceV3.CONFIRMED)
        finish(current, terminal)
    }

    private fun finishHandoff(current: Active, outcome: LocalPhaseOutcome.Handoff): Unit {
        val resources = synchronized(lock) { current.resources }
        if (resources != SearchResourceStateV2.RELEASED || outcome.localFence != SearchResultFenceV3.CONFIRMED) return unknown(current)
        val terminal = when (val handoff = outcome.result) {
            is HandoffResult.Delivered -> when (handoff.receipt) {
                SearchResultReceiptV3.COMMITTED_CLOSED -> SearchTerminalV3.Complete(current.snapshot.sessionGeneration, current.epoch, resources, outcome.localFence)
                SearchResultReceiptV3.CANCELLED_CLOSED -> SearchTerminalV3.Cancelled(current.snapshot.sessionGeneration, current.epoch, resources, outcome.localFence)
                SearchResultReceiptV3.FAILED_CLOSED, SearchResultReceiptV3.IGNORED_CLOSED -> SearchTerminalV3.Stopped(current.snapshot.sessionGeneration, current.epoch, SearchFailureV1.INTERRUPTED, resources, outcome.localFence)
                SearchResultReceiptV3.UNKNOWN -> return unknown(current)
            }
            HandoffResult.Discarded -> SearchTerminalV3.Cancelled(current.snapshot.sessionGeneration, current.epoch, resources, outcome.localFence)
            HandoffResult.Rejected, HandoffResult.Unknown -> return unknown(current)
        }
        finish(current, terminal)
    }

    private fun finish(current: Active, terminal: SearchTerminalV3): Unit {
        synchronized(lock) {
            if (active !== current || current.state == State.UNKNOWN || current.state == State.TERMINAL) return
            current.state = State.TERMINAL
            current.terminal = terminal
        }
        emit(current, terminal)
    }

    private fun emit(current: Active, terminal: SearchTerminalV3): Boolean = try {
        current.observer.onTerminal(terminal)
        true
    } catch (_: Exception) {
        unknown(current, notify = false)
        false
    } catch (_: LinkageError) {
        unknown(current, notify = false)
        false
    }

    private fun progress(current: Active, epoch: Long, progress: SearchProgressV1): Unit {
        synchronized(lock) {
            if (active !== current || current.state != State.RUNNING || current.epoch != epoch ||
                progress.sessionGeneration != current.snapshot.sessionGeneration
            ) return
        }
        try { current.observer.onProgress(epoch, progress) } catch (_: Exception) { }
    }

    private fun unknown(current: Active, notify: Boolean = true, watch: ControlWatch? = null): Unit {
        val actions = synchronized(lock) { markUnknown(current, notify, watch) } ?: return
        dispatchUnknown(current, actions)
    }

    private fun markUnknown(current: Active, notify: Boolean, watch: ControlWatch?): UnknownActions? {
        if (active !== current || current.state == State.UNKNOWN || (watch != null && current.watch !== watch)) return null
        val deliver = notify && current.state != State.TERMINAL
        blocked = true
        current.state = State.UNKNOWN
        current.watch?.done?.countDown()
        current.watch = null
        return UnknownActions(current.session, current.phase, current.resources, deliver)
    }

    private fun dispatchUnknown(current: Active, actions: UnknownActions): Unit {
        actions.phase?.revoke()
        launch {
            try { actions.session?.quarantine() } catch (_: Exception) { }
            if (actions.deliver) try {
                current.observer.onTerminal(SearchTerminalV3.Stopped(current.snapshot.sessionGeneration, current.epoch,
                    SearchFailureV1.INTERRUPTED, actions.resources, SearchResultFenceV3.UNKNOWN))
            } catch (_: Exception) { }
        }
    }

    private fun launch(block: () -> Unit): Boolean = try {
        Thread(block, "search-session-phase").apply { isDaemon = true }.start()
        true
    } catch (_: Exception) { false } catch (_: LinkageError) { false }

    private fun <T : Any> cleanup(current: Active, action: (Long) -> T): T? {
        val now = nowMillis()
        if (now < 0 || now > Long.MAX_VALUE - cleanupMillis) return null
        var startWatch: ControlWatch? = null
        val deadline = synchronized(lock) {
            if (active !== current || current.state == State.UNKNOWN) return null
            val selected = current.deadline ?: (now + cleanupMillis).also { current.deadline = it }
            if (current.watch == null) {
                startWatch = ControlWatch(current.epoch, selected).also { current.watch = it }
            }
            selected
        }
        startWatch?.let { dispatchWatch(current, it) }
        if (deadline <= now) return null
        val done = CountDownLatch(1)
        val value = java.util.concurrent.atomic.AtomicReference<T?>()
        if (!launch {
                try { value.set(action(deadline)) } catch (_: Exception) { } catch (_: LinkageError) { }
                finally { done.countDown() }
            }) return null
        val signalled = try { done.await(deadline - now, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val after = nowMillis()
        return if (signalled && after >= 0 && after < deadline) value.get() else null
    }
}

private fun rethrowProducerFatal(failure: Throwable): Unit {
    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
}

private class OwnedProfiles(private val source: PrivateInputV1) : PrivateInputV1 {
    private var opened = false
    private var stream: InputStream? = null
    private val closeSource = LeaseCloseOnce { source.close() }
    private val closeAll = LeaseCloseOnce {
        var failed: Throwable? = null
        try { stream?.close() } catch (failure: Throwable) { failed = failure }
        try { closeSource.close() } catch (failure: Throwable) { if (failed == null) failed = failure }
        failed?.let { throw IOException("profile_close_unknown", it) }
    }

    @Synchronized override fun open(): InputStream {
        check(!opened) { "profile_already_opened" }
        opened = true
        val input = source.open()
        val once = LeaseCloseOnce { input.close() }
        return object : FilterInputStream(input) {
            override fun close(): Unit = once.close()
        }.also { stream = it }
    }

    override fun close(): Unit = closeAll.close()
}
