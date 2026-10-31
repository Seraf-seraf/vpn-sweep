package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultHandoffFenceTest {
    @Test
    fun localOpenAndReceiptRequireLeaseReleaseAndSealedFence(): Unit {
        val input = FakeInput()
        val calls = AtomicInteger()
        val observer = FakeObserver { value ->
            value.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }
        val fence = ResultHandoffFence(4, 2, observer)
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.seal()
        assertNull(fence.reserveLocalOpen())
        assertEquals(HandoffResult.Rejected, open.run { calls.incrementAndGet(); offer(input) })
        assertEquals(0, calls.get())
        assertNull(fence.resultFence())
        fence.workerState(SearchResourceStateV2.RELEASED)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.COMMITTED_CLOSED), open.run {
            calls.incrementAndGet()
            offer(input)
        })
        assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
        assertEquals(1, calls.get())
        assertEquals(1, input.closes.get())
        assertEquals(1, observer.calls.get())
    }

    @Test
    fun admittedSynchronousOpenAfterSealBlocksUntilReturnAndReceipt(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<HandoffResult>()
        val input = FakeInput()
        val fence = ResultHandoffFence(4, 2, FakeObserver { value ->
            value.result.close()
            SearchResultReceiptV3.FAILED_CLOSED
        })
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.workerState(SearchResourceStateV2.RELEASED)
        val caller = Thread {
            outcome.set(open.run {
                entered.countDown()
                release.await()
                offer(input)
            })
        }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fence.seal()
            assertNull(fence.reserveLocalOpen())
            assertNull(fence.resultFence())
        } finally {
            release.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.FAILED_CLOSED), outcome.get())
        assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
    }

    @Test
    fun duplicateOpenNeverCallsSourceOrConsumerAgain(): Unit {
        val input = FakeInput()
        val sourceCalls = AtomicInteger()
        val observer = FakeObserver { value ->
            value.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }
        val fence = ResultHandoffFence(4, 2, observer)
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.workerState(SearchResourceStateV2.RELEASED)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.COMMITTED_CLOSED), open.run {
            sourceCalls.incrementAndGet()
            offer(input)
        })
        fence.seal()
        assertEquals(HandoffResult.Rejected, open.run {
            sourceCalls.incrementAndGet()
            offer(FakeInput())
        })
        assertNull(fence.reserveLocalOpen())
        assertEquals(1, sourceCalls.get())
        assertEquals(1, observer.calls.get())
        assertEquals(1, input.closes.get())
        assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
    }

    @Test
    fun cancelBeforeOpenDoesNotCreateAResult(): Unit {
        val calls = AtomicInteger()
        val fence = ResultHandoffFence(4, 2, FakeObserver { error("cancelled result delivered") })
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.cancel()
        assertEquals(HandoffResult.Discarded, open.run { calls.incrementAndGet(); offer(FakeInput()) })
        fence.workerState(SearchResourceStateV2.RELEASED)
        assertEquals(0, calls.get())
        assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
    }

    @Test
    fun cancelDuringOpenClosesProducerInputOrQuarantinesOnFailure(): Unit {
        for (closeFails in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val input = FakeInput(closeFails)
            val observer = FakeObserver { error("cancelled result delivered") }
            val fence = ResultHandoffFence(4, 2, observer)
            val open = checkNotNull(fence.reserveLocalOpen())
            fence.workerState(SearchResourceStateV2.RELEASED)
            val outcome = AtomicReference<HandoffResult>()
            val caller = Thread {
                outcome.set(open.run {
                    entered.countDown()
                    release.await()
                    offer(input)
                })
            }
            caller.start()
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                fence.cancel()
                assertNull(fence.resultFence())
            } finally {
                release.countDown()
                caller.join(1000)
            }
            assertFalse(caller.isAlive)
            assertEquals(if (closeFails) HandoffResult.Unknown else HandoffResult.Discarded, outcome.get())
            assertEquals(if (closeFails) SearchResultFenceV3.UNKNOWN else SearchResultFenceV3.CONFIRMED, fence.resultFence())
            assertEquals(1, input.closes.get())
            assertEquals(0, observer.calls.get())
        }
    }

    @Test
    fun staleGenerationOrPhaseIsClosedByProducerBeforeConsumerCall(): Unit {
        for ((generation, phase) in listOf(3L to 2L, 4L to 1L)) {
            val input = FakeInput()
            val observer = FakeObserver { error("stale result delivered") }
            val fence = ResultHandoffFence(4, 2, observer)
            val open = checkNotNull(fence.reserveLocalOpen())
            fence.workerState(SearchResourceStateV2.RELEASED)
            assertEquals(HandoffResult.Discarded, open.run { offer(input, generation, phase) })
            fence.seal()
            assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
            assertEquals(1, input.closes.get())
            assertEquals(0, observer.calls.get())
        }
    }

    @Test
    fun unknownReceiptOrExceptionDoesNotCloseConsumerOwnedInputAgain(): Unit {
        for (failure in listOf("receipt", "exception")) {
            val input = FakeInput()
            val observer = FakeObserver {
                if (failure == "exception") throw IllegalStateException("synthetic")
                SearchResultReceiptV3.UNKNOWN
            }
            val fence = ResultHandoffFence(4, 2, observer)
            val open = checkNotNull(fence.reserveLocalOpen())
            fence.workerState(SearchResourceStateV2.RELEASED)
            assertEquals(HandoffResult.Unknown, open.run { offer(input) })
            assertEquals(SearchResultFenceV3.UNKNOWN, fence.resultFence())
            assertEquals(0, input.closes.get())
            assertEquals(1, observer.calls.get())
            assertEquals(HandoffResult.Rejected, open.run { error("blind retry") })
        }
    }

    @Test
    fun blockedConsumerDoesNotBlockQuarantineAndCannotConfirm(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observer = FakeObserver { value ->
            entered.countDown()
            release.await()
            value.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }
        val fence = ResultHandoffFence(4, 2, observer)
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.workerState(SearchResourceStateV2.RELEASED)
        val caller = Thread { open.run { offer(FakeInput()) } }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val returned = CountDownLatch(1)
            val revoker = Thread { fence.quarantine(); returned.countDown() }
            revoker.start()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertNull(fence.resultFence())
        } finally {
            release.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(SearchResultFenceV3.UNKNOWN, fence.resultFence())
    }

    @Test
    fun cancelDuringHandoffPreservesCommittedReceipt(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = FakeInput()
        val outcome = AtomicReference<HandoffResult>()
        val observer = FakeObserver { value ->
            entered.countDown()
            release.await()
            value.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }
        val fence = ResultHandoffFence(4, 2, observer)
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.workerState(SearchResourceStateV2.RELEASED)
        val caller = Thread { outcome.set(open.run { offer(input) }) }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fence.cancel()
            assertNull(fence.resultFence())
        } finally {
            release.countDown()
            caller.join(1000)
        }
        assertFalse(caller.isAlive)
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.COMMITTED_CLOSED), outcome.get())
        assertEquals(1, input.closes.get())
        assertEquals(SearchResultFenceV3.CONFIRMED, fence.resultFence())
    }

    @Test
    fun openFailureOrUnknownWorkerCleanupCannotConfirm(): Unit {
        val fence = ResultHandoffFence(4, 2, FakeObserver { error("unknown result delivered") })
        val open = checkNotNull(fence.reserveLocalOpen())
        fence.workerState(SearchResourceStateV2.RELEASED)
        assertEquals(HandoffResult.Unknown, open.run { error("synthetic open failure") })
        assertEquals(SearchResultFenceV3.UNKNOWN, fence.resultFence())

        val lostWorker = ResultHandoffFence(4, 2, FakeObserver { error("lost worker delivered") })
        lostWorker.workerState(SearchResourceStateV2.QUARANTINED)
        lostWorker.workerState(SearchResourceStateV2.RELEASED)
        assertNull(lostWorker.reserveLocalOpen())
        assertEquals(SearchResultFenceV3.UNKNOWN, lostWorker.resultFence())
    }

    private fun offer(input: PrivateInputV1, generation: Long = 4, phase: Long = 2): SearchResultOfferV3 =
        SearchResultOfferV3(generation, phase, 10, 11, "synthetic", input)

    private class FakeInput(private val failClose: Boolean = false) : PrivateInputV1 {
        val closes = AtomicInteger()
        override fun open(): InputStream = error("not needed")
        override fun close(): Unit {
            closes.incrementAndGet()
            check(!failClose)
        }
    }

    private class FakeObserver(
        private val result: (SearchResultOfferV3) -> SearchResultReceiptV3,
    ) : SearchSessionObserverV3 {
        val calls = AtomicInteger()
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
            calls.incrementAndGet()
            return result(offer)
        }
        override fun onTerminal(terminal: SearchTerminalV3): Unit = Unit
    }
}
