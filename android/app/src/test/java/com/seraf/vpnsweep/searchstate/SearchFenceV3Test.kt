package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SearchFenceV3Test {
    @Test
    fun committedReceiptDoesNotClearUntilConfirmedFence(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input(), true))
        val input = Input()
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(input))
        assertTrue(input.closed)
        assertTrue(input.streamClosed)
        assertTrue(controller.state.value.resultCommitted)
        assertEquals(MarkerState.ACTIVE, marker.value)
        assertEquals(SearchPhase.INTERRUPTED, SearchController(Port(), ResultStore(Storage()), marker).state.value.phase)
        port.terminal(SearchResultFenceV3.CONFIRMED)
        assertEquals(SearchPhase.COMPLETE, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
        assertNotNull(storage.manifest)
    }

    @Test
    fun unknownFenceBlocksButPreservesConfirmedCommit(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        controller.start(1, 2, Input(), true)
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(Input()))
        controller.cancel()
        port.terminal(SearchResultFenceV3.UNKNOWN)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(SearchFailureV1.INTERRUPTED, controller.state.value.failure)
        assertTrue(controller.state.value.resultCommitted)
        assertNotNull(storage.manifest)
        assertEquals(MarkerState.ACTIVE, marker.value)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input(), true))
    }

    @Test
    fun cancellationBeforeCommitKeepsPreviousAndRequiresFence(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        val previous = ResultManifest("previous", ResultRevision("1", "2"), "previous")
        storage.manifest = previous
        controller.start(1, 2, Input(), true)
        controller.cancel()
        val input = Input()
        assertEquals(SearchResultReceiptV3.CANCELLED_CLOSED, port.offer(input))
        assertEquals(0, input.opens)
        assertEquals(previous, storage.manifest)
        assertEquals(MarkerState.ACTIVE, marker.value)
        port.observer!!.onTerminal(SearchTerminalV3.Cancelled(1, 1, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
    }

    @Test
    fun stalePhaseAndDuplicateCloseWithoutOpeningOrStateChange(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        controller.start(1, 2, Input(), true)
        controller.pause()
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
        assertEquals(SearchStartV3.ACCEPTED, controller.resume())
        assertEquals(2L, port.phase)
        val stale = Input()
        assertEquals(SearchResultReceiptV3.IGNORED_CLOSED, port.offer(stale, phase = 1))
        assertEquals(0, stale.opens)
        assertTrue(stale.closed)
        assertEquals(SearchPhase.RUNNING, controller.state.value.phase)
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(Input()))
        val duplicate = Input()
        assertEquals(SearchResultReceiptV3.IGNORED_CLOSED, port.offer(duplicate))
        assertEquals(0, duplicate.opens)
        assertEquals(MarkerState.ACTIVE, marker.value)
        port.terminal(SearchResultFenceV3.CONFIRMED)
        assertEquals(MarkerState.CLEAR, marker.value)
    }

    @Test
    fun failedCloseAndPrematureFenceRemainInterruptedAcrossRestart(): Unit {
        for (streamFailure in listOf(false, true)) {
            val marker = Marker()
            val port = Port()
            val controller = SearchController(port, ResultStore(Storage()), marker)
            controller.start(1, 2, Input(), true)
            val input = Input(failInputClose = !streamFailure, failStreamClose = streamFailure)
            assertEquals(SearchResultReceiptV3.UNKNOWN, port.offer(input))
            port.terminal(SearchResultFenceV3.CONFIRMED)
            assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
            assertTrue(controller.state.value.resultCommitted)
            assertEquals(MarkerState.ACTIVE, marker.value)
            assertEquals(SearchPhase.INTERRUPTED, SearchController(Port(), ResultStore(Storage()), marker).state.value.phase)
        }
    }

    @Test
    fun terminalDuringDeliveryCannotClearAfterDeliveryCompletes(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        controller.start(1, 2, Input(), true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Input(closeAction = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
        val worker = thread { port.offer(result) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        port.terminal(SearchResultFenceV3.CONFIRMED)
        release.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.value)
    }

    @Test
    fun asyncCallbackAndCancellationDuringStartDoNotWaitForControllerLock(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        port.onStart = { observer ->
            val callback = thread {
                observer.onProgress(1, SearchProgressV1(1, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 0u, 0, 0, 0, 0, 0))
                entered.countDown()
            }
            callback.join(10_000)
            check(!callback.isAlive)
            check(release.await(10, TimeUnit.SECONDS))
        }
        val start = thread { assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input(), true)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        start.join(10_000)
        assertFalse(start.isAlive)
        assertEquals(listOf(1L to 1L), port.cancellations)
        assertEquals(SearchPhase.CANCELLING, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.value)
    }

    @Test
    fun rejectedResumeEpochIsNeverReusedAndSyncResumeCallbackIsAccepted(): Unit {
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), Marker())
        controller.start(1, 2, Input(), true)
        controller.pause()
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
        port.resumeResult = SearchStartV3.REJECTED
        assertEquals(SearchStartV3.REJECTED, controller.resume())
        port.resumeResult = SearchStartV3.ACCEPTED
        port.onResume = { phase ->
            port.observer!!.onProgress(phase, SearchProgressV1(1, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 0u, 0, 0, 0, 0, 0))
        }
        assertEquals(SearchStartV3.ACCEPTED, controller.resume())
        assertEquals(listOf(2L, 3L), port.resumePhases)
        assertNotNull(controller.state.value.progress)
    }

    @Test
    fun synchronousResultAndTerminalDuringStartUseReservedOwnerAndPhase(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        port.onStart = {
            assertEquals(MarkerState.ACTIVE, marker.value)
            assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(Input()))
            port.terminal(SearchResultFenceV3.CONFIRMED)
            assertEquals(MarkerState.ACTIVE, marker.value)
        }
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input(), true))
        assertEquals(SearchPhase.COMPLETE, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
    }

    @Test
    fun oldObserverAndLateCloseFailureDoNotRewriteDurableMarker(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        controller.start(1, 2, Input(), true)
        val oldObserver = port.observer!!
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(Input()))
        port.terminal(SearchResultFenceV3.CONFIRMED)
        assertEquals(MarkerState.CLEAR, marker.value)
        val activeWrites = marker.activeWrites
        val late = Input(failInputClose = true)
        assertEquals(SearchResultReceiptV3.UNKNOWN, oldObserver.onResult(
            SearchResultOfferV3(1, 1, 1, 2, "unused", late),
        ))
        assertEquals(0, late.opens)
        assertEquals(activeWrites, marker.activeWrites)
        assertEquals(MarkerState.CLEAR, marker.value)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input(), true))
    }

    @Test
    fun pausedPhaseRejectsLateResultBeforeResumeWithoutOpeningIt(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        controller.start(1, 2, Input(), true)
        controller.pause()
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
        val late = Input()
        assertEquals(SearchResultReceiptV3.IGNORED_CLOSED, port.offer(late, phase = 1))
        assertEquals(0, late.opens)
        assertTrue(late.closed)
        assertNull(storage.manifest)
        assertEquals(SearchPhase.PAUSED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.value)
        assertEquals(SearchStartV3.ACCEPTED, controller.resume())
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(Input(), phase = 2))
        port.terminal(SearchResultFenceV3.CONFIRMED)
        assertEquals(SearchPhase.COMPLETE, controller.state.value.phase)
    }

    @Test
    fun pausedPhaseIgnoresDuplicatePauseAndLateProgressBeforeResume(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        controller.start(1, 2, Input(), true)
        controller.pause()
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.BUDGET, SearchResourceStateV2.RELEASED))
        port.observer!!.onProgress(1, SearchProgressV1(1, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 0u, 0, 0, 0, 0, 0))
        assertEquals(SearchPauseReasonV1.USER_REQUEST, controller.state.value.pauseReason)
        assertNull(controller.state.value.progress)
        assertEquals(SearchPhase.PAUSED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.value)
    }

    @Test
    fun cancellationFromPausedStillAcceptsFinalCancelledFence(): Unit {
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, ResultStore(Storage()), marker)
        controller.start(1, 2, Input(), true)
        controller.pause()
        port.observer!!.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
        controller.cancel()
        val late = Input()
        assertEquals(SearchResultReceiptV3.IGNORED_CLOSED, port.offer(late, phase = 1))
        assertEquals(0, late.opens)
        assertEquals(SearchPhase.CANCELLING, controller.state.value.phase)
        port.observer!!.onTerminal(SearchTerminalV3.Cancelled(1, 1, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
    }

    @Test
    fun synchronousPausedConsumesQueuedPauseButPreservesQueuedCancel(): Unit {
        for (cancel in listOf(false, true)) {
            val marker = Marker()
            val port = Port()
            val controller = SearchController(port, ResultStore(Storage()), marker)
            port.onStart = { observer ->
                controller.pause()
                if (cancel) controller.cancel()
                observer.onTerminal(SearchTerminalV3.Paused(1, 1, SearchPauseReasonV1.USER_REQUEST, SearchResourceStateV2.PAUSED_LEASE_HELD))
            }
            assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input(), true))
            assertTrue(port.pauses.isEmpty())
            assertEquals(if (cancel) listOf(1L to 1L) else emptyList<Pair<Long, Long>>(), port.cancellations)
            assertEquals(if (cancel) SearchPhase.CANCELLING else SearchPhase.PAUSED, controller.state.value.phase)
            assertEquals(MarkerState.ACTIVE, marker.value)
        }
    }

    private class Marker : SessionMarker {
        var value = MarkerState.CLEAR
        var activeWrites = 0
        override fun read(): MarkerState = value
        override fun markActive(): Boolean { activeWrites++; value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { value = MarkerState.CLEAR; return true }
    }

    private class Input(
        val failInputClose: Boolean = false,
        val failStreamClose: Boolean = false,
        val closeAction: () -> Unit = {},
    ) : PrivateInputV1 {
        var opens = 0
        var closed = false
        var streamClosed = false
        override fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream("result".toByteArray()) {
                override fun close(): Unit {
                    if (failStreamClose) throw IOException("synthetic")
                    streamClosed = true
                    super.close()
                }
            }
        }
        override fun close(): Unit {
            closeAction()
            if (failInputClose) throw IOException("synthetic")
            closed = true
        }
    }

    private class Port : SearchSessionPortV3 {
        var observer: SearchSessionObserverV3? = null
        var phase = 1L
        var onStart: (SearchSessionObserverV3) -> Unit = {}
        var onResume: (Long) -> Unit = {}
        var resumeResult = SearchStartV3.ACCEPTED
        val resumePhases = mutableListOf<Long>()
        val pauses = mutableListOf<Pair<Long, Long>>()
        val cancellations = mutableListOf<Pair<Long, Long>>()
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            this.observer = observer
            phase = phaseEpoch
            onStart(observer)
            return SearchStartV3.ACCEPTED
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit { pauses.add(sessionGeneration to phaseEpoch) }
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 {
            phase = phaseEpoch
            resumePhases.add(phaseEpoch)
            onResume(phaseEpoch)
            return resumeResult
        }
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit { cancellations.add(sessionGeneration to phaseEpoch) }
        fun offer(input: PrivateInputV1, phase: Long = this.phase): SearchResultReceiptV3 = observer!!.onResult(
            SearchResultOfferV3(1, phase, 1, 2, digest("result".byteInputStream()), input),
        )
        fun terminal(fence: SearchResultFenceV3): Unit = observer!!.onTerminal(
            SearchTerminalV3.Complete(1, phase, SearchResourceStateV2.RELEASED, fence),
        )
    }

    private class Storage : ResultStorage {
        var stored: StoredResult? = null
        var manifest: ResultManifest? = null
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            stored = StoredResult(revision, sha256, input.readBytes().inputStream())
            return "result"
        }
        override fun readGeneration(generation: String): StoredResult? = stored
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { stored = null }
    }
}
