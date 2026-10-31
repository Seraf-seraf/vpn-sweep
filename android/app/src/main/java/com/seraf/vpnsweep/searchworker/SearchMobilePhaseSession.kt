package com.seraf.vpnsweep.searchworker

import android.os.SystemClock
import com.seraf.vpnsweep.core.mobile.SearchSession
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class SearchPhaseUnavailable : IOException("search_phase_unavailable")

// The reporter must be wired only to SearchEngineWorkerAdapter's synchronous
// Go progress callback for the same session as runPhase.
internal class SearchMobilePhaseProgress(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val deliver: (Long, SearchProgressV1) -> Unit,
) {
    private val lock = Any()
    private var activeEpoch: Long? = null
    private var revoked = false
    private var sealed = false
    private var resumable = false
    private var closing = false
    private var retiring = false
    private var inFlight = 0
    private var drained = CountDownLatch(0)
    private var retirementDone = CountDownLatch(0)

    fun report(progress: SearchProgressV1): Unit {
        val epoch = synchronized(lock) { if (revoked) null else activeEpoch } ?: return
        deliver(epoch, progress)
    }

    fun isRevoked(): Boolean = synchronized(lock) { revoked }

    fun runPhase(epoch: Long, run: (Long) -> String): String {
        return operation {
            synchronized(lock) {
                if (activeEpoch != null || epoch <= 0) throw IOException("search_phase_unavailable")
                activeEpoch = epoch
            }
            try { run(epoch) } finally { synchronized(lock) { activeEpoch = null } }
        }
    }

    fun <T> operation(resume: Boolean = false, whileSealed: Boolean = false, action: () -> T): T {
        synchronized(lock) {
            if (revoked || retiring || closing || sealed && !whileSealed && !(resume && resumable)) throw SearchPhaseUnavailable()
            if (resume && sealed) { sealed = false; resumable = false }
            if (inFlight++ == 0) drained = CountDownLatch(1)
        }
        try { return action() } finally {
            synchronized(lock) { if (--inFlight == 0) drained.countDown() }
        }
    }

    fun sealAndDrain(deadline: Long): Boolean {
        val completions = synchronized(lock) {
            if (revoked) return false
            sealed = true
            resumable = false
            closing = true
            drained to retirementDone
        }
        if (!await(completions.first, deadline) || !await(completions.second, deadline)) return false
        return synchronized(lock) { sealed && !revoked && inFlight == 0 && !retiring }
    }

    fun beginRetirement(deadline: Long): Boolean {
        val completion = synchronized(lock) {
            if (revoked || closing || retiring) return false
            sealed = true
            resumable = false
            retiring = true
            retirementDone = CountDownLatch(1)
            drained
        }
        if (await(completion, deadline) && !isRevoked()) return true
        endRetirement()
        return false
    }

    fun endRetirement(): Unit = synchronized(lock) {
        retiring = false
        retirementDone.countDown()
    }

    private fun await(completion: CountDownLatch, deadline: Long): Boolean {
        val now = nowMillis()
        if (now < 0 || now >= deadline) return false
        val done = try { completion.await(deadline - now, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        val after = nowMillis()
        return done && after >= 0 && after < deadline
    }

    fun allowResume(): Boolean = synchronized(lock) {
        if (revoked || closing || !sealed || inFlight != 0) false else { resumable = true; true }
    }

    fun before(deadline: Long): Boolean = nowMillis().let { it >= 0 && it < deadline }

    fun revoke(owner: SearchOwnerLease?): Unit = synchronized(lock) {
        revoked = true
        owner?.quarantine()
    }
}

internal class SearchMobilePhaseSession internal constructor(
    private val prepare: (Long) -> Unit,
    private val run: (Long) -> String,
    private val cancel: (Long) -> Boolean,
    private val openResult: () -> LocalOpenedResult,
    close: () -> Unit,
    private val progress: SearchMobilePhaseProgress,
    private val paused: (Long) -> SearchResourceStateV2,
    private val quarantineOwner: () -> Unit,
    private val owner: SearchOwnerLease? = null,
) : SearchPhaseSession {
    override val releaseOwner: SearchOwnerLease? get() = owner

    constructor(
        session: SearchSession,
        progress: SearchMobilePhaseProgress,
        paused: (Long) -> SearchResourceStateV2,
        quarantineOwner: () -> Unit,
        owner: SearchOwnerLease,
    ) : this(
        session::preparePhase, session::runPhase, session::cancelPhase,
        { openMobileResult(session) }, session::close, progress, paused, quarantineOwner, owner,
    )

    private val quarantineDispatched = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeComplete = AtomicBoolean(false)
    private val closeOnce = LeaseCloseOnce(close)

    override fun preparePhase(epoch: Long): Unit {
        ensureAvailable()
        progress.operation(resume = true) {
            if (closed.get()) throw SearchPhaseUnavailable()
            if (owner != null && !owner.resumeWorkers()) throw IOException("search_owner_unavailable")
            prepare(epoch)
        }
        ensureAvailable()
    }

    override fun runPhase(epoch: Long): String {
        ensureAvailable()
        val status = progress.runPhase(epoch, run)
        ensureAvailable()
        return status
    }

    override fun cancelPhase(epoch: Long): Boolean = if (closed.get()) false else try {
        progress.operation { if (closed.get()) false else cancel(epoch) }
    } catch (_: SearchPhaseUnavailable) { false }

    override fun openVerifiedResult(): LocalOpenedResult {
        ensureAvailable()
        val result = progress.operation(whileSealed = true) {
            if (closed.get()) throw SearchPhaseUnavailable()
            openResult()
        }
        if (progress.isRevoked() || closed.get()) {
            try { result.input.close() } catch (_: Exception) { }
            throw IOException("search_result_unavailable")
        }
        return result
    }

    override fun closeSession(deadlineElapsedMillis: Long): Unit {
        closed.set(true)
        if (!progress.sealAndDrain(deadlineElapsedMillis)) {
            quarantine()
            throw IOException("search_session_cleanup_unknown")
        }
        try {
            closeOnce.close()
            if (!progress.before(deadlineElapsedMillis) || progress.isRevoked()) throw IOException("search_session_cleanup_unknown")
            closeComplete.set(true)
        } catch (failure: Exception) {
            try { quarantine() } catch (_: Exception) { }
            throw failure
        }
    }

    override fun pauseResources(deadlineElapsedMillis: Long): SearchResourceStateV2 =
        retire(deadlineElapsedMillis, resumable = true)

    override fun retireResources(deadlineElapsedMillis: Long): SearchResourceStateV2 =
        retire(deadlineElapsedMillis, resumable = false)

    override fun releaseResources(deadlineElapsedMillis: Long): SearchResourceStateV2 {
        val lease = owner
        if (lease != null && closeComplete.get() && progress.sealAndDrain(deadlineElapsedMillis) &&
            lease.releaseUntil(deadlineElapsedMillis) && !progress.isRevoked()
        ) return SearchResourceStateV2.RELEASED
        quarantine()
        return SearchResourceStateV2.QUARANTINED
    }

    override fun quarantine(): Unit {
        progress.revoke(owner)
        if (quarantineDispatched.compareAndSet(false, true)) {
            quarantineOwner()
        }
    }

    private fun ensureAvailable(): Unit {
        if (progress.isRevoked() || closed.get()) throw IOException("search_session_unavailable")
    }

    private fun retire(deadline: Long, resumable: Boolean): SearchResourceStateV2 {
        if (!progress.beginRetirement(deadline)) { quarantine(); return SearchResourceStateV2.QUARANTINED }
        try {
            val result = try {
                if (owner != null && !owner.retireWorkerUntil(deadline)) SearchResourceStateV2.QUARANTINED else paused(deadline)
            } catch (_: Exception) { SearchResourceStateV2.QUARANTINED }
            catch (_: LinkageError) { SearchResourceStateV2.QUARANTINED }
            if (!progress.isRevoked() && progress.before(deadline) && result == SearchResourceStateV2.PAUSED_LEASE_HELD &&
                (!resumable || progress.allowResume())
            ) return result
            try { quarantine() } catch (_: Exception) { }
            return SearchResourceStateV2.QUARANTINED
        } finally {
            progress.endRetirement()
        }
    }
}
