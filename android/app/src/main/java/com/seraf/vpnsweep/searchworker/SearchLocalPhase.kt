package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.core.mobile.SearchSession
import com.seraf.vpnsweep.core.mobile.SearchVerifiedResult
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal data class LocalOpenedResult(val digest: String, val input: PrivateInputV1)

internal sealed interface LocalPhaseOutcome {
    data object NotStarted : LocalPhaseOutcome
    data object Paused : LocalPhaseOutcome
    data object Interrupted : LocalPhaseOutcome
    data object Stopped : LocalPhaseOutcome
    data object Unknown : LocalPhaseOutcome
    data class Handoff(val result: HandoffResult, val localFence: SearchResultFenceV3) : LocalPhaseOutcome
}

// The session owner transfers exclusive use of the Go session to this phase.
// execute runs away from Binder-death/revoke threads. Retirement keeps the
// anchor held; release follows result disposition and session close.
internal class SearchLocalPhase internal constructor(
    private val sessionGeneration: Long,
    private val phaseEpoch: Long,
    private val sourceRevision: Long,
    private val selectionRevision: Long,
    observer: SearchSessionObserverV3,
    private val runSession: () -> String,
    private val openResult: () -> LocalOpenedResult,
    private val closeSession: () -> Unit,
    private val retireWorker: () -> SearchResourceStateV2,
    private val releaseLease: () -> SearchResourceStateV2,
) {
    private val lock = Any()
    private var started = false
    private var revoked = false
    private val fence = ResultHandoffFence(sessionGeneration, phaseEpoch, object : SearchSessionObserverV3 {
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = observer.onProgress(phaseEpoch, progress)
        override fun onTerminal(terminal: SearchTerminalV3): Unit = observer.onTerminal(terminal)
        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
            val receipt = observer.onResult(offer)
            val input = offer.result as? TrackedInput ?: return SearchResultReceiptV3.UNKNOWN
            return if (input.closedFor(receipt)) receipt else SearchResultReceiptV3.UNKNOWN
        }
    })

    // Only fences future result delivery; the owner schedules Go cancellation
    // separately, so a blocked Go callback cannot hold the death thread.
    fun revoke(): Unit {
        synchronized(lock) { revoked = true }
        fence.cancel()
    }

    fun execute(): LocalPhaseOutcome {
        synchronized(lock) {
            if (started || revoked) return LocalPhaseOutcome.NotStarted
            started = true
        }
        val status = try {
            runSession()
        } catch (failure: Throwable) {
            fence.quarantine()
            rethrowFatal(failure)
            return LocalPhaseOutcome.Unknown
        }
        when (status) {
            "paused_budget" -> return LocalPhaseOutcome.Paused
            "interrupted" -> return LocalPhaseOutcome.Interrupted
            "success" -> Unit
            else -> return LocalPhaseOutcome.Stopped
        }
        val resources = try {
            retireWorker()
        } catch (failure: Throwable) {
            fence.quarantine()
            closeAfterUnknown()
            rethrowFatal(failure)
            return LocalPhaseOutcome.Unknown
        }
        fence.workerState(resources)
        if (resources != SearchResourceStateV2.PAUSED_LEASE_HELD) {
            fence.quarantine()
            closeAfterUnknown()
            return LocalPhaseOutcome.Unknown
        }
        val localOpen = fence.reserveLocalOpen()
        val handoff = if (localOpen == null) {
            HandoffResult.Discarded
        } else {
            localOpen.run {
                val opened = openResult()
                SearchResultOfferV3(
                    sessionGeneration,
                    phaseEpoch,
                    sourceRevision,
                    selectionRevision,
                    opened.digest,
                    TrackedInput(opened.input),
                )
            }
        }
        try {
            closeSession()
        } catch (failure: Throwable) {
            fence.quarantine()
            rethrowFatal(failure)
            return LocalPhaseOutcome.Unknown
        }
        if (handoff == HandoffResult.Unknown || handoff == HandoffResult.Rejected) {
            fence.quarantine()
            return LocalPhaseOutcome.Handoff(handoff, SearchResultFenceV3.UNKNOWN)
        }
        val released = try {
            releaseLease()
        } catch (failure: Throwable) {
            fence.quarantine()
            rethrowFatal(failure)
            return LocalPhaseOutcome.Unknown
        }
        fence.workerState(released)
        if (released != SearchResourceStateV2.RELEASED) {
            fence.quarantine()
            return LocalPhaseOutcome.Unknown
        }
        fence.seal()
        val localFence = fence.resultFence() ?: SearchResultFenceV3.UNKNOWN
        return LocalPhaseOutcome.Handoff(handoff, localFence)
    }

    private fun closeAfterUnknown(): Unit {
        try {
            closeSession()
        } catch (failure: Throwable) {
            rethrowFatal(failure)
        }
    }

    companion object {
        fun forMobile(
            session: SearchSession,
            sessionGeneration: Long,
            phaseEpoch: Long,
            sourceRevision: Long,
            selectionRevision: Long,
            observer: SearchSessionObserverV3,
            retireWorker: () -> SearchResourceStateV2,
            releaseLease: () -> SearchResourceStateV2,
        ): SearchLocalPhase = SearchLocalPhase(
            sessionGeneration, phaseEpoch, sourceRevision, selectionRevision, observer,
            session::run, { openMobileResult(session) }, session::close, retireWorker, releaseLease,
        )
    }
}

