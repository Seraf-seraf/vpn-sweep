package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchControllerTest {
    @Test
    fun cancelWaitsForTerminalAndIgnoresLateResult(): Unit {
        val runtime = FakeSession()
        val storage = FakeStorage()
        val controller = SearchController(runtime, ResultStore(storage), FakeMarker())
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        controller.cancel()
        assertEquals(SearchPhase.CANCELLING, controller.state.value.phase)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
        val result = Input("result")
        runtime.complete(1, 1, 2, result)
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(true, result.closed)
        assertEquals(0, result.opens)
        assertEquals(0, storage.prepares)
        assertNull(storage.manifest)
    }

    @Test
    fun pausedSessionResumesAndOldEventsCannotChangeNewSession(): Unit {
        val runtime = FakeSession()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), FakeMarker())
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), false))
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        controller.pause()
        runtime.paused(1, SearchPauseReasonV1.BUDGET, SearchResourceStateV2.PAUSED_LEASE_HELD)
        assertEquals(SearchPhase.PAUSED, controller.state.value.phase)
        assertEquals(SearchStartV3.ACCEPTED, controller.resume())
        controller.cancel()
        runtime.cancelled(1, SearchResourceStateV2.RELEASED)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(3, 4, Input("profiles"), true))
        runtime.cancelled(1, SearchResourceStateV2.RELEASED)
        assertEquals(SearchPhase.RUNNING, controller.state.value.phase)
    }

    @Test
    fun cancelledDuringPrepareAndReadFailureReportsCancelled(): Unit {
        val runtime = FakeSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = FakeStorage()
        storage.prepareAction = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            throw IOException("synthetic")
        }
        val controller = SearchController(runtime, ResultStore(storage), FakeMarker())
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val result = Input("result")
        val worker = thread {
            runtime.complete(1, 1, 2, result)
        }
        check(entered.await(10, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        worker.join(10_000)
        assertEquals(false, worker.isAlive)
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(true, result.closed)
        assertNull(storage.manifest)
    }

    @Test
    fun cancelDuringCloseAfterOpenFailureReportsCancelled(): Unit {
        val runtime = FakeSession()
        val marker = FakeMarker()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = object : PrivateInputV1 {
            override fun open(): InputStream = throw IOException("synthetic")
            override fun close(): Unit {
                closing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val worker = thread {
            runtime.complete(1, 1, 2, result)
        }
        check(closing.await(10, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        worker.join(10_000)
        assertEquals(false, worker.isAlive)
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.state)
    }

    @Test
    fun failedResultCloseAfterCancelKeepsMarkerAndIsNotCancelled(): Unit {
        val runtime = FakeSession()
        val marker = FakeMarker()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = object : PrivateInputV1 {
            override fun open(): InputStream = throw IOException("synthetic")
            override fun close(): Unit {
                closing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                throw IOException("synthetic")
            }
        }
        val worker = thread {
            runtime.complete(1, 1, 2, result)
        }
        check(closing.await(10, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        worker.join(10_000)
        assertEquals(false, worker.isAlive)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.state)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
    }

    @Test
    fun failedCloseAfterCommittedResultPreservesCommitAndBlocks(): Unit {
        val runtime = FakeSession()
        val marker = FakeMarker()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val result = object : PrivateInputV1 {
            override fun open(): InputStream = "result".byteInputStream()
            override fun close(): Unit = throw IOException("synthetic")
        }
        runtime.complete(1, 1, 2, result)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(true, controller.state.value.resultCommitted)
        assertEquals(MarkerState.ACTIVE, marker.state)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
    }

    @Test
    fun staleResultCloseFailureRetainsMarkerForCurrentGeneration(): Unit {
        val runtime = FakeSession()
        val marker = FakeMarker()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        controller.cancel()
        runtime.cancelled(1, SearchResourceStateV2.RELEASED)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(3, 4, Input("profiles"), true))
        var failures = 1
        val stale = object : PrivateInputV1 {
            override fun open(): InputStream = error("must not open")
            override fun close(): Unit {
                if (failures-- > 0) throw IOException("synthetic")
            }
        }
        runtime.complete(1, 1, 2, stale)
        controller.cancel()
        runtime.cancelled(2, SearchResourceStateV2.RELEASED)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.state)
        assertEquals(SearchStartV3.BUSY, controller.start(3, 4, Input("profiles"), true))
        assertEquals(true, controller.retryResultCleanup())
        assertEquals(SearchStartV3.BUSY, controller.start(3, 4, Input("profiles"), true))
    }

    @Test
    fun activeMarkerRestoresInterruptedAndPreventsDispatch(): Unit {
        val marker = FakeMarker()
        val firstRuntime = FakeSession()
        val storage = FakeStorage()
        val previous = ResultManifest("old", ResultRevision("old-source", "old-selection"), "old-hash")
        storage.manifest = previous
        firstRuntime.onStart = { assertEquals(MarkerState.ACTIVE, marker.state) }
        val controller = SearchController(firstRuntime, ResultStore(storage), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val nextRuntime = FakeSession()
        val recovered = SearchController(nextRuntime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchPhase.INTERRUPTED, recovered.state.value.phase)
        assertEquals(SearchStartV3.BUSY, recovered.start(1, 2, Input("profiles"), true))
        assertNull(nextRuntime.observer)
        assertEquals(previous, storage.manifest)
    }

    @Test
    fun markerWriteFailurePreventsDispatchAndRestartsClosed(): Unit {
        val marker = FakeMarker()
        marker.writeFailure = true
        val runtime = FakeSession()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.REJECTED, controller.start(1, 2, Input("profiles"), true))
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
        assertNull(runtime.observer)
        val recovered = SearchController(FakeSession(), ResultStore(FakeStorage()), marker)
        assertEquals(SearchPhase.INTERRUPTED, recovered.state.value.phase)
    }

    @Test
    fun unknownMarkerFailsClosedWithoutStarting(): Unit {
        val marker = FakeMarker()
        marker.state = MarkerState.UNKNOWN
        val runtime = FakeSession()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
        assertNull(runtime.observer)
    }

    @Test
    fun releasedTerminalClearsMarkerAndAllowsNextStart(): Unit {
        val marker = FakeMarker()
        val runtime = FakeSession()
        val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        runtime.observer!!.onTerminal(SearchTerminalV3.Stopped(1, runtime.phase, SearchFailureV1.EXHAUSTED, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
        assertEquals(MarkerState.CLEAR, marker.state)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
    }

    @Test
    fun completedPublicationClearsMarkerOnlyAfterResultCommit(): Unit {
        val marker = FakeMarker()
        val runtime = FakeSession()
        val storage = FakeStorage()
        val controller = SearchController(runtime, ResultStore(storage), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        val result = Input("result")
        runtime.complete(1, 1, 2, result)
        assertEquals(SearchPhase.COMPLETE, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.state)
        assertEquals("one", storage.manifest?.generation)
        assertEquals(SearchPhase.IDLE, SearchController(FakeSession(), ResultStore(FakeStorage()), marker).state.value.phase)
    }

    @Test
    fun uncertainPublicationKeepsMarkerAcrossRestart(): Unit {
        val marker = FakeMarker()
        val runtime = FakeSession()
        val storage = FakeStorage()
        storage.prepareAction = { throw PreparationCleanupUnknownException(IOException("synthetic")) }
        val controller = SearchController(runtime, ResultStore(storage), marker)
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
        runtime.complete(1, 1, 2, Input("result"))
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, marker.state)
        val recovered = SearchController(FakeSession(), ResultStore(FakeStorage()), marker)
        assertEquals(SearchPhase.INTERRUPTED, recovered.state.value.phase)
        assertEquals(SearchStartV3.BUSY, recovered.start(1, 2, Input("profiles"), true))
    }

    @Test
    fun quarantineAndFailedClearBlockAnotherStartAndRecovery(): Unit {
        for (quarantined in listOf(true, false)) {
            val marker = FakeMarker()
            val runtime = FakeSession()
            val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
            assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
            marker.clearFailure = !quarantined
            val resource = if (quarantined) SearchResourceStateV2.QUARANTINED else SearchResourceStateV2.RELEASED
            runtime.cancelled(1, resource)
            assertEquals(if (quarantined) MarkerState.ACTIVE else MarkerState.UNKNOWN, marker.state)
            assertEquals(SearchStartV3.BUSY, controller.start(1, 2, Input("profiles"), true))
            assertEquals(SearchPhase.INTERRUPTED, SearchController(FakeSession(), ResultStore(FakeStorage()), marker).state.value.phase)
        }
    }

    @Test
    fun pausedStatesKeepMarkerEvenWhenResourcesReleased(): Unit {
        for (resource in listOf(SearchResourceStateV2.RELEASED, SearchResourceStateV2.PAUSED_LEASE_HELD)) {
            val marker = FakeMarker()
            val runtime = FakeSession()
            val controller = SearchController(runtime, ResultStore(FakeStorage()), marker)
            assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, Input("profiles"), true))
            controller.pause()
            runtime.paused(1, SearchPauseReasonV1.USER_REQUEST, resource)
            assertEquals(SearchPhase.PAUSED, controller.state.value.phase)
            assertEquals(MarkerState.ACTIVE, marker.state)
            assertEquals(SearchPhase.INTERRUPTED, SearchController(FakeSession(), ResultStore(FakeStorage()), marker).state.value.phase)
            assertEquals(SearchStartV3.ACCEPTED, controller.resume())
        }
    }

    private class FakeMarker : SessionMarker {
        var state = MarkerState.CLEAR
        var writeFailure = false
        var clearFailure = false
        override fun read(): MarkerState = state
        override fun markActive(): Boolean {
            if (writeFailure) { state = MarkerState.UNKNOWN; return false }
            state = MarkerState.ACTIVE
            return true
        }
        override fun markClear(): Boolean {
            if (clearFailure) { state = MarkerState.UNKNOWN; return false }
            state = MarkerState.CLEAR
            return true
        }
    }

    private class Input(value: String) : PrivateInputV1 {
        val bytes = value.toByteArray()
        var closed = false
        var opens = 0
        override fun open(): InputStream {
            opens++
            return ByteArrayInputStream(bytes)
        }
        override fun close(): Unit { closed = true }
    }

    private class FakeSession : SearchSessionPortV4 {
        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean = true
        var observer: SearchSessionObserverV3? = null
        var phase = 1L
        var onStart: () -> Unit = {}
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            phase = phaseEpoch
            this.observer = observer
            onStart()
            return SearchStartV3.ACCEPTED
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 {
            phase = phaseEpoch
            return SearchStartV3.ACCEPTED
        }
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit

        fun paused(generation: Long, reason: SearchPauseReasonV1, resources: SearchResourceStateV2): Unit {
            observer!!.onTerminal(SearchTerminalV3.Paused(generation, phase, reason, resources))
        }
        fun cancelled(generation: Long, resources: SearchResourceStateV2): Unit {
            observer!!.onTerminal(SearchTerminalV3.Cancelled(generation, phase, resources, SearchResultFenceV3.CONFIRMED))
        }
        fun complete(generation: Long, source: Long, selection: Long, result: PrivateInputV1): Unit {
            val receipt = observer!!.onResult(SearchResultOfferV3(generation, phase, source, selection, digest("result".byteInputStream()), result))
            when (receipt) {
                SearchResultReceiptV3.COMMITTED_CLOSED -> observer!!.onTerminal(SearchTerminalV3.Complete(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
                SearchResultReceiptV3.CANCELLED_CLOSED -> cancelled(generation, SearchResourceStateV2.RELEASED)
                SearchResultReceiptV3.FAILED_CLOSED -> observer!!.onTerminal(SearchTerminalV3.Stopped(generation, phase, SearchFailureV1.INTERNAL_ERROR, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
                SearchResultReceiptV3.UNKNOWN, SearchResultReceiptV3.IGNORED_CLOSED -> Unit
            }
        }
    }

    private class FakeStorage : ResultStorage {
        var result: StoredResult? = null
        var manifest: ResultManifest? = null
        var prepares = 0
        var prepareAction: () -> Unit = {}
        override fun discard(generation: String): Unit { result = null }
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            prepares++
            prepareAction()
            result = StoredResult(revision, sha256, input.readBytes().inputStream())
            return "one"
        }
        override fun readGeneration(generation: String): StoredResult? = result
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
    }
}
