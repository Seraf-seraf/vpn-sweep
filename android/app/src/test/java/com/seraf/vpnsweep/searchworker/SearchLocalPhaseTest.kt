package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SearchLocalPhaseTest {
    private class Input(
        private val failStreamClose: Boolean = false,
        private val failInputClose: Boolean = false,
    ) : PrivateInputV1 {
        var opened = 0
        var streamsClosed = 0
        var inputsClosed = 0
        override fun open(): InputStream {
            opened++
            return object : ByteArrayInputStream("synthetic".toByteArray()) {
                override fun close(): Unit {
                    streamsClosed++
                    if (failStreamClose) throw IOException("synthetic")
                    super.close()
                }
            }
        }
        override fun close(): Unit {
            inputsClosed++
            if (failInputClose) throw IOException("synthetic")
        }
    }

    private class Observer(private val result: (SearchResultOfferV3) -> SearchResultReceiptV3) : SearchSessionObserverV3 {
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = result(offer)
        override fun onTerminal(terminal: SearchTerminalV3): Unit = error("no terminal in local phase")
    }

    @Test
    fun successRunsReleasesOpensAndTransfersOnce(): Unit {
        val input = Input()
        val trace = mutableListOf<String>()
        val phase = SearchLocalPhase(9, 3, 12, 14, Observer { offer ->
            trace.add("offer")
            assertEquals(9L, offer.sessionGeneration)
            assertEquals(3L, offer.phaseEpoch)
            assertEquals(12L, offer.sourceRevision)
            assertEquals(14L, offer.selectionRevision)
            assertEquals("synthetic", offer.result.open().use { it.readBytes().decodeToString() })
            offer.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }, { trace.add("run"); "success" },
            { trace.add("open"); LocalOpenedResult("a".repeat(64), input) },
            { trace.add("close") }, { trace.add("release"); SearchResourceStateV2.RELEASED })
        val result = phase.execute() as LocalPhaseOutcome.Handoff
        assertEquals(HandoffResult.Delivered(SearchResultReceiptV3.COMMITTED_CLOSED), result.result)
        assertEquals(SearchResultFenceV3.CONFIRMED, result.localFence)
        assertEquals(listOf("run", "release", "open", "offer", "close"), trace)
        assertEquals(1, input.opened)
        assertEquals(1, input.streamsClosed)
        assertEquals(1, input.inputsClosed)
        assertEquals(LocalPhaseOutcome.NotStarted, phase.execute())
    }

    @Test
    fun closedReceiptCannotClaimAnUnclosedInput(): Unit {
        val input = Input()
        val phase = phase(input, Observer { SearchResultReceiptV3.COMMITTED_CLOSED })
        val outcome = phase.execute() as LocalPhaseOutcome.Handoff
        assertEquals(HandoffResult.Unknown, outcome.result)
        assertEquals(SearchResultFenceV3.UNKNOWN, outcome.localFence)
        assertEquals(0, input.inputsClosed)
        assertEquals(LocalPhaseOutcome.NotStarted, phase.execute())
    }

    @Test
    fun streamCloseFailureStillAttemptsInputCloseAndRemainsUnknown(): Unit {
        val input = Input(failStreamClose = true)
        val phase = phase(input, Observer { offer ->
            try {
                offer.result.open().close()
            } catch (_: IOException) {
                // Consumer continues cleanup of its independent input handle.
            }
            offer.result.close()
            SearchResultReceiptV3.UNKNOWN
        })
        val outcome = phase.execute() as LocalPhaseOutcome.Handoff
        assertEquals(SearchResultFenceV3.UNKNOWN, outcome.localFence)
        assertEquals(1, input.streamsClosed)
        assertEquals(1, input.inputsClosed)
    }

    @Test
    fun revokeDuringRunDoesNotOpenResultAndDoesNotWaitForRun(): Unit {
        val entered = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val input = Input()
        var opens = 0
        val outcome = AtomicReference<LocalPhaseOutcome>()
        val phase = SearchLocalPhase(9, 3, 12, 14, Observer { error("late offer") }, {
            entered.countDown()
            check(releaseRun.await(2, TimeUnit.SECONDS))
            "success"
        }, { opens++; LocalOpenedResult("a".repeat(64), input) }, {}, { SearchResourceStateV2.RELEASED })
        val worker = Thread { outcome.set(phase.execute()) }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        phase.revoke()
        releaseRun.countDown()
        worker.join(2000)
        assertTrue(!worker.isAlive)
        val completed = outcome.get() as LocalPhaseOutcome.Handoff
        assertEquals(HandoffResult.Discarded, completed.result)
        assertEquals(SearchResultFenceV3.CONFIRMED, completed.localFence)
        assertEquals(0, opens)
    }

    @Test
    fun revokeDuringOpenClosesLocalInputAndCloseFailureQuarantines(): Unit {
        val entered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val input = Input(failInputClose = true)
        val outcome = AtomicReference<LocalPhaseOutcome>()
        val phase = SearchLocalPhase(9, 3, 12, 14, Observer { error("late offer") }, { "success" }, {
            entered.countDown()
            check(releaseOpen.await(2, TimeUnit.SECONDS))
            LocalOpenedResult("a".repeat(64), input)
        }, {}, { SearchResourceStateV2.RELEASED })
        val worker = Thread { outcome.set(phase.execute()) }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        phase.revoke()
        releaseOpen.countDown()
        worker.join(2000)
        assertTrue(!worker.isAlive)
        val completed = outcome.get() as LocalPhaseOutcome.Handoff
        assertEquals(HandoffResult.Unknown, completed.result)
        assertEquals(SearchResultFenceV3.UNKNOWN, completed.localFence)
        assertEquals(1, input.inputsClosed)
        assertEquals(0, input.opened)
    }

    @Test
    fun unknownLeaseNeverOpensResultAndPauseKeepsSession(): Unit {
        var opens = 0
        var closes = 0
        val observer = Observer { error("unexpected offer") }
        val failed = SearchLocalPhase(9, 3, 12, 14, observer, { "success" }, {
            opens++
            LocalOpenedResult("a".repeat(64), Input())
        }, { closes++ }, { SearchResourceStateV2.QUARANTINED })
        assertEquals(LocalPhaseOutcome.Unknown, failed.execute())
        assertEquals(0, opens)
        assertEquals(1, closes)
        val paused = SearchLocalPhase(9, 4, 12, 14, observer, { "paused_budget" }, {
            error("paused result")
        }, { error("paused session closed") }, { error("paused lease released") })
        assertEquals(LocalPhaseOutcome.Paused, paused.execute())
    }

    @Test
    fun sessionCloseFailureAfterCommitDoesNotProduceConfirmedFence(): Unit {
        val input = Input()
        val phase = SearchLocalPhase(9, 3, 12, 14, Observer { offer ->
            offer.result.open().close()
            offer.result.close()
            SearchResultReceiptV3.COMMITTED_CLOSED
        }, { "success" }, { LocalOpenedResult("a".repeat(64), input) },
            { throw IOException("synthetic") }, { SearchResourceStateV2.RELEASED })
        assertEquals(LocalPhaseOutcome.Unknown, phase.execute())
        assertEquals(1, input.inputsClosed)
    }

    private fun phase(input: Input, observer: SearchSessionObserverV3): SearchLocalPhase =
        SearchLocalPhase(9, 3, 12, 14, observer, { "success" },
            { LocalOpenedResult("a".repeat(64), input) }, {}, { SearchResourceStateV2.RELEASED })
}
