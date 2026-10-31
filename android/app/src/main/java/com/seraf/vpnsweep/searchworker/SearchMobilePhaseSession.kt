package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.core.mobile.SearchSession
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// The reporter must be wired only to SearchEngineWorkerAdapter's synchronous
// Go progress callback for the same session as runPhase.
internal class SearchMobilePhaseProgress(private val deliver: (Long, SearchProgressV1) -> Unit) {
    private val lock = Any()
    private var activeEpoch: Long? = null
    private var revoked = false

    fun report(progress: SearchProgressV1): Unit {
        val epoch = synchronized(lock) { if (revoked) null else activeEpoch } ?: return
        deliver(epoch, progress)
    }

    fun admit(): Unit = synchronized(lock) {
        if (revoked) throw IOException("search_phase_unavailable")
    }

    fun isRevoked(): Boolean = synchronized(lock) { revoked }

    fun runPhase(epoch: Long, run: (Long) -> String): String {
        synchronized(lock) {
            if (revoked || activeEpoch != null || epoch <= 0) throw IOException("search_phase_unavailable")
            activeEpoch = epoch
        }
        try {
            return run(epoch)
        } finally {
            synchronized(lock) { activeEpoch = null }
        }
    }

    fun revoke(): Unit = synchronized(lock) { revoked = true }
}

internal class SearchMobilePhaseSession internal constructor(
    private val prepare: (Long) -> Unit,
    private val run: (Long) -> String,
    private val cancel: (Long) -> Boolean,
    private val openResult: () -> LocalOpenedResult,
    close: () -> Unit,
    private val progress: SearchMobilePhaseProgress,
    private val paused: () -> SearchResourceStateV2,
    private val quarantineOwner: () -> Unit,
) : SearchPhaseSession {
    constructor(
        session: SearchSession,
        progress: SearchMobilePhaseProgress,
        paused: () -> SearchResourceStateV2,
        quarantineOwner: () -> Unit,
    ) : this(
        session::preparePhase, session::runPhase, session::cancelPhase,
        { openMobileResult(session) }, session::close, progress, paused, quarantineOwner,
    )

    private val quarantineDispatched = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeOnce = LeaseCloseOnce(close)

    override fun preparePhase(epoch: Long): Unit {
        ensureAvailable()
        progress.admit()
        prepare(epoch)
        ensureAvailable()
    }

    override fun runPhase(epoch: Long): String {
        ensureAvailable()
        val status = progress.runPhase(epoch, run)
        ensureAvailable()
        return status
    }

    override fun cancelPhase(epoch: Long): Boolean = if (closed.get()) false else cancel(epoch)

    override fun openVerifiedResult(): LocalOpenedResult {
        ensureAvailable()
        progress.admit()
        val result = openResult()
        if (progress.isRevoked() || closed.get()) {
            try { result.input.close() } catch (_: Exception) { }
            throw IOException("search_result_unavailable")
        }
        return result
    }

    override fun closeSession(): Unit {
        progress.revoke()
        closed.set(true)
        try {
            closeOnce.close()
        } catch (failure: Exception) {
            try { quarantine() } catch (_: Exception) { }
            throw failure
        }
    }

    override fun pauseResources(): SearchResourceStateV2 = resources(SearchResourceStateV2.PAUSED_LEASE_HELD, paused)
    override fun releaseResources(): SearchResourceStateV2 {
        try { quarantine() } catch (_: Exception) { }
        return SearchResourceStateV2.QUARANTINED
    }

    override fun quarantine(): Unit {
        progress.revoke()
        if (quarantineDispatched.compareAndSet(false, true)) {
            quarantineOwner()
        }
    }

    private fun ensureAvailable(): Unit {
        if (progress.isRevoked() || closed.get()) throw IOException("search_session_unavailable")
    }

    private fun resources(expected: SearchResourceStateV2, action: () -> SearchResourceStateV2): SearchResourceStateV2 {
        try { progress.admit() } catch (_: IOException) { return SearchResourceStateV2.QUARANTINED }
        val result = try { action() } catch (_: Exception) { SearchResourceStateV2.QUARANTINED }
        catch (_: LinkageError) { SearchResourceStateV2.QUARANTINED }
        if (!progress.isRevoked() && result == expected) return result
        try { quarantine() } catch (_: Exception) { }
        return SearchResourceStateV2.QUARANTINED
    }
}
