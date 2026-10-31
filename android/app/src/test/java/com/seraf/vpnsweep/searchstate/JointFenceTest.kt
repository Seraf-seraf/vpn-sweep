package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchworker.HandoffResult
import com.seraf.vpnsweep.searchworker.ResultHandoffFence
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class JointFenceTest {
    @Test
    fun realFenceAndControllerRequireReceiptBeforeClear(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        val profiles = Input()
        assertEquals(SearchStartV3.ACCEPTED, controller.start(1, 2, profiles, true))
        assertTrue(profiles.closed)
        port.fence.workerState(SearchResourceStateV2.RELEASED)
        val operation = port.fence.reserveLocalOpen()!!
        port.fence.seal()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = Input(closeAction = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) })
        var handoff: HandoffResult? = null
        val worker = thread { handoff = operation.run { port.offer(result) } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertNull(port.fence.resultFence())
        assertEquals(MarkerState.ACTIVE, marker.value)
        assertEquals(SearchPhase.INTERRUPTED, SearchController(Port(), ResultStore(Storage()), marker).state.value.phase)
        release.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.COMMITTED_CLOSED), handoff)
        assertTrue(result.closed)
        assertTrue(result.streamClosed)
        assertEquals(SearchResultFenceV3.CONFIRMED, port.fence.resultFence())
        assertEquals(MarkerState.ACTIVE, marker.value)
        port.complete()
        assertEquals(SearchPhase.COMPLETE, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
        assertNotNull(storage.manifest)
    }

    @Test
    fun realFencePropagatesUnknownReceiverCloseAndRetainsCommit(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val controller = SearchController(port, ResultStore(storage), marker)
        controller.start(1, 2, Input(), true)
        port.fence.workerState(SearchResourceStateV2.RELEASED)
        val operation = port.fence.reserveLocalOpen()!!
        port.fence.seal()
        val input = Input(failClose = true)
        assertEquals(HandoffResult.Unknown, operation.run { port.offer(input) })
        assertEquals(SearchResultFenceV3.UNKNOWN, port.fence.resultFence())
        assertEquals(SearchPhase.INTERRUPTED, controller.state.value.phase)
        assertTrue(controller.state.value.resultCommitted)
        assertNotNull(storage.manifest)
        assertEquals(MarkerState.ACTIVE, marker.value)
        port.complete()
        assertEquals(MarkerState.ACTIVE, marker.value)
        assertEquals(SearchPhase.INTERRUPTED, SearchController(Port(), ResultStore(Storage()), marker).state.value.phase)
    }

    @Test
    fun cancellationDuringPrepareReturnsClosedReceiptAndKeepsPrevious(): Unit {
        val marker = Marker()
        val port = Port()
        val storage = Storage()
        val previous = ResultManifest("previous", ResultRevision("1", "2"), "previous")
        storage.manifest = previous
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        storage.prepareAction = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val controller = SearchController(port, ResultStore(storage), marker)
        controller.start(1, 2, Input(), true)
        port.fence.workerState(SearchResourceStateV2.RELEASED)
        val operation = port.fence.reserveLocalOpen()!!
        port.fence.seal()
        val result = Input()
        var handoff: HandoffResult? = null
        val worker = thread { handoff = operation.run { port.offer(result) } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.CANCELLED_CLOSED), handoff)
        assertEquals(previous, storage.manifest)
        assertTrue(result.closed)
        assertTrue(result.streamClosed)
        assertEquals(SearchResultFenceV3.CONFIRMED, port.fence.resultFence())
        port.cancelled()
        assertEquals(SearchPhase.CANCELLED, controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, marker.value)
    }

    private class Port : SearchSessionPortV3 {
        lateinit var fence: ResultHandoffFence
        lateinit var observer: SearchSessionObserverV3
        private var generation = 0L
        private var phase = 0L
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            generation = snapshot.sessionGeneration
            phase = phaseEpoch
            this.observer = observer
            fence = ResultHandoffFence(generation, phase, observer)
            snapshot.profiles.close()
            return SearchStartV3.ACCEPTED
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.REJECTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit { fence.cancel() }
        fun offer(input: PrivateInputV1): SearchResultOfferV3 = SearchResultOfferV3(generation, phase, 1, 2, digest("result".byteInputStream()), input)
        fun complete(): Unit = observer.onTerminal(SearchTerminalV3.Complete(generation, phase, SearchResourceStateV2.RELEASED, fence.resultFence()!!))
        fun cancelled(): Unit = observer.onTerminal(SearchTerminalV3.Cancelled(generation, phase, SearchResourceStateV2.RELEASED, fence.resultFence()!!))
    }

    private class Marker : SessionMarker {
        var value = MarkerState.CLEAR
        override fun read(): MarkerState = value
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { value = MarkerState.CLEAR; return true }
    }

    private class Input(val failClose: Boolean = false, val closeAction: () -> Unit = {}) : PrivateInputV1 {
        var closed = false
        var streamClosed = false
        override fun open(): InputStream = object : ByteArrayInputStream("result".toByteArray()) {
            override fun close(): Unit { streamClosed = true; super.close() }
        }
        override fun close(): Unit {
            closeAction()
            if (failClose) throw IOException("synthetic")
            closed = true
        }
    }

    private class Storage : ResultStorage {
        var stored: StoredResult? = null
        var manifest: ResultManifest? = null
        var prepareAction: () -> Unit = {}
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            prepareAction()
            stored = StoredResult(revision, sha256, input.readBytes().inputStream())
            return "result"
        }
        override fun readGeneration(generation: String): StoredResult? = stored
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { stored = null }
    }
}
