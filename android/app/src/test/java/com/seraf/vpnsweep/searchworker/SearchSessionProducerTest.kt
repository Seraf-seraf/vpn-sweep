package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SearchSessionProducerTest {
    private class Input(private val failClose: Boolean = false) : PrivateInputV1 {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val streamClosed = AtomicInteger()
        override fun open(): InputStream {
            opened.incrementAndGet()
            return object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close(): Unit { streamClosed.incrementAndGet(); super.close() }
            }
        }
        override fun close(): Unit { closed.incrementAndGet(); if (failClose) throw IOException("synthetic") }
    }

    private class Owner : SearchPhaseSession {
        private val admission = SearchOwnerAdmission()
        private val token = checkNotNull(admission.reserve())
        override val releaseOwner = SearchOwnerLease(42, admission, token, { true }, { 0 })
        val prepareEntered = CountDownLatch(1)
        val runEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val runThread = AtomicReference<Thread>()
        var holdPrepare = false
        var holdRun = false
        var cancelResult = true
        @Volatile var status = "success"
        @Volatile var onRun: (Long) -> Unit = {}
        @Volatile var onPrepare: (Long) -> Unit = {}
        val cancelled = AtomicBoolean()
        val cancelCalls = Collections.synchronizedList(mutableListOf<Long>())
        val preparations = Collections.synchronizedList(mutableListOf<Long>())
        val closed = AtomicInteger()
        val released = AtomicInteger()
        val quarantined = AtomicInteger()
        val quarantineReached = CountDownLatch(1)
        val resultInput = Input()
        override fun preparePhase(epoch: Long): Unit {
            preparations.add(epoch)
            onPrepare(epoch)
            cancelled.set(false)
            prepareEntered.countDown()
            if (holdPrepare) check(releasePrepare.await(3, TimeUnit.SECONDS))
        }
        override fun runPhase(epoch: Long): String {
            runThread.set(Thread.currentThread())
            runEntered.countDown()
            onRun(epoch)
            if (holdRun) check(releaseRun.await(3, TimeUnit.SECONDS))
            return if (cancelled.get()) "interrupted" else status
        }
        override fun cancelPhase(epoch: Long): Boolean {
            cancelCalls.add(epoch)
            if (cancelResult) cancelled.set(true)
            cancelEntered.countDown()
            return cancelResult
        }
        override fun openVerifiedResult(): LocalOpenedResult = LocalOpenedResult("a".repeat(64), resultInput)
        override fun closeSession(deadlineElapsedMillis: Long): Unit { closed.incrementAndGet() }
        override fun pauseResources(deadlineElapsedMillis: Long): SearchResourceStateV2 = SearchResourceStateV2.PAUSED_LEASE_HELD
        override fun retireResources(deadlineElapsedMillis: Long): SearchResourceStateV2 = SearchResourceStateV2.PAUSED_LEASE_HELD
        override fun releaseResources(deadlineElapsedMillis: Long): SearchResourceStateV2 {
            released.incrementAndGet()
            return if (releaseOwner.releaseUntil(deadlineElapsedMillis)) SearchResourceStateV2.RELEASED else SearchResourceStateV2.QUARANTINED
        }
        override fun quarantine(): Unit { releaseOwner.quarantine(); quarantined.incrementAndGet(); quarantineReached.countDown() }
    }

    private class Observer(private val failTerminal: Boolean = false) : SearchSessionObserverV3 {
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val terminal = CountDownLatch(1)
        val finalTerminal = CountDownLatch(1)
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, SearchProgressV1>>())
        val offers = AtomicInteger()
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit { this.progress.add(phaseEpoch to progress) }
        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
            offers.incrementAndGet()
            offer.result.open().use { it.readBytes() }
            offer.result.close()
            return SearchResultReceiptV3.COMMITTED_CLOSED
        }
        override fun onTerminal(terminal: SearchTerminalV3): Unit {
            terminals.add(terminal)
            this.terminal.countDown()
            if (terminal !is SearchTerminalV3.Paused) finalTerminal.countDown()
            if (failTerminal) throw IOException("synthetic")
        }
    }

    private fun snapshot(input: Input): SearchSnapshotV1 = SearchSnapshotV1(7, 11, 13, input)
    private fun producer(owner: Owner): SearchSessionProducer = SearchSessionProducer(2_000, { snapshot, _ ->
        snapshot.profiles.open().use { it.readBytes() }
        owner
    }, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })

    @Test fun successUsesRealLocalFenceAndTransfersProfilesOnce(): Unit {
        val owner = Owner()
        val input = Input()
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(input), 1, observer))
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        val terminal = observer.terminals.single() as SearchTerminalV3.Complete
        assertEquals(SearchResourceStateV2.RELEASED, terminal.resources)
        assertEquals(SearchResultFenceV3.CONFIRMED, terminal.resultFence)
        assertEquals(1, observer.offers.get())
        assertEquals(1, input.opened.get())
        assertEquals(1, input.streamClosed.get())
        assertEquals(1, input.closed.get())
        assertEquals(1, owner.closed.get())
        assertEquals(1, owner.released.get())
        assertEquals(0, owner.quarantined.get())
    }

    @Test fun cancelDuringPrepareIsStickyBeforeRun(): Unit {
        val owner = Owner().apply { holdPrepare = true }
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(owner.prepareEntered.await(2, TimeUnit.SECONDS))
        producer.cancel(7, 1)
        owner.releasePrepare.countDown()
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        assertTrue(observer.terminals.single() is SearchTerminalV3.Cancelled)
        assertEquals(listOf(1L), owner.cancelCalls.toList())
        assertEquals(0, observer.offers.get())
        assertEquals(1, owner.closed.get())
        assertEquals(1, owner.released.get())
    }

    @Test fun pauseAndResumeIgnoreStalePhaseCancel(): Unit {
        val owner = Owner().apply { holdRun = true }
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(owner.runEntered.await(2, TimeUnit.SECONDS))
        producer.pause(7, 1)
        assertTrue(owner.cancelEntered.await(2, TimeUnit.SECONDS))
        owner.releaseRun.countDown()
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        assertTrue(observer.terminals[0] is SearchTerminalV3.Paused)
        owner.runThread.get().join(3_000)
        assertFalse(owner.runThread.get().isAlive)
        assertEquals(SearchStartV3.ACCEPTED, producer.resume(7, 2))
        producer.cancel(7, 1)
        assertTrue(observer.finalTerminal.await(3, TimeUnit.SECONDS))
        assertEquals(2, observer.terminals.size)
        assertTrue(observer.terminals[1] is SearchTerminalV3.Complete)
        assertEquals(listOf(1L), owner.cancelCalls.toList())
        assertEquals(listOf(1L, 2L), owner.preparations.toList())
    }

    @Test fun unknownProfileCloseBlocksFurtherAdmission(): Unit {
        val owner = Owner()
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input(failClose = true)), 1, observer))
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        val terminal = observer.terminals.single() as SearchTerminalV3.Stopped
        assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
        assertEquals(1, owner.quarantined.get())
        val other = Input()
        assertEquals(SearchStartV3.BUSY, producer.start(snapshot(other), 1, Observer()))
        assertEquals(0, other.opened.get())
        assertEquals(0, other.closed.get())
    }

    @Test fun lostTerminalCallbackIsNeverReplayed(): Unit {
        val owner = Owner()
        val observer = Observer(failTerminal = true)
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        assertTrue(owner.quarantineReached.await(2, TimeUnit.SECONDS))
        assertEquals(1, observer.terminals.size)
        assertEquals(1, owner.quarantined.get())
        assertEquals(SearchStartV3.BUSY, producer.start(snapshot(Input()), 1, Observer()))
    }

    @Test fun ownerLossStopsResourceDeliveryAndKeepsAdmissionBlocked(): Unit {
        val owner = Owner().apply { holdRun = true }
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(owner.runEntered.await(2, TimeUnit.SECONDS))
        producer.ownerLost(7)
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        val terminal = observer.terminals.single() as SearchTerminalV3.Stopped
        assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
        assertEquals(1, owner.quarantined.get())
        owner.releaseRun.countDown()
        owner.runThread.get().join(3_000)
        assertFalse(owner.runThread.get().isAlive)
        assertEquals(SearchStartV3.BUSY, producer.start(snapshot(Input()), 1, Observer()))
        assertEquals(0, observer.offers.get())
        assertEquals(0, owner.released.get())
    }

    @Test fun busyStartDoesNotTouchProfiles(): Unit {
        val owner = Owner().apply { holdPrepare = true }
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(owner.prepareEntered.await(2, TimeUnit.SECONDS))
        val untouched = Input()
        assertEquals(SearchStartV3.BUSY, producer.start(snapshot(untouched), 1, Observer()))
        assertEquals(0, untouched.opened.get())
        assertEquals(0, untouched.closed.get())
        producer.cancel(7, 1)
        owner.releasePrepare.countDown()
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
    }

    @Test fun lostResultReceiptKeepsFenceUnknownWithoutReplay(): Unit {
        val owner = Owner()
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val done = CountDownLatch(1)
        val calls = AtomicInteger()
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                calls.incrementAndGet()
                throw IOException("synthetic delivery unknown")
            }
            override fun onTerminal(terminal: SearchTerminalV3): Unit { terminals.add(terminal); done.countDown() }
        }
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        val terminal = terminals.single() as SearchTerminalV3.Stopped
        assertEquals(SearchResourceStateV2.QUARANTINED, terminal.resources)
        assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
        assertEquals(1, calls.get())
        assertEquals(1, owner.quarantined.get())
        assertEquals(0, owner.released.get())
    }

    @Test fun falseCancelAfterPhaseCannotBeReportedAsCancelled(): Unit {
        val owner = Owner().apply { holdRun = true; cancelResult = false; status = "interrupted" }
        val observer = Observer()
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(owner.runEntered.await(2, TimeUnit.SECONDS))
        producer.cancel(7, 1)
        assertTrue(owner.cancelEntered.await(2, TimeUnit.SECONDS))
        owner.releaseRun.countDown()
        assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
        assertTrue(observer.terminals.single() is SearchTerminalV3.Stopped)
        assertEquals(1, owner.released.get())
    }

    @Test fun ownerLossAfterFinalTerminalIsChosenDoesNotReplayIt(): Unit {
        val owner = Owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                offer.result.open().use { it.readBytes() }
                offer.result.close()
                return SearchResultReceiptV3.COMMITTED_CLOSED
            }
            override fun onTerminal(terminal: SearchTerminalV3): Unit {
                count.incrementAndGet()
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }
        val producer = producer(owner)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        producer.ownerLost(7)
        release.countDown()
        owner.runThread.get().join(3_000)
        assertFalse(owner.runThread.get().isAlive)
        assertEquals(1, count.get())
        assertEquals(0, owner.quarantined.get())
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendProgress(sink: Any, phase: Long, progress: SearchProgressV1): Unit {
        when (sink) {
            is Function2<*, *, *> -> (sink as (Long, SearchProgressV1) -> Unit)(phase, progress)
            is Function1<*, *> -> (sink as (SearchProgressV1) -> Unit)(progress)
            else -> error("unexpected progress port")
        }
    }

    @Test fun firstResumeDuringPausedCallbackIsAcceptedAndOldCallbackCannotOverwriteIt(): Unit {
        val owner = Owner().apply { status = "paused_budget" }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                offer.result.open().use { it.readBytes() }; offer.result.close(); return SearchResultReceiptV3.COMMITTED_CLOSED
            }
            override fun onTerminal(terminal: SearchTerminalV3): Unit {
                terminals.add(terminal)
                if (terminal is SearchTerminalV3.Paused) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                else complete.countDown()
            }
        }
        val producer = producer(owner)
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.status = "success"
            assertEquals(SearchStartV3.ACCEPTED, producer.resume(7, 2))
            release.countDown()
            assertTrue(complete.await(3, TimeUnit.SECONDS))
            assertEquals(2, terminals.size)
            assertEquals(1L, terminals[0].phaseEpoch)
            assertEquals(2L, terminals[1].phaseEpoch)
            assertTrue(terminals[1] is SearchTerminalV3.Complete)
        } finally { release.countDown() }
    }

    @Test fun delayedOldProgressKeepsItsOriginatingPhase(): Unit {
        val owner = Owner().apply { status = "paused_budget" }
        val sink = AtomicReference<Any>()
        val observer = Observer()
        val enteredSecond = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val producer = SearchSessionProducer(2_000, { snapshot, progress ->
            snapshot.profiles.open().use { it.readBytes() }; sink.set(progress); owner
        }, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(observer.terminal.await(3, TimeUnit.SECONDS))
            owner.runThread.get().join(3_000)
            assertFalse(owner.runThread.get().isAlive)
            owner.status = "success"
            owner.onRun = { epoch -> if (epoch == 2L) { enteredSecond.countDown(); check(releaseSecond.await(3, TimeUnit.SECONDS)) } }
            assertEquals(SearchStartV3.ACCEPTED, producer.resume(7, 2))
            assertTrue(enteredSecond.await(2, TimeUnit.SECONDS))
            val value = SearchProgressV1(7, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 1uL, 1, 1, 0, 0, 0)
            sendProgress(sink.get(), 1, value)
            assertTrue(observer.progress.isEmpty())
            sendProgress(sink.get(), 2, value)
            assertEquals(listOf(2L), observer.progress.map { it.first })
            releaseSecond.countDown()
            assertTrue(observer.finalTerminal.await(3, TimeUnit.SECONDS))
        } finally { releaseSecond.countDown() }
    }

    @Test fun cancelWhilePrepareBlockedExpiresToUnknownWithoutLeaseRelease(): Unit {
        val owner = Owner().apply { holdPrepare = true }
        val observer = Observer()
        val producer = SearchSessionProducer(100, { snapshot, _ -> snapshot.profiles.open().use { it.readBytes() }; owner },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(owner.prepareEntered.await(2, TimeUnit.SECONDS))
            producer.cancel(7, 1)
            assertTrue("cleanup deadline must be observed independently of Prepare", observer.terminal.await(2, TimeUnit.SECONDS))
            val terminal = observer.terminals.single() as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
            assertEquals(SearchResourceStateV2.QUARANTINED, terminal.resources)
            assertEquals(1, owner.quarantined.get())
            assertEquals(0, owner.released.get())
        } finally { owner.releasePrepare.countDown() }
    }

    @Test fun cancelWhileRunBlockedExpiresToUnknownWithoutLeaseRelease(): Unit {
        val owner = Owner().apply { holdRun = true }
        val observer = Observer()
        val producer = SearchSessionProducer(100, { snapshot, _ -> snapshot.profiles.open().use { it.readBytes() }; owner },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(owner.runEntered.await(2, TimeUnit.SECONDS))
            producer.cancel(7, 1)
            assertTrue("cleanup deadline must be observed independently of Run", observer.terminal.await(2, TimeUnit.SECONDS))
            val terminal = observer.terminals.single() as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
            assertEquals(1, owner.quarantined.get())
            assertEquals(0, owner.released.get())
        } finally { owner.releaseRun.countDown() }
    }

    @Test fun cancelAfterCompletedPauseStartsFreshCleanupBudget(): Unit {
        val owner = Owner().apply { status = "paused_budget" }
        val observer = Observer()
        val clock = java.util.concurrent.atomic.AtomicLong(1_000)
        val producer = SearchSessionProducer(1_000, { snapshot, _ -> snapshot.profiles.open().use { it.readBytes() }; owner }, clock::get)
        assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
        assertTrue(observer.terminal.await(2, TimeUnit.SECONDS))
        owner.runThread.get().join(3_000)
        assertFalse(owner.runThread.get().isAlive)
        clock.set(3_000)
        producer.cancel(7, 1)
        assertTrue(observer.finalTerminal.await(3, TimeUnit.SECONDS))
        assertTrue(observer.terminals[1] is SearchTerminalV3.Cancelled)
        assertEquals(1, owner.released.get())
        assertEquals(0, owner.quarantined.get())
    }

    @Test fun cancelWhileFactoryBlockedExpiresToUnknown(): Unit {
        val owner = Owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observer = Observer()
        val producer = SearchSessionProducer(100, { snapshot, _ ->
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); snapshot.profiles.open().use { it.readBytes() }; owner
        }, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            producer.cancel(7, 1)
            assertTrue("cleanup deadline must be observed independently of factory", observer.terminal.await(2, TimeUnit.SECONDS))
            val terminal = observer.terminals.single() as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
            assertEquals(0, owner.released.get())
        } finally { release.countDown() }
    }

    @Test fun cancelDuringUndeliveredPauseRetainsOriginalDeadline(): Unit {
        val owner = Owner().apply { status = "paused_budget" }
        val clock = java.util.concurrent.atomic.AtomicLong(1_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finalTerminal = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = error("unexpected result")
            override fun onTerminal(terminal: SearchTerminalV3): Unit {
                terminals.add(terminal)
                if (terminal is SearchTerminalV3.Paused) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                else finalTerminal.countDown()
            }
        }
        val producer = SearchSessionProducer(1_000, { snap, _ -> snap.profiles.open().use { it.readBytes() }; owner }, clock::get)
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            clock.set(1_500)
            producer.cancel(7, 1)
            clock.set(2_000)
            release.countDown()
            assertTrue(finalTerminal.await(3, TimeUnit.SECONDS))
            val terminal = terminals[1] as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
            assertEquals(0, owner.released.get())
        } finally { release.countDown() }
    }

    @Test fun cancelWhileResultCallbackBlockedExpiresWithoutConfirmedFence(): Unit {
        val owner = Owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val offers = AtomicInteger()
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                offers.incrementAndGet()
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                offer.result.open().use { it.readBytes() }
                offer.result.close()
                return SearchResultReceiptV3.COMMITTED_CLOSED
            }
            override fun onTerminal(value: SearchTerminalV3): Unit { terminals.add(value); terminal.countDown() }
        }
        val producer = SearchSessionProducer(100, { snap, _ -> snap.profiles.open().use { it.readBytes() }; owner },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            producer.cancel(7, 1)
            assertTrue("control deadline must be observed independently of result callback", terminal.await(2, TimeUnit.SECONDS))
            val unknown = terminals.single() as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, unknown.resultFence)
            assertEquals(SearchResourceStateV2.QUARANTINED, unknown.resources)
            assertEquals(0, owner.released.get())
            release.countDown()
            owner.runThread.get().join(3_000)
            assertFalse(owner.runThread.get().isAlive)
            assertEquals(1, offers.get())
            assertEquals(1, terminals.size)
            assertEquals(1, owner.resultInput.closed.get())
        } finally { release.countDown() }
    }

    @Test fun resultCallbackDeadlineExpiresWithoutExplicitCancel(): Unit {
        val owner = Owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                offer.result.close()
                return SearchResultReceiptV3.IGNORED_CLOSED
            }
            override fun onTerminal(value: SearchTerminalV3): Unit { terminals.add(value); terminal.countDown() }
        }
        val producer = SearchSessionProducer(100, { snap, _ -> snap.profiles.open().use { it.readBytes() }; owner },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            val stopped = terminals.single() as SearchTerminalV3.Stopped
            assertEquals(SearchResultFenceV3.UNKNOWN, stopped.resultFence)
            assertEquals(SearchResourceStateV2.QUARANTINED, stopped.resources)
            assertEquals(0, owner.released.get())
            release.countDown()
            owner.runThread.get().join(3_000)
            assertEquals(1, terminals.size)
            assertEquals(0, owner.released.get())
        } finally { release.countDown() }
    }

    @Test fun resumeInsideBlockedPauseKeepsEarlierWatchUntilCallbackReturns(): Unit {
        val owner = Owner().apply { holdRun = true }
        val pausedEntered = CountDownLatch(1)
        val releasePaused = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val preparedNew = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        owner.onPrepare = { epoch -> if (epoch == 2L) preparedNew.countDown() }
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = error("unexpected result")
            override fun onTerminal(terminal: SearchTerminalV3): Unit {
                terminals.add(terminal)
                if (terminal is SearchTerminalV3.Paused) {
                    pausedEntered.countDown()
                    check(releasePaused.await(3, TimeUnit.SECONDS))
                } else interrupted.countDown()
            }
        }
        val producer = SearchSessionProducer(300, { snap, _ -> snap.profiles.open().use { it.readBytes() }; owner },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(owner.runEntered.await(2, TimeUnit.SECONDS))
            producer.pause(7, 1)
            assertTrue(owner.cancelEntered.await(2, TimeUnit.SECONDS))
            owner.releaseRun.countDown()
            assertTrue(pausedEntered.await(2, TimeUnit.SECONDS))
            assertEquals(SearchStartV3.ACCEPTED, producer.resume(7, 2))
            assertTrue("old cleanup deadline must remain active during callback", interrupted.await(2, TimeUnit.SECONDS))
            val stopped = terminals[1] as SearchTerminalV3.Stopped
            assertEquals(2L, stopped.phaseEpoch)
            assertEquals(SearchResultFenceV3.UNKNOWN, stopped.resultFence)
            assertEquals(1, owner.quarantined.get())
            assertEquals(0, owner.released.get())
            releasePaused.countDown()
            assertFalse(preparedNew.await(300, TimeUnit.MILLISECONDS))
        } finally {
            releasePaused.countDown()
            owner.releaseRun.countDown()
        }
    }

    @Test fun resumeCannotStartNextPhaseWhenPauseReturnsAtDeadline(): Unit {
        pauseResumeAtDeadline(cancelNewPhase = false)
    }

    @Test fun cancelWhileResumedPhaseWaitsKeepsPriorDeadline(): Unit {
        pauseResumeAtDeadline(cancelNewPhase = true)
    }

    private fun pauseResumeAtDeadline(cancelNewPhase: Boolean): Unit {
        val owner = Owner().apply { status = "paused_budget" }
        val clock = java.util.concurrent.atomic.AtomicLong(1_000)
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val preparedNew = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        owner.onPrepare = { epoch -> if (epoch == 2L) preparedNew.countDown() }
        val observer = object : SearchSessionObserverV3 {
            override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
            override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = error("unexpected result")
            override fun onTerminal(value: SearchTerminalV3): Unit {
                terminals.add(value)
                if (value is SearchTerminalV3.Paused) { paused.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                else terminal.countDown()
            }
        }
        val producer = SearchSessionProducer(1_000, { snap, _ -> snap.profiles.open().use { it.readBytes() }; owner }, clock::get)
        try {
            assertEquals(SearchStartV3.ACCEPTED, producer.start(snapshot(Input()), 1, observer))
            assertTrue(paused.await(2, TimeUnit.SECONDS))
            assertEquals(SearchStartV3.ACCEPTED, producer.resume(7, 2))
            if (cancelNewPhase) { clock.set(1_500); producer.cancel(7, 2) }
            clock.set(2_000)
            release.countDown()
            assertTrue(terminal.await(3, TimeUnit.SECONDS))
            val stopped = terminals[1] as SearchTerminalV3.Stopped
            assertEquals(2L, stopped.phaseEpoch)
            assertEquals(SearchResultFenceV3.UNKNOWN, stopped.resultFence)
            assertEquals(0, owner.released.get())
            assertFalse(preparedNew.await(150, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
    }
}
