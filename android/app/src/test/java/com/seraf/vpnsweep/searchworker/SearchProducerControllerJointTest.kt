package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SearchProducerControllerJointTest {
    @Test fun realProducerAndControllerCloseResultBeforeExactAcceptanceAndClear(): Unit {
        val test = Harness(injectStale = true)
        assertEquals(SearchStartV3.ACCEPTED, test.controller.start(1, 2, Input(byteArrayOf(7)), true))
        assertTrue(test.port.terminalForwarded.await(3, TimeUnit.SECONDS))
        assertEquals(SearchPhase.COMPLETE, test.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertArrayEquals(test.bytes, test.storage.bytes)
        assertSame(test.port.terminal.get(), test.port.acceptedTerminal.get())
        assertEquals(1, test.port.acceptCalls.get())
        assertEquals(1, test.marker.clears.get())
        assertEquals(1, test.workerStarts.get())
        assertEquals(1, test.leaseCloses.get())
        assertEquals(0, test.duplicate.opens.get())
        assertEquals(1, test.duplicate.closes.get())
        assertEquals(0, test.stale.opens.get())
        assertEquals(1, test.stale.closes.get())
        test.assertOrder("worker-retired", "retire-confirmed", "offer", "stream-close", "input-close",
            "receipt-COMMITTED_CLOSED", "session-close", "anchor-close", "accept-true", "marker-clear")

        val terminal = checkNotNull(test.port.terminal.get())
        checkNotNull(test.port.downstream.get()).onTerminal(terminal)
        assertFalse(test.producer.acceptTerminal(terminal))
        assertEquals(1, test.port.acceptCalls.get())
        assertEquals(1, test.marker.clears.get())
        assertEquals(1, test.workerStarts.get())
        assertEquals(1, test.leaseCloses.get())
    }

    @Test fun ownerLossBeforeAcceptanceKeepsMarkerActiveAndCommittedResult(): Unit {
        val test = Harness(blockAccept = true)
        try {
            assertEquals(SearchStartV3.ACCEPTED, test.controller.start(1, 2, Input(byteArrayOf(7)), true))
            assertTrue(test.port.acceptEntered.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.producer.ownerLost(1)
            assertTrue(test.quarantined.await(3, TimeUnit.SECONDS))
            test.port.continueAccept.countDown()
            assertTrue(test.port.terminalForwarded.await(3, TimeUnit.SECONDS))
            assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertTrue(test.controller.state.value.resultCommitted)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.marker.clears.get())
            assertEquals(1, test.port.acceptCalls.get())
            assertTrue(test.events.contains("accept-false"))
            assertFalse(test.admissionAvailable())
            assertArrayEquals(test.bytes, test.storage.bytes)
        } finally { test.port.continueAccept.countDown() }
    }

    @Test fun sameThreadTerminalBeforeStartReturnIsAcceptedAfterDispatch(): Unit {
        val test = Harness(synchronousTerminal = true)
        val result = AtomicReference<SearchStartV3>()
        val caller = Thread { result.set(test.controller.start(1, 2, Input(byteArrayOf(7)), true)) }
        caller.start()
        try {
            assertTrue(test.port.terminalForwarded.await(3, TimeUnit.SECONDS))
            assertSame(caller, test.port.forwardingThread.get())
            assertTrue(caller.isAlive)
            assertEquals(0, test.port.acceptCalls.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.port.continueStart.countDown()
            caller.join(3_000)
            assertFalse(caller.isAlive)
            assertEquals(SearchStartV3.ACCEPTED, result.get())
            assertEquals(1, test.port.acceptCalls.get())
            assertEquals(SearchPhase.COMPLETE, test.controller.state.value.phase)
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.assertOrder("terminal-forwarded", "start-returning", "accept-true", "marker-clear")
        } finally { test.port.continueStart.countDown(); caller.join(3_000) }
    }

    private class Harness(
        injectStale: Boolean = false,
        blockAccept: Boolean = false,
        synchronousTerminal: Boolean = false,
    ) {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val bytes = ByteArray(128) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val resultInput = Input(bytes, events)
        val duplicate = Input(byteArrayOf(8))
        val stale = Input(byteArrayOf(9))
        val admission = SearchOwnerAdmission()
        private val token = checkNotNull(admission.reserve())
        val leaseCloses = AtomicInteger()
        val workerStarts = AtomicInteger()
        val quarantined = CountDownLatch(1)
        val clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
        val lease = SearchOwnerLease(42, admission, token, { leaseCloses.incrementAndGet(); events.add("anchor-close"); true }, clock)
        val marker = Marker(events)
        val storage = Storage()
        val producer = SearchSessionProducer(10_000, { snapshot, deliver ->
            snapshot.profiles.open().use { it.readBytes() }
            val progress = SearchMobilePhaseProgress(clock, deliver)
            SearchMobilePhaseSession({}, {
                check(lease.reserveWorker())
                workerStarts.incrementAndGet()
                lease.workerDeathConfirmed()
                events.add("worker-retired")
                "success"
            }, { true }, { LocalOpenedResult(digest, resultInput) }, { events.add("session-close") }, progress,
                { events.add("retire-confirmed"); SearchResourceStateV2.PAUSED_LEASE_HELD }, { quarantined.countDown() }, lease)
        }, clock)
        val port = Relay(producer, marker, events, resultInput, duplicate, stale, injectStale, blockAccept, synchronousTerminal)
        val controller = SearchController(port, ResultStore(storage), marker)

        fun admissionAvailable(): Boolean {
            val next = admission.reserve() ?: return false
            admission.release(next)
            return true
        }

        fun assertOrder(vararg wanted: String): Unit {
            val snapshot = events.toList()
            var previous = -1
            for (event in wanted) {
                val next = snapshot.indexOf(event)
                assertTrue("$event out of order: $snapshot", next > previous)
                previous = next
            }
        }
    }

    private class Relay(
        private val producer: SearchSessionProducer,
        private val marker: Marker,
        private val events: MutableList<String>,
        private val input: Input,
        private val duplicate: Input,
        private val stale: Input,
        private val injectStale: Boolean,
        private val blockAccept: Boolean,
        private val synchronousTerminal: Boolean,
    ) : SearchSessionPortV4 {
        val terminal = AtomicReference<SearchTerminalV3>()
        val acceptedTerminal = AtomicReference<SearchTerminalV3>()
        val downstream = AtomicReference<SearchSessionObserverV3>()
        val forwardingThread = AtomicReference<Thread>()
        val acceptCalls = AtomicInteger()
        val acceptEntered = CountDownLatch(1)
        val continueAccept = CountDownLatch(1)
        val terminalArrived = CountDownLatch(1)
        val terminalForwarded = CountDownLatch(1)
        val continueStart = CountDownLatch(1)

        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            downstream.set(observer)
            val forward = object : SearchSessionObserverV3 {
                override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = observer.onProgress(phaseEpoch, progress)
                override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 {
                    events.add("offer")
                    val receipt = observer.onResult(offer)
                    events.add("receipt-$receipt")
                    if (injectStale) {
                        check(observer.onResult(offer.copy(result = duplicate)) == SearchResultReceiptV3.IGNORED_CLOSED)
                        check(observer.onResult(offer.copy(sessionGeneration = offer.sessionGeneration + 1, result = stale)) == SearchResultReceiptV3.IGNORED_CLOSED)
                    }
                    return receipt
                }
                override fun onTerminal(value: SearchTerminalV3): Unit {
                    terminal.set(value)
                    terminalArrived.countDown()
                    if (synchronousTerminal) {
                        check(terminalForwarded.await(3, TimeUnit.SECONDS))
                    } else {
                        observer.onTerminal(value)
                        terminalForwarded.countDown()
                    }
                }
            }
            val result = producer.start(snapshot, phaseEpoch, forward)
            if (synchronousTerminal && result == SearchStartV3.ACCEPTED) {
                check(terminalArrived.await(3, TimeUnit.SECONDS))
                forwardingThread.set(Thread.currentThread())
                observer.onTerminal(checkNotNull(terminal.get()))
                events.add("terminal-forwarded")
                terminalForwarded.countDown()
                check(continueStart.await(3, TimeUnit.SECONDS))
                events.add("start-returning")
            }
            return result
        }

        override fun acceptTerminal(value: SearchTerminalV3): Boolean {
            acceptCalls.incrementAndGet()
            check(marker.read() == MarkerState.ACTIVE)
            check(input.closes.get() == 1)
            check(events.contains("session-close") && events.contains("anchor-close"))
            acceptEntered.countDown()
            if (blockAccept) check(continueAccept.await(3, TimeUnit.SECONDS))
            acceptedTerminal.set(value)
            val accepted = producer.acceptTerminal(value)
            events.add("accept-$accepted")
            return accepted
        }

        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)
    }

    private class Input(private val bytes: ByteArray, private val events: MutableList<String>? = null) : PrivateInputV1 {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        override fun open(): InputStream {
            opens.incrementAndGet()
            return object : ByteArrayInputStream(bytes) {
                override fun close(): Unit { events?.add("stream-close"); super.close() }
            }
        }
        override fun close(): Unit { closes.incrementAndGet(); events?.add("input-close") }
    }

    private class Marker(private val events: MutableList<String>) : SessionMarker {
        @Volatile private var value = MarkerState.CLEAR
        val clears = AtomicInteger()
        override fun read(): MarkerState = value
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; events.add("marker-active"); return true }
        override fun markClear(): Boolean { clears.incrementAndGet(); events.add("marker-clear"); value = MarkerState.CLEAR; return true }
    }

    private class Storage : ResultStorage {
        @Volatile var bytes: ByteArray? = null
        private var revision: ResultRevision? = null
        private var sha256: String? = null
        private var manifest: ResultManifest? = null
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            this.revision = revision
            this.sha256 = sha256
            bytes = input.readBytes()
            return "synthetic-generation"
        }
        override fun readGeneration(generation: String): StoredResult? = bytes?.let { StoredResult(checkNotNull(revision), checkNotNull(sha256), it.inputStream()) }
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { bytes = null }
    }
}