private fun rethrowFatal(failure: Throwable): Unit {
    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
}

internal fun openMobileResult(session: SearchSession): LocalOpenedResult {
    val result = session.takeVerifiedResult() ?: throw IOException("search_result_unavailable")
    try {
        val digest = result.digest()
        if (digest == null || !digest.matches(Regex("[0-9a-f]{64}"))) throw IOException("search_result_unavailable")
        return LocalOpenedResult(digest, MobileResultInput(result))
    } catch (failure: Throwable) {
        try {
            result.close()
        } catch (_: Exception) {
            // The surrounding fence treats the whole open as unknown.
        }
        throw failure
    }
}

private class MobileResultInput(private val result: SearchVerifiedResult) : PrivateInputV1 {
    private val lock = Any()
    private var opened = false
    private var closed = false
    private var closeUnknown = false

    override fun open(): InputStream = synchronized(lock) {
        check(!opened && !closed) { "search_result_unavailable" }
        opened = true
        object : InputStream() {
            private var streamClosed = false
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
                if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
                if (streamClosed || closed) throw IOException("search_result_closed")
                if (length == 0) return@synchronized 0
                val chunk = try {
                    result.readChunk(minOf(length, MAX_CHUNK_BYTES).toLong()) ?: ByteArray(0)
                } catch (_: Exception) {
                    throw IOException("search_result_read_failed")
                }
                if (chunk.isEmpty()) return@synchronized -1
                if (chunk.size > length) throw IOException("search_result_read_failed")
                chunk.copyInto(bytes, offset)
                chunk.size
            }
            override fun close(): Unit = synchronized(lock) { streamClosed = true }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (!closed) {
            closed = true
            try {
                result.close()
            } catch (_: Exception) {
                closeUnknown = true
            }
        }
        if (closeUnknown) throw IOException("search_result_close_unknown")
    }

    private companion object { const val MAX_CHUNK_BYTES = 64 * 1024 }
}

private class TrackedInput(private val input: PrivateInputV1) : PrivateInputV1 {
    private val lock = Any()
    private var opened = false
    private var streamClosed = false
    private var streamCloseAttempted = false
    private var inputClosed = false
    private var inputCloseAttempted = false
    private var unknown = false

    override fun open(): InputStream = synchronized(lock) {
        check(!opened && !inputClosed) { "search_result_unavailable" }
        opened = true
        val stream = input.open()
        object : FilterInputStream(stream) {
            override fun close(): Unit = synchronized(lock) {
                if (streamCloseAttempted) {
                    if (!streamClosed) throw IOException("search_result_close_unknown")
                    return@synchronized
                }
                streamCloseAttempted = true
                try {
                    super.close()
                    streamClosed = true
                } catch (failure: Throwable) {
                    unknown = true
                    throw failure
                }
            }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (inputCloseAttempted) {
            if (!inputClosed) throw IOException("search_result_close_unknown")
            return@synchronized
        }
        inputCloseAttempted = true
        try {
            input.close()
            inputClosed = true
        } catch (failure: Throwable) {
            unknown = true
            throw failure
        }
    }

    fun closedFor(receipt: SearchResultReceiptV3): Boolean = synchronized(lock) {
        receipt != SearchResultReceiptV3.UNKNOWN && !unknown && inputClosed && (!opened || streamClosed) &&
            (receipt != SearchResultReceiptV3.COMMITTED_CLOSED || opened)
    }
}
