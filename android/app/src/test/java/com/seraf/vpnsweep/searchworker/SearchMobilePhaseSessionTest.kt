package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressKindV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressOutcomeV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SearchMobilePhaseSessionTest {
    private class Input(private val failClose: Boolean = false) : PrivateInputV1 {
        val closed = AtomicInteger()
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun close(): Unit { closed.incrementAndGet(); if (failClose) throw IOException("synthetic") }
    }

    private fun sample(): SearchProgressV1 = SearchProgressV1(
        7, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 1uL, 1, 1, 0, 0, 0,
    )

    @Test fun delegatesPhaseEpochAndKeepsCleanupResultsTyped(): Unit {
        val calls = mutableListOf<String>()
        val events = mutableListOf<Long>()
        val progress = SearchMobilePhaseProgress { epoch, _ -> events.add(epoch) }
        val input = Input()
        val session = SearchMobilePhaseSession(
            { epoch -> calls.add("prepare:$epoch") },
            { epoch -> calls.add("run:$epoch"); progress.report(sample()); "paused_budget" },
            { epoch -> calls.add("cancel:$epoch"); true },
            { LocalOpenedResult("a".repeat(64), input) },
            { calls.add("close") }, progress,
            { SearchResourceStateV2.PAUSED_LEASE_HELD }, { calls.add("quarantine") },
        )
        progress.report(sample())
        session.preparePhase(3)
        assertEquals("paused_budget", session.runPhase(3))
        assertTrue(session.cancelPhase(3))
        assertEquals(listOf(3L), events)
        assertEquals(SearchResourceStateV2.PAUSED_LEASE_HELD, session.pauseResources())
        assertSame(input, session.openVerifiedResult().input)
        session.closeSession()
        session.closeSession()
        assertEquals(listOf("prepare:3", "run:3", "cancel:3", "close"), calls)
    }

    @Test fun synchronousProgressCannotCrossIntoAnotherRun(): Unit {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val epochs = Collections.synchronizedList(mutableListOf<Long>())
        val progress = SearchMobilePhaseProgress { epoch, _ ->
            epochs.add(epoch)
            if (epoch == 1L) { callbackEntered.countDown(); check(releaseCallback.await(3, TimeUnit.SECONDS)) }
        }
        val session = SearchMobilePhaseSession({}, { progress.report(sample()); "interrupted" }, { true },
            { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { SearchResourceStateV2.PAUSED_LEASE_HELD }, {})
        val first = AtomicReference<String>()
        val runner = Thread { first.set(session.runPhase(1)) }
        runner.start()
        try {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            try { session.runPhase(2); fail("concurrent phase admitted") } catch (_: IOException) { }
            releaseCallback.countDown()
            runner.join(3000)
            assertFalse(runner.isAlive)
            assertEquals("interrupted", first.get())
            progress.report(sample())
            assertEquals("interrupted", session.runPhase(2))
            assertEquals(listOf(1L, 2L), epochs.toList())
        } finally { releaseCallback.countDown(); runner.join(3000) }
    }

    @Test fun actualWorkerProgressCallbackUsesEnclosingGoPhaseEpoch(): Unit {
        val events = mutableListOf<Pair<Long, SearchProgressV1>>()
        val progress = SearchMobilePhaseProgress { epoch, value -> events.add(epoch to value) }
        val worker = SearchEngineWorkerAdapter(7, SearchNetworkV1(11, 12), 5_000, 1_024, { null }, progress::report)
        val session = SearchMobilePhaseSession({}, {
            worker.progress(1, 1, 4, 1, 1, 0, 0, 0)
            "interrupted"
        }, { false }, { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { SearchResourceStateV2.PAUSED_LEASE_HELD }, {})
        assertEquals("interrupted", session.runPhase(8))
        assertEquals(1, events.size)
        assertEquals(8L, events.single().first)
        assertEquals(7L, events.single().second.sessionGeneration)
        assertEquals(SearchProgressKindV1.ATTEMPT_STARTED, events.single().second.kind)
    }

    @Test fun ownerLossStopsProgressAndCannotReturnLateSuccess(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Throwable>()
        val events = AtomicInteger()
        val quarantines = AtomicInteger()
        val progress = SearchMobilePhaseProgress { _, _ -> events.incrementAndGet() }
        val session = SearchMobilePhaseSession({}, {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); progress.report(sample()); "success"
        }, { true }, { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { SearchResourceStateV2.PAUSED_LEASE_HELD }, { quarantines.incrementAndGet() })
        val runner = Thread { try { session.runPhase(1) } catch (failure: Throwable) { result.set(failure) } }
        runner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.quarantine()
            session.quarantine()
            release.countDown()
            runner.join(3000)
            assertFalse(runner.isAlive)
            assertTrue(result.get() is IOException)
            assertEquals(0, events.get())
            assertEquals(1, quarantines.get())
            assertEquals(SearchResourceStateV2.QUARANTINED, session.pauseResources())
            assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
        } finally { release.countDown(); runner.join(3000) }
    }

    @Test fun openRacingQuarantineClosesUntransferredInputOnce(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = Input(failClose = true)
        val failure = AtomicReference<Throwable>()
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val session = SearchMobilePhaseSession({}, { "success" }, { true }, {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); LocalOpenedResult("a".repeat(64), input)
        }, {}, progress, { SearchResourceStateV2.PAUSED_LEASE_HELD }, {})
        val opener = Thread { try { session.openVerifiedResult() } catch (error: Throwable) { failure.set(error) } }
        opener.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.quarantine()
            release.countDown()
            opener.join(3000)
            assertFalse(opener.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(1, input.closed.get())
        } finally { release.countDown(); opener.join(3000) }
    }

    @Test fun unknownCloseIsStickyAndNeverRetriesOwner(): Unit {
        val closes = AtomicInteger()
        val quarantines = AtomicInteger()
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val session = SearchMobilePhaseSession({}, { "interrupted" }, { false },
            { LocalOpenedResult("a".repeat(64), Input()) }, { closes.incrementAndGet(); throw IOException("synthetic") },
            progress, { SearchResourceStateV2.PAUSED_LEASE_HELD }, { quarantines.incrementAndGet() })
        repeat(2) { try { session.closeSession(); fail("unknown close accepted") } catch (_: IOException) { } }
        assertEquals(1, closes.get())
        assertEquals(1, quarantines.get())
        assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
        try { session.preparePhase(1); fail("closed session admitted") } catch (_: IOException) { }
        assertFalse(session.cancelPhase(1))
    }

    @Test fun unconfirmedOrFailedResourceDispositionIsQuarantined(): Unit {
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val quarantines = AtomicInteger()
        val session = SearchMobilePhaseSession({}, { "interrupted" }, { false },
            { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { throw IOException("synthetic") }, { quarantines.incrementAndGet() })
        assertEquals(SearchResourceStateV2.QUARANTINED, session.pauseResources())
        assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
        assertEquals(1, quarantines.get())
    }

    @Test fun quarantineGateBlocksNewGoDispatchAndReleaseWhileOwnerCallbackWaits(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val ownerCalls = AtomicInteger()
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val session = SearchMobilePhaseSession({ calls.incrementAndGet() }, { calls.incrementAndGet(); "success" }, { false },
            { calls.incrementAndGet(); LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { calls.incrementAndGet(); SearchResourceStateV2.PAUSED_LEASE_HELD },
            { ownerCalls.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) })
        val revoke = Thread { session.quarantine() }
        revoke.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try { session.preparePhase(1); fail("prepare after revoke") } catch (_: IOException) { }
            try { session.runPhase(1); fail("run after revoke") } catch (_: IOException) { }
            try { session.openVerifiedResult(); fail("open after revoke") } catch (_: IOException) { }
            assertEquals(SearchResourceStateV2.QUARANTINED, session.pauseResources())
            assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
            assertEquals(0, calls.get())
            assertEquals(1, ownerCalls.get())
        } finally { release.countDown(); revoke.join(3000) }
    }

    @Test fun releaseIsFailClosedUntilRealOwnerGateExists(): Unit {
        val ownerCalls = AtomicInteger()
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val session = SearchMobilePhaseSession({}, { "interrupted" }, { false },
            { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { SearchResourceStateV2.PAUSED_LEASE_HELD }, { ownerCalls.incrementAndGet() })
        assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
        assertEquals(SearchResourceStateV2.QUARANTINED, session.releaseResources())
        assertEquals(1, ownerCalls.get())
    }

    @Test fun pauseAdmittedBeforeQuarantineCannotReturnConfirmedState(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<SearchResourceStateV2>()
        val progress = SearchMobilePhaseProgress { _, _ -> }
        val session = SearchMobilePhaseSession({}, { "interrupted" }, { false },
            { LocalOpenedResult("a".repeat(64), Input()) }, {}, progress,
            { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); SearchResourceStateV2.PAUSED_LEASE_HELD }, {})
        val waiter = Thread { result.set(session.pauseResources()) }
        waiter.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.quarantine()
            release.countDown()
            waiter.join(3_000)
            assertFalse(waiter.isAlive)
            assertEquals(SearchResourceStateV2.QUARANTINED, result.get())
        } finally { release.countDown(); waiter.join(3_000) }
    }
}
