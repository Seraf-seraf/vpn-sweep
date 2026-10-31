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
        val prepareEntered = CountDownLatch(1)
        val runEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val runThread = AtomicReference<Thread>()
        var holdPrepare = false
        var holdRun = false
        var cancelResult = true
        var status = "success"
        val cancelled = AtomicBoolean()
        val cancelCalls = Collections.synchronizedList(mutableListOf<Long>())
        val preparations = Collections.synchronizedList(mutableListOf<Long>())
        val closed = AtomicInteger()
        val released = AtomicInteger()
        val quarantined = AtomicInteger()
        val resultInput = Input()
        override fun preparePhase(epoch: Long): Unit {
            preparations.add(epoch)
            cancelled.set(false)
            prepareEntered.countDown()
            if (holdPrepare) check(releasePrepare.await(3, TimeUnit.SECONDS))
        }
        override fun runPhase(epoch: Long): String {
            runThread.set(Thread.currentThread())
            runEntered.countDown()
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
        override fun closeSession(): Unit { closed.incrementAndGet() }
        override fun pauseResources(): SearchResourceStateV2 = SearchResourceStateV2.PAUSED_LEASE_HELD
        override fun releaseResources(): SearchResourceStateV2 { released.incrementAndGet(); return SearchResourceStateV2.RELEASED }
        override fun quarantine(): Unit { quarantined.incrementAndGet() }
    }

    private class Observer(private val failTerminal: Boolean = false) : SearchSessionObserverV3 {
        val terminals = Collections.synchronizedList(mutableListOf<SearchTerminalV3>())
        val terminal = CountDownLatch(1)
        val offers = AtomicInteger()
        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = Unit
        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
            offers.incrementAndGet()
            offer.result.open().use { it.readBytes() }
            offer.result.close()
            return SearchResultReceiptV3.COMMITTED_CLOSED
        }
        override fun onTerminal(terminal: SearchTerminalV3): Unit {
            terminals.add(terminal)
            this.terminal.countDown()
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var nextEpoch = 2L
        var resumed = SearchStartV3.REJECTED
        while (resumed == SearchStartV3.REJECTED && System.nanoTime() < deadline) {
            resumed = producer.resume(7, nextEpoch++)
            if (resumed == SearchStartV3.REJECTED) Thread.yield()
        }
        assertEquals(SearchStartV3.ACCEPTED, resumed)
        producer.cancel(7, 1)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (observer.terminals.size < 2 && System.nanoTime() < end) Thread.yield()
        assertEquals(2, observer.terminals.size)
        assertTrue(observer.terminals[1] is SearchTerminalV3.Complete)
        assertEquals(listOf(1L), owner.cancelCalls.toList())
        assertEquals(listOf(1L, nextEpoch - 1), owner.preparations.toList())
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
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (owner.quarantined.get() == 0 && System.nanoTime() < end) Thread.yield()
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
        assertEquals(SearchResourceStateV2.RELEASED, terminal.resources)
        assertEquals(SearchResultFenceV3.UNKNOWN, terminal.resultFence)
        assertEquals(1, calls.get())
        assertEquals(1, owner.quarantined.get())
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
}
