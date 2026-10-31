package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV4
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SearchStartPreparation
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.ResultManifest
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.RevisionStorage
import com.seraf.vpnsweep.searchstorage.StoredResult
import com.seraf.vpnsweep.searchstorage.digest
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultActivationCompositionTest {
    @Test
    fun currentRevisionAndSnapshotReservationShareTheControllerMonitor(): Unit {
        val f = Fixture()
        assertTrue(f.composition.withSourceMutation { ResultRevision("1", "2") })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutation = async {
            f.composition.withSourceMutation {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                ResultRevision("3", "2")
            }
        }
        check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val (read, reader) = asyncWithThread { f.composition.currentRevision() }
        try {
            awaitBlockedAt(reader, ResultActivationComposition::class.java.name, "currentRevision")
        } finally {
            release.countDown()
        }
        assertTrue(mutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(ResultRevision("3", "2"), read.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val subscriptionLock = Any()
        f.port.onStart = {
            assertFalse(Thread.holdsLock(subscriptionLock))
            assertFalse(Thread.holdsLock(f.composition.controller))
        }
        lateinit var profiles: Input
        val prepared = synchronized(subscriptionLock) {
            synchronized(f.composition.controller) {
                val current = f.composition.currentRevision()!!
                assertEquals(ResultRevision("3", "2"), current)
                assertTrue(Thread.holdsLock(subscriptionLock))
                assertTrue(Thread.holdsLock(f.composition.controller))
                profiles = Input()
                f.composition.activation.reserveSearch(current.source.toLong(), current.selection.toLong(), profiles)
            }
        } as SearchStartPreparation.Reserved
        assertEquals(0, f.port.starts)
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        assertEquals(1, f.port.starts)
        assertEquals(1, profiles.closes)
        f.composition.controller.cancel()
        f.port.cancelled()
    }

    @Test
    fun missingOrUnreadableCurrentRevisionCannotStartSearch(): Unit {
        val f = Fixture()
        assertNull(f.composition.currentRevision())
        assertEquals(SearchStartV3.REJECTED, denied(f.composition.activation.reserveSearch(1, 2, Input())))
        assertEquals(0, f.port.starts)
        assertTrue(f.composition.withSourceMutation { ResultRevision("1", "2") })
        f.revisionStorage.failRead = true
        assertNull(f.composition.currentRevision())
        assertEquals(SearchStartV3.REJECTED, denied(f.composition.activation.reserveSearch(1, 2, Input())))
        assertEquals(0, f.port.starts)
    }

    @Test
    fun publishedResultAndVpnReservationUseTheSameComposition(): Unit {
        val f = Fixture()
        assertSame(f.registry, f.composition.vpnRuns)
        f.publishResult()
        assertEquals(SearchPhase.COMPLETE, f.composition.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, f.marker.state)
        assertTrue(f.composition.activation.ready())

        val admission = f.composition.activation.admitVpn { f.registry.reserve(stopping = {}) }
            as VpnAdmission.Admitted
        assertArrayEquals(f.result, admission.bytes)
        assertFalse(f.registry.available())
        assertEquals(SearchStartV3.BUSY, denied(f.composition.activation.reserveSearch(1, 2, Input())))
        assertUnavailable(f.composition)

        assertTrue(admission.reservation.claimRelease())
        assertTrue(f.registry.available())
        assertTrue(f.composition.activation.ready())
    }

    @Test
    fun sourceMutationAndPowerAdmissionUseTheSameControllerMonitor(): Unit {
        val f = Fixture()
        f.publishResult()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutation = async {
            f.composition.withSourceMutation {
                assertTrue(Thread.holdsLock(f.composition.controller))
                assertEquals(MarkerState.ACTIVE, f.marker.state)
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                ResultRevision("3", "2")
            }
        }
        check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val reserveCalls = AtomicInteger()
        val (admission, admissionThread) = asyncWithThread {
            f.composition.activation.admitVpn {
                reserveCalls.incrementAndGet()
                f.registry.reserve(stopping = {})
            }
        }
        try {
            awaitBlockedAt(admissionThread, SearchController::class.java.name, "withSourceMutation")
            assertEquals(0, reserveCalls.get())
        } finally {
            release.countDown()
        }
        assertTrue(mutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(MarkerState.CLEAR, f.marker.state)
        assertTrue(admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) is VpnAdmission.Unavailable)
        assertEquals(0, reserveCalls.get())
        assertTrue(f.registry.available())
        assertEquals(SearchStartV3.REJECTED, denied(f.composition.activation.reserveSearch(1, 2, Input())))
        val prepared = f.composition.activation.reserveSearch(3, 2, Input()) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        f.composition.controller.cancel()
        f.port.cancelled()
    }

    @Test
    fun sourceMutationIsNotInvokedDuringAnActiveSearch(): Unit {
        val f = Fixture()
        assertTrue(f.composition.withSourceMutation { ResultRevision("1", "2") })
        val prepared = f.composition.activation.reserveSearch(1, 2, Input()) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        var invoked = false
        assertFalse(f.composition.withSourceMutation {
            invoked = true
            ResultRevision("3", "2")
        })
        assertFalse(invoked)
        f.composition.controller.cancel()
        f.port.cancelled()
    }

    @Test
    fun invalidOrUncertainRevisionAfterMutationBlocksAcrossFakeRestart(): Unit {
        for (case in 0..3) {
            val f = Fixture()
            f.publishResult()
            f.revisionStorage.failBeforeWrite = case == 1
            f.revisionStorage.failAfterWrite = case == 2
            val clearCalls = f.marker.clearCalls
            var snapshot = "before"
            val failure = runCatching {
                f.composition.withSourceMutation {
                    assertEquals(MarkerState.ACTIVE, f.marker.state)
                    snapshot = "after"
                    if (case == 3) throw IOException("synthetic")
                    if (case == 0) ResultRevision("", "2") else ResultRevision("3", "2")
                }
            }.exceptionOrNull()
            assertTrue(when (case) {
                0 -> failure is IllegalArgumentException
                3 -> failure is IOException
                else -> failure is IllegalStateException
            })
            assertEquals("after", snapshot)
            assertEquals(if (case == 2) ResultRevision("3", "2") else ResultRevision("1", "2"), f.revisionStorage.value)
            assertEquals(MarkerState.ACTIVE, f.marker.state)
            assertEquals(clearCalls, f.marker.clearCalls)
            assertFalse(f.composition.activation.ready())
            assertEquals(SearchStartV3.BUSY, denied(f.composition.activation.reserveSearch(1, 2, Input())))
            assertUnavailable(f.composition)
            var retried = false
            assertFalse(f.composition.withSourceMutation {
                retried = true
                ResultRevision("4", "2")
            })
            assertFalse(retried)
            val recovered = f.recovered()
            assertEquals(SearchPhase.INTERRUPTED, recovered.controller.state.value.phase)
            assertFalse(recovered.activation.ready())
            assertEquals(SearchStartV3.BUSY, denied(recovered.activation.reserveSearch(1, 2, Input())))
            assertUnavailable(recovered)
        }
    }

    @Test
    fun mutationDoesNotStartWithoutConfirmedActiveMarker(): Unit {
        for (case in 0..2) {
            val f = Fixture()
            f.publishResult()
            f.marker.failBeforeActive = case == 0
            f.marker.failAfterActive = case == 1
            f.marker.unknownReadWhileActive = case == 2
            val clearCalls = f.marker.clearCalls
            var invoked = false
            val failure = runCatching {
                f.composition.withSourceMutation {
                    invoked = true
                    ResultRevision("3", "2")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(invoked)
            assertEquals(clearCalls, f.marker.clearCalls)
            assertFalse(f.composition.activation.ready())
            assertUnavailable(f.composition)
            f.marker.unknownReadWhileActive = false
            val recovered = f.recovered()
            if (case == 0) {
                assertEquals(MarkerState.CLEAR, f.marker.state)
                assertTrue(recovered.activation.ready())
            } else {
                assertEquals(MarkerState.ACTIVE, f.marker.state)
                assertEquals(SearchPhase.INTERRUPTED, recovered.controller.state.value.phase)
                assertUnavailable(recovered)
            }
        }
    }

    @Test
    fun unconfirmedClearAfterMutationBlocksThisComposition(): Unit {
        for (failAfter in listOf(false, true)) {
            val f = Fixture()
            f.publishResult()
            f.marker.failBeforeClear = !failAfter
            f.marker.failAfterClear = failAfter
            val clearCalls = f.marker.clearCalls
            var snapshot = "before"
            val failure = runCatching {
                f.composition.withSourceMutation {
                    snapshot = "after"
                    ResultRevision("3", "2")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("after", snapshot)
            assertEquals(ResultRevision("3", "2"), f.revisionStorage.value)
            assertEquals(clearCalls + 1, f.marker.clearCalls)
            assertFalse(f.composition.activation.ready())
            assertUnavailable(f.composition)
            val recovered = f.recovered()
            assertFalse(recovered.activation.ready())
            if (failAfter) {
                assertEquals(MarkerState.CLEAR, f.marker.state)
                assertEquals(SearchStartV3.REJECTED, denied(recovered.activation.reserveSearch(1, 2, Input())))
            } else {
                assertEquals(MarkerState.ACTIVE, f.marker.state)
                assertEquals(SearchPhase.INTERRUPTED, recovered.controller.state.value.phase)
                assertUnavailable(recovered)
            }
        }
    }

    @Test
    fun mutationCannotReuseCurrentOrPublishedResultRevision(): Unit {
        for (case in 0..2) {
            val f = Fixture()
            f.publishResult()
            var snapshot = "original"
            if (case == 1) {
                assertTrue(f.composition.withSourceMutation {
                    snapshot = "intermediate"
                    ResultRevision("3", "2")
                })
            }
            if (case == 2) f.revisionStorage.value = null
            val clearCalls = f.marker.clearCalls
            val failure = runCatching {
                f.composition.withSourceMutation {
                    snapshot = "changed"
                    ResultRevision("1", "2")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("changed", snapshot)
            assertEquals(MarkerState.ACTIVE, f.marker.state)
            assertEquals(clearCalls, f.marker.clearCalls)
            val expected = when (case) {
                0 -> ResultRevision("1", "2")
                1 -> ResultRevision("3", "2")
                else -> null
            }
            assertEquals(expected, f.revisionStorage.value)
            assertFalse(f.composition.activation.ready())
            assertUnavailable(f.composition)
            val recovered = f.recovered()
            assertEquals(SearchPhase.INTERRUPTED, recovered.controller.state.value.phase)
            assertFalse(recovered.activation.ready())
            assertUnavailable(recovered)
        }
    }

    @Test
    fun mutationDoesNotStartWhenCurrentOrPublishedRevisionCannotBeRead(): Unit {
        for (failCurrent in listOf(true, false)) {
            val f = Fixture()
            f.publishResult()
            f.revisionStorage.failRead = failCurrent
            f.storage.failManifestRead = !failCurrent
            var invoked = false
            val failure = runCatching {
                f.composition.withSourceMutation {
                    invoked = true
                    ResultRevision("3", "2")
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(invoked)
            assertEquals(MarkerState.ACTIVE, f.marker.state)
            assertUnavailable(f.composition)
            f.revisionStorage.failRead = false
            f.storage.failManifestRead = false
            val recovered = f.recovered()
            assertEquals(SearchPhase.INTERRUPTED, recovered.controller.state.value.phase)
            assertUnavailable(recovered)
        }
    }

    private fun denied(preparation: SearchStartPreparation): SearchStartV3 =
        (preparation as SearchStartPreparation.Denied).result

    private fun assertUnavailable(composition: ResultActivationComposition): Unit {
        var called = false
        assertTrue(composition.activation.admitVpn { called = true; Any() } is VpnAdmission.Unavailable)
        assertFalse(called)
    }

    private fun awaitBlockedAt(worker: Thread, owner: String, method: String): Unit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (true) {
            val frame = worker.stackTrace.firstOrNull()
            if (worker.state == Thread.State.BLOCKED && frame?.className == owner && frame.methodName == method) return
            check(worker.isAlive && System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private fun <T> async(action: () -> T): FutureTask<T> = asyncWithThread(action).first

    private fun <T> asyncWithThread(action: () -> T): Pair<FutureTask<T>, Thread> {
        val task = FutureTask(Callable { action() })
        return task to thread(start = true) { task.run() }
    }

    private fun compose(
        storage: ResultStorage,
        revisions: RevisionStorage,
        port: SearchSessionPortV3,
        marker: SessionMarker,
        registry: VpnRunRegistry,
    ): ResultActivationComposition {
        val constructor = ResultActivationComposition::class.java.getDeclaredConstructor(
            ResultStorage::class.java,
            RevisionStorage::class.java,
            SearchSessionPortV3::class.java,
            SessionMarker::class.java,
            VpnRunRegistry::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(storage, revisions, port, marker, registry)
    }

    private inner class Fixture {
        val storage = Storage()
        val revisionStorage = Revisions()
        val port = Port()
        val marker = Marker()
        val registry = VpnRunRegistry()
        val composition = compose(storage, revisionStorage, port, marker, registry)
        val result = "verified-synthetic-result".toByteArray()

        fun recovered(): ResultActivationComposition =
            compose(storage, revisionStorage, Port(), marker, VpnRunRegistry())

        fun publishResult(): Unit {
            assertTrue(composition.withSourceMutation { ResultRevision("1", "2") })
            val profiles = Input()
            val prepared = composition.activation.reserveSearch(1, 2, profiles) as SearchStartPreparation.Reserved
            assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
            assertEquals(1, profiles.closes)
            assertEquals(MarkerState.ACTIVE, marker.state)
            val input = Input(result)
            assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, port.offer(input))
            assertEquals(1, input.opens)
            assertEquals(1, input.streamCloses)
            assertEquals(1, input.closes)
            assertFalse(composition.activation.ready())
            port.complete()
        }
    }

    private class Marker : SessionMarker {
        var state = MarkerState.CLEAR
        var failBeforeActive = false
        var failAfterActive = false
        var unknownReadWhileActive = false
        var failBeforeClear = false
        var failAfterClear = false
        var clearCalls = 0
        override fun read(): MarkerState = if (unknownReadWhileActive && state == MarkerState.ACTIVE) MarkerState.UNKNOWN else state
        override fun markActive(): Boolean {
            if (failBeforeActive) return false
            state = MarkerState.ACTIVE
            return !failAfterActive
        }
        override fun markClear(): Boolean {
            clearCalls++
            if (failBeforeClear) return false
            state = MarkerState.CLEAR
            return !failAfterClear
        }
    }

    private class Revisions : RevisionStorage {
        var value: ResultRevision? = null
        var failRead = false
        var failBeforeWrite = false
        var failAfterWrite = false
        override fun read(): ResultRevision? {
            if (failRead) throw IOException("synthetic")
            return value
        }
        override fun write(revision: ResultRevision): Unit {
            if (failBeforeWrite) throw IOException("synthetic")
            value = revision
            if (failAfterWrite) throw IOException("synthetic")
        }
    }

    private class Storage : ResultStorage {
        private var result: Stored? = null
        private var manifest: ResultManifest? = null
        var failManifestRead = false
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            result = Stored(revision, sha256, input.readBytes())
            return "generation"
        }
        override fun readGeneration(generation: String): StoredResult? {
            if (generation != "generation") return null
            val stored = result ?: return null
            return StoredResult(stored.revision, stored.sha256, stored.bytes.inputStream())
        }
        override fun readManifest(): ResultManifest? {
            if (failManifestRead) throw IOException("synthetic")
            return manifest
        }
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { result = null }
        private class Stored(val revision: ResultRevision, val sha256: String, val bytes: ByteArray)
    }

    private class Input(val bytes: ByteArray = "synthetic-profiles".toByteArray()) : PrivateInputV1 {
        var opens = 0
        var streamCloses = 0
        var closes = 0
        override fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                override fun close(): Unit { streamCloses++; super.close() }
            }
        }
        override fun close(): Unit { closes++ }
    }

    private class Port : SearchSessionPortV4 {
        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean = true
        private var observer: SearchSessionObserverV3? = null
        private var generation = 0L
        private var phase = 0L
        var starts = 0
        var onStart: () -> Unit = {}
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            starts++
            onStart()
            generation = snapshot.sessionGeneration
            phase = phaseEpoch
            this.observer = observer
            snapshot.profiles.close()
            return SearchStartV3.ACCEPTED
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.REJECTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        fun offer(input: Input): SearchResultReceiptV3 = observer!!.onResult(
            SearchResultOfferV3(generation, phase, 1, 2, digest(input.bytes.inputStream()), input),
        )
        fun complete(): Unit = observer!!.onTerminal(
            SearchTerminalV3.Complete(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
        fun cancelled(): Unit = observer!!.onTerminal(
            SearchTerminalV3.Cancelled(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
    }
}

private const val TIMEOUT_SECONDS = 10L
