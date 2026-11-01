package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchidentity.requireCoreIdentity
import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SearchIdentityFailureJointTest {
    @Test fun identityFailureAllowsRetryOnlyAfterOwnerAcceptanceAndMarkerReadback(): Unit {
        val test = Harness()
        assertEquals(SearchStartV3.ACCEPTED, test.start())
        assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.FAILED, test.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertTrue(test.admissionAvailable())
        assertEquals(0, test.workerStarts.get())
        test.assertOrder("profiles-close", "owner-close", "accept-true", "marker-clear", "marker-read-CLEAR")

        assertEquals(SearchStartV3.ACCEPTED, test.start())
        assertTrue(test.secondTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.FAILED, test.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertEquals(0, test.workerStarts.get())
    }

    @Test fun unknownCleanupOrAcceptanceKeepsRetryBlocked(): Unit {
        for (failure in listOf(Failure.PROFILES_CLOSE, Failure.OWNER_CLOSE, Failure.TERMINAL_ACCEPT, Failure.RETIRE)) {
            val test = Harness(failure)
            assertEquals(SearchStartV3.ACCEPTED, test.start())
            assertTrue("$failure did not finish", test.firstTerminal.await(5, TimeUnit.SECONDS))
            assertEquals(failure.toString(), SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertFalse(test.admissionAvailable())
            assertEquals(0, test.marker.clears.get())
            assertEquals(0, test.workerStarts.get())
            assertEquals(SearchStartV3.BUSY, test.start())
        }
    }

    @Test fun failedMarkerReadbackKeepsRetryBlocked(): Unit {
        val test = Harness(Failure.MARKER_READBACK)
        assertEquals(SearchStartV3.ACCEPTED, test.start())
        assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
        assertEquals(MarkerState.UNKNOWN, test.marker.read())
        assertEquals(1, test.marker.clears.get())
        assertEquals(0, test.workerStarts.get())
        assertEquals(SearchStartV3.BUSY, test.start())
    }

    @Test fun cancellationDuringIdentityCleanupKeepsRetryBlocked(): Unit {
        val test = Harness(Failure.CANCEL_DURING_CLOSE)
        try {
            assertEquals(SearchStartV3.ACCEPTED, test.start())
            assertTrue(test.closeEntered.await(5, TimeUnit.SECONDS))
            test.controller.cancel()
        } finally {
            test.continueClose.countDown()
        }
        assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, test.marker.read())
        assertFalse(test.admissionAvailable())
        assertEquals(0, test.marker.clears.get())
        assertEquals(0, test.workerStarts.get())
        assertEquals(SearchStartV3.BUSY, test.start())
    }

    @Test fun genericFailureDuringOrAfterIdentityStillKeepsRetryBlocked(): Unit {
        for (failure in listOf(Failure.RESOLVER_EXCEPTION, Failure.LATER_FAILURE)) {
            val test = Harness(failure)
            assertEquals(SearchStartV3.ACCEPTED, test.start())
            assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
            assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertFalse(test.admissionAvailable())
            assertEquals(0, test.marker.clears.get())
            assertEquals(SearchStartV3.BUSY, test.start())
        }
    }

    private enum class Failure { NONE, PROFILES_CLOSE, OWNER_CLOSE, TERMINAL_ACCEPT, MARKER_READBACK, CANCEL_DURING_CLOSE, RETIRE, RESOLVER_EXCEPTION, LATER_FAILURE }

    private class Harness(private val failure: Failure = Failure.NONE) {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val closeEntered = CountDownLatch(1)
        val continueClose = CountDownLatch(1)
        val firstTerminal = CountDownLatch(1)
        val secondTerminal = CountDownLatch(2)
        val workerStarts = AtomicInteger()
        val admission = SearchOwnerAdmission()
        val clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
        val marker = Marker(events, failure == Failure.MARKER_READBACK)
        val producer = SearchSessionProducer(if (failure == Failure.RETIRE) 200 else 5_000, { snapshot, lease, _ ->
            snapshot.profiles.open().use { it.readBytes() }
            if (failure == Failure.RETIRE) check(lease.reserveWorker())
            requireCoreIdentity {
                when (failure) {
                    Failure.RESOLVER_EXCEPTION -> throw IOException("synthetic_resolver_failure")
                    Failure.LATER_FAILURE -> "a".repeat(64)
                    else -> null
                }
            }
            workerStarts.incrementAndGet()
            throw IOException("synthetic_later_failure")
        }, clock)
        val port = object : SearchSessionPortV4 {
            override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
                val token = checkNotNull(admission.reserve())
                val lease = SearchOwnerLease(42, admission, token, {
                    events.add("owner-close")
                    failure != Failure.OWNER_CLOSE
                }, clock)
                check(producer.offerLease(lease) == SearchLeaseHandoff.CLAIMED)
                return producer.start(snapshot, phaseEpoch, observer)
            }

            override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
                if (failure == Failure.TERMINAL_ACCEPT) producer.ownerLost(terminal.sessionGeneration)
                val accepted = producer.acceptTerminal(terminal)
                events.add("accept-$accepted")
                return accepted
            }

            override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)
            override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)
            override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)
        }
        val controller = SearchController(port, ResultStore(Storage()), marker).apply {
            setPhaseListener { state ->
                if (state.phase == SearchPhase.FAILED || state.phase == SearchPhase.INTERRUPTED) {
                    firstTerminal.countDown()
                    secondTerminal.countDown()
                }
            }
        }

        fun start(): SearchStartV3 = controller.start(1, 2, Input(), true)

        fun admissionAvailable(): Boolean {
            val token = admission.reserve() ?: return false
            admission.release(token)
            return true
        }

        fun assertOrder(vararg wanted: String): Unit {
            val snapshot = events.toList()
            var previous = -1
            for (event in wanted) {
                previous = snapshot.indexOfFirstAfter(previous, event)
                assertTrue("$event out of order: $snapshot", previous >= 0)
            }
        }

        private inner class Input : PrivateInputV1 {
            override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1))
            override fun close(): Unit {
                events.add("profiles-close")
                if (failure == Failure.CANCEL_DURING_CLOSE) {
                    closeEntered.countDown()
                    check(continueClose.await(5, TimeUnit.SECONDS))
                }
                if (failure == Failure.PROFILES_CLOSE) throw IOException("synthetic_profiles_close")
            }
        }
    }

    private class Marker(private val events: MutableList<String>, private val failReadback: Boolean) : SessionMarker {
        @Volatile private var value = MarkerState.CLEAR
        val clears = AtomicInteger()
        override fun read(): MarkerState = value.also { events.add("marker-read-$it") }
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean {
            clears.incrementAndGet()
            events.add("marker-clear")
            value = if (failReadback) MarkerState.UNKNOWN else MarkerState.CLEAR
            return true
        }
    }

    private class Storage : ResultStorage {
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String = error("unexpected result")
        override fun readGeneration(generation: String): StoredResult? = null
        override fun readManifest(): ResultManifest? = null
        override fun publish(manifest: ResultManifest): Unit = error("unexpected result")
        override fun discard(generation: String): Unit = Unit
    }
}

private fun List<String>.indexOfFirstAfter(previous: Int, value: String): Int {
    for (index in previous + 1 until size) if (this[index] == value) return index
    return -1
}
