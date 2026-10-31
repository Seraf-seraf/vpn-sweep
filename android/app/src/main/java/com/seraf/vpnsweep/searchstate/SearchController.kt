package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchFailureV1
import com.seraf.vpnsweep.searchprotocol.SearchPauseReasonV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstorage.Publication
import com.seraf.vpnsweep.searchstorage.PublicationToken
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStore
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SearchPhase { IDLE, RUNNING, PAUSING, PAUSED, CANCELLING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }
internal data class SearchUiState(
    val phase: SearchPhase = SearchPhase.IDLE,
    val progress: SearchProgressV1? = null,
    val pauseReason: SearchPauseReasonV1? = null,
    val failure: SearchFailureV1? = null,
    val resultCommitted: Boolean = false,
)

internal sealed interface SearchStartPreparation {
    class Denied(val result: SearchStartV3) : SearchStartPreparation
    class Reserved internal constructor(internal val dispatch: () -> SearchStartV3) : SearchStartPreparation
}

internal class SearchController(
    private val port: SearchSessionPortV3,
    private val store: ResultStore,
    private val marker: SessionMarker,
) {
    private enum class Control { PAUSE, CANCEL }

    private class Session(val generation: Long, val token: PublicationToken) {
        var phase = 1L
        var lastPhase = 1L
        var phasePaused = false
        var dispatching = true
        var dispatchStarted = false
        var callbackDuringDispatch = false
        var pendingControl: Control? = null
        var resultSeen = false
        var resultInFlight = false
        var committed = false
        var unknown = false
        var terminal: SearchTerminalV3? = null
        lateinit var observer: SearchSessionObserverV3
    }

    private var admissionBlocked = marker.read() != MarkerState.CLEAR
    private val mutableState = MutableStateFlow(SearchUiState(
        phase = if (admissionBlocked) SearchPhase.INTERRUPTED else SearchPhase.IDLE,
    ))
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var active: Session? = null
    private var pendingDeliveries = 0
    private val pendingResultClose = mutableListOf<AutoCloseable>()
    private var cleanupInProgress = false

    fun start(sourceRevision: Long, selectionRevision: Long, profiles: PrivateInputV1, vpnDisconnected: Boolean): SearchStartV3 =
        when (val prepared = reserveStart(sourceRevision, selectionRevision, profiles, vpnDisconnected)) {
            is SearchStartPreparation.Denied -> prepared.result
            is SearchStartPreparation.Reserved -> prepared.dispatch()
        }

    @Synchronized
    fun reserveStart(sourceRevision: Long, selectionRevision: Long, profiles: PrivateInputV1, vpnDisconnected: Boolean): SearchStartPreparation {
        if (!vpnDisconnected || active != null || admissionBlocked) return SearchStartPreparation.Denied(SearchStartV3.BUSY)
        val token = store.begin(ResultRevision(sourceRevision.toString(), selectionRevision.toString()))
            ?: return SearchStartPreparation.Denied(SearchStartV3.REJECTED)
        admissionBlocked = true
        if (!marker.markActive()) {
            store.cancel(token)
            mutableState.value = SearchUiState(phase = SearchPhase.INTERRUPTED, failure = SearchFailureV1.INTERRUPTED)
            return SearchStartPreparation.Denied(SearchStartV3.REJECTED)
        }
        val session = Session(++generation, token)
        session.observer = observerFor(session)
        active = session
        mutableState.value = SearchUiState(phase = SearchPhase.RUNNING)
        val snapshot = SearchSnapshotV1(session.generation, sourceRevision, selectionRevision, profiles)
        return SearchStartPreparation.Reserved { dispatchStart(session, snapshot) }
    }

    private fun dispatchStart(session: Session, snapshot: SearchSnapshotV1): SearchStartV3 {
        val phase = synchronized(this) {
            if (active !== session || session.dispatchStarted) return SearchStartV3.UNKNOWN
            session.dispatchStarted = true
            session.phase
        }
        val outcome = try {
            port.start(snapshot, phase, session.observer)
        } catch (_: Exception) {
            SearchStartV3.UNKNOWN
        }
        return finishDispatch(session, phase, null, outcome)
    }

    @Synchronized
    fun withSourceMutation(mutation: () -> Boolean): Boolean {
        if (active != null || admissionBlocked) return false
        return mutation()
    }

    fun pause(): Unit {
        val command = synchronized(this) {
            val session = active ?: return
            if (mutableState.value.phase != SearchPhase.RUNNING || session.unknown) return
            mutableState.value = mutableState.value.copy(phase = SearchPhase.PAUSING)
            if (session.dispatching) {
                session.pendingControl = Control.PAUSE
                null
            } else Triple(session, session.phase, Control.PAUSE)
        }
        if (command != null) sendControl(command.first, command.second, command.third)
    }

    fun resume(): SearchStartV3 {
        val prepared = synchronized(this) {
            val session = active ?: return SearchStartV3.REJECTED
            if (mutableState.value.phase != SearchPhase.PAUSED || session.dispatching || session.unknown) return SearchStartV3.REJECTED
            val previous = session.phase
            session.phase = ++session.lastPhase
            session.phasePaused = false
            session.dispatching = true
            session.callbackDuringDispatch = false
            session.pendingControl = null
            mutableState.value = mutableState.value.copy(phase = SearchPhase.RUNNING, pauseReason = null)
            Triple(session, previous, session.phase)
        }
        val outcome = try {
            port.resume(prepared.first.generation, prepared.third)
        } catch (_: Exception) {
            SearchStartV3.UNKNOWN
        }
        return finishDispatch(prepared.first, prepared.third, prepared.second, outcome)
    }

    fun cancel(): Unit {
        val command = synchronized(this) {
            val session = active ?: return
            if (session.unknown || !store.cancel(session.token)) return
            mutableState.value = mutableState.value.copy(phase = SearchPhase.CANCELLING)
            if (session.dispatching) {
                session.pendingControl = Control.CANCEL
                null
            } else Triple(session, session.phase, Control.CANCEL)
        }
        if (command != null) sendControl(command.first, command.second, command.third)
    }

    private fun sendControl(session: Session, phase: Long, control: Control): Unit {
        try {
            when (control) {
                Control.PAUSE -> port.pause(session.generation, phase)
                Control.CANCEL -> port.cancel(session.generation, phase)
            }
        } catch (_: Exception) {
            synchronized(this) { if (active === session) interrupt(session) }
        }
    }

    private fun finishDispatch(session: Session, phase: Long, previousPhase: Long?, outcome: SearchStartV3): SearchStartV3 {
        val command = synchronized(this) {
            if (active !== session || session.phase != phase) return outcome
            session.dispatching = false
            if (outcome == SearchStartV3.UNKNOWN || (outcome != SearchStartV3.ACCEPTED && session.callbackDuringDispatch)) {
                interrupt(session)
                return SearchStartV3.UNKNOWN
            }
            if (outcome != SearchStartV3.ACCEPTED) {
                if (previousPhase == null) {
                    store.cancel(session.token)
                    interrupt(session)
                    return outcome
                }
                session.phase = previousPhase
                session.phasePaused = true
                if (session.pendingControl == Control.CANCEL) {
                    session.pendingControl = null
                    Triple(session, previousPhase, Control.CANCEL)
                } else {
                    session.pendingControl = null
                    mutableState.value = mutableState.value.copy(phase = SearchPhase.PAUSED)
                    null
                }
            } else if (session.terminal != null) {
                finishTerminal(session)
                null
            } else if (!session.unknown && session.pendingControl != null) {
                Triple(session, phase, session.pendingControl!!).also { session.pendingControl = null }
            } else null
        }
        if (command != null) sendControl(command.first, command.second, command.third)
        return outcome
    }

    private fun observerFor(session: Session): SearchSessionObserverV3 = object : SearchSessionObserverV3 {
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit {
            synchronized(this@SearchController) {
                if (active !== session || progress.sessionGeneration != session.generation || phaseEpoch != session.phase || session.phasePaused || session.terminal != null || session.unknown) return
                if (session.dispatching) session.callbackDuringDispatch = true
                if (mutableState.value.phase != SearchPhase.CANCELLING) mutableState.value = mutableState.value.copy(progress = progress)
            }
        }

        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = result(session, offer)

        override fun onTerminal(terminal: SearchTerminalV3): Unit = terminal(session, terminal)
    }

    private fun result(session: Session, offer: SearchResultOfferV3): SearchResultReceiptV3 {
        val disposition = synchronized(this) {
            pendingDeliveries++
            val current = active === session && offer.sessionGeneration == session.generation && offer.phaseEpoch == session.phase && !session.phasePaused && session.terminal == null && !session.unknown
            if (!current || session.resultSeen) null
            else {
                session.resultSeen = true
                session.resultInFlight = true
                if (session.dispatching) session.callbackDuringDispatch = true
                when {
                    mutableState.value.phase == SearchPhase.CANCELLING -> Publication.CANCELLED
                    session.token.revision != ResultRevision(offer.sourceRevision.toString(), offer.selectionRevision.toString()) -> Publication.FAILED
                    else -> Publication.SUCCESS
                }
            }
        }
        var publication = disposition
        var stream: InputStream? = null
        var closeFailed = false
        try {
            if (publication == Publication.SUCCESS) {
                stream = try { offer.result.open() } catch (_: IOException) { null }
                publication = if (stream == null) Publication.FAILED else store.publish(session.token, stream, offer.resultSha256)
            }
        } catch (_: Exception) {
            publication = Publication.INDETERMINATE
        } finally {
            if (stream != null && !closeResultResource(stream)) closeFailed = true
            if (!closeResultResource(offer.result)) closeFailed = true
        }
        return synchronized(this) {
            pendingDeliveries--
            if (disposition == null) {
                if (closeFailed) interruptCurrentOr(session)
                return@synchronized if (closeFailed) SearchResultReceiptV3.UNKNOWN else SearchResultReceiptV3.IGNORED_CLOSED
            }
            session.resultInFlight = false
            if (publication == Publication.FAILED && mutableState.value.phase == SearchPhase.CANCELLING) publication = Publication.CANCELLED
            if (publication == Publication.SUCCESS) session.committed = true else store.cancel(session.token)
            if (closeFailed || publication == Publication.INDETERMINATE) {
                interruptCurrentOr(session)
                SearchResultReceiptV3.UNKNOWN
            } else {
                mutableState.value = mutableState.value.copy(resultCommitted = session.committed)
                when (publication) {
                    Publication.SUCCESS -> SearchResultReceiptV3.COMMITTED_CLOSED
                    Publication.CANCELLED -> SearchResultReceiptV3.CANCELLED_CLOSED
                    else -> SearchResultReceiptV3.FAILED_CLOSED
                }
            }
        }
    }

    private fun closeResultResource(resource: AutoCloseable): Boolean = try {
        resource.close()
        true
    } catch (_: Exception) {
        synchronized(this) { pendingResultClose.add(resource) }
        false
    }

    fun retryResultCleanup(): Boolean {
        val resources = synchronized(this) {
            if (cleanupInProgress) return false
            cleanupInProgress = true
            pendingResultClose.toList()
        }
        val closed = resources.filter { resource -> try { resource.close(); true } catch (_: Exception) { false } }
        return synchronized(this) {
            for (resource in closed) {
                val index = pendingResultClose.indexOfFirst { it === resource }
                if (index >= 0) pendingResultClose.removeAt(index)
            }
            cleanupInProgress = false
            pendingResultClose.isEmpty()
        }
    }

    private fun terminal(session: Session, terminal: SearchTerminalV3): Unit {
        synchronized(this) {
            if (active !== session || terminal.sessionGeneration != session.generation || terminal.phaseEpoch != session.phase || session.terminal != null || session.unknown) return
            if (session.dispatching) session.callbackDuringDispatch = true
            if (terminal is SearchTerminalV3.Paused) {
                if (session.phasePaused) return
                if (terminal.resources == SearchResourceStateV2.QUARANTINED || session.resultInFlight || session.committed) {
                    interrupt(session)
                } else {
                    session.phasePaused = true
                    if (session.pendingControl == Control.PAUSE) session.pendingControl = null
                    if (mutableState.value.phase != SearchPhase.CANCELLING) {
                        mutableState.value = mutableState.value.copy(phase = SearchPhase.PAUSED, pauseReason = terminal.reason)
                    }
                }
                return
            }
            session.terminal = terminal
            if (session.resultInFlight || pendingDeliveries != 0) interrupt(session)
            if (!session.dispatching) finishTerminal(session)
        }
    }

    private fun finishTerminal(session: Session): Unit {
        val terminal = session.terminal ?: return
        val fence = when (terminal) {
            is SearchTerminalV3.Complete -> terminal.resultFence
            is SearchTerminalV3.Cancelled -> terminal.resultFence
            is SearchTerminalV3.Stopped -> terminal.resultFence
            is SearchTerminalV3.Paused -> return
        }
        if (session.unknown || session.resultInFlight || pendingDeliveries != 0 || pendingResultClose.isNotEmpty() ||
            fence != SearchResultFenceV3.CONFIRMED || terminal.resources != SearchResourceStateV2.RELEASED ||
            (terminal is SearchTerminalV3.Complete && !session.committed)) {
            interrupt(session, terminal.resources == SearchResourceStateV2.QUARANTINED)
            return
        }
        if (!session.committed) store.cancel(session.token)
        if (!marker.markClear()) {
            interrupt(session)
            return
        }
        active = null
        admissionBlocked = false
        mutableState.value = SearchUiState(
            phase = when {
                session.committed -> SearchPhase.COMPLETE
                terminal is SearchTerminalV3.Cancelled -> SearchPhase.CANCELLED
                else -> SearchPhase.FAILED
            },
            failure = (terminal as? SearchTerminalV3.Stopped)?.reason,
            resultCommitted = session.committed,
        )
    }

    private fun interruptCurrentOr(session: Session): Unit {
        val current = active
        if (current != null) interrupt(current) else {
            admissionBlocked = true
            mutableState.value = SearchUiState(phase = SearchPhase.INTERRUPTED, failure = SearchFailureV1.INTERRUPTED, resultCommitted = session.committed)
        }
    }

    private fun interrupt(session: Session, workerQuarantined: Boolean = false): Unit {
        session.unknown = true
        admissionBlocked = true
        mutableState.value = SearchUiState(
            phase = SearchPhase.INTERRUPTED,
            failure = if (workerQuarantined) SearchFailureV1.WORKER_QUARANTINED else SearchFailureV1.INTERRUPTED,
            resultCommitted = session.committed,
        )
    }
}
