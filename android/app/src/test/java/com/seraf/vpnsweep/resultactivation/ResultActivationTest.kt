package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.importer.MAX_CONFIG_BYTES
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SearchStartPreparation
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.CurrentRevisionStore
import com.seraf.vpnsweep.searchstorage.DurabilityUnknownException
import com.seraf.vpnsweep.searchstorage.Publication
import com.seraf.vpnsweep.searchstorage.ResultManifest
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.ResultStore
import com.seraf.vpnsweep.searchstorage.RevisionStorage
import com.seraf.vpnsweep.searchstorage.StoredResult
import com.seraf.vpnsweep.searchstorage.digest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultActivationTest {
    private val current = ResultRevision("1", "2")
    private val previous = "previous-good".toByteArray()

    @Test
    fun onlyVerifiedCurrentBytesAreAdmittedAndVpnStateBlocksSearch(): Unit {
        val f = Fixture()
        f.publish(previous)
        assertTrue(f.activation.ready())
        val token = Any()
        val result = f.activation.admitVpn {
            f.vpnBusy = true
            token
        }
        val admitted = result as VpnAdmission.Admitted
        assertSame(token, admitted.reservation)
        assertArrayEquals(previous, admitted.bytes)
        assertFalse(f.activation.ready())
        val profiles = Input()
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, profiles)))
        assertFalse(profiles.closed)
        assertEquals(0, f.port.starts)
        f.vpnBusy = false
        assertTrue(f.activation.ready())
    }

    @Test
    fun mismatchedRevisionsAndUnusableRecordsFailClosedWithoutDiscardingPrevious(): Unit {
        val f = Fixture()
        f.publish(previous)
        for (next in listOf(ResultRevision("3", "2"), ResultRevision("1", "4"))) {
            assertTrue(f.activation.withSourceMutation { f.revisions.update(next) })
            assertFalse(f.activation.ready())
            assertEquals(SearchStartV3.REJECTED, denied(f.activation.reserveSearch(1, 2, Input())))
            assertUnavailable(f)
        }
        assertTrue(f.activation.withSourceMutation { f.revisions.update(current) })
        f.revisionStorage.failRead = true
        assertUnavailable(f)
        f.revisionStorage.failRead = false
        f.storage.failManifestRead = true
        assertUnavailable(f)
        f.storage.failManifestRead = false
        val saved = f.storage.manifest!!
        f.storage.manifest = null
        assertUnavailable(f)
        f.storage.manifest = saved
        val data = f.storage.generations.remove(saved.generation)!!
        assertUnavailable(f)
        f.storage.generations[saved.generation] = data
        assertTrue(f.activation.ready())
        assertArrayEquals(previous, f.storage.generations[saved.generation]!!.bytes)
    }

    @Test
    fun checksExactBytesAndMetadataAgainAfterStoreResolution(): Unit {
        val f = Fixture()
        f.publish(previous)
        val alterations: List<(Data) -> Data?> = listOf(
            { it.copy(bytes = "different".toByteArray()) },
            { it.copy(revision = ResultRevision("other", "2")) },
            { it.copy(sha256 = digest("different".byteInputStream())) },
            { null },
        )
        for (alter in alterations) {
            f.storage.reads = 0
            f.storage.transformRead = { count, data -> if (count == 2) alter(data) else data }
            assertUnavailable(f)
        }
        f.storage.transformRead = { _, data -> data }
        f.storage.reads = 0
        assertTrue(f.activation.ready())
    }

    @Test
    fun closesBothStoreReadStreamsAndDeniesWhenTheActivationReadCannotClose(): Unit {
        val f = Fixture()
        f.publish(previous)
        f.storage.reads = 0
        f.storage.closes = 0
        assertTrue(f.activation.ready())
        assertEquals(2, f.storage.reads)
        assertEquals(2, f.storage.closes)
        f.storage.reads = 0
        f.storage.closes = 0
        f.storage.failCloseAt = 2
        assertUnavailable(f)
        assertEquals(2, f.storage.closes)
    }

    @Test
    fun configSizeBoundariesAreEnforcedOnTheBytesGivenToVpn(): Unit {
        for (size in listOf(0, 1, MAX_CONFIG_BYTES - 1, MAX_CONFIG_BYTES, MAX_CONFIG_BYTES + 1)) {
            val f = Fixture()
            val bytes = ByteArray(size) { (it % 251).toByte() }
            f.publish(bytes)
            assertEquals(size in 1..MAX_CONFIG_BYTES, f.activation.ready())
            var called = false
            val outcome = f.activation.admitVpn {
                called = true
                f.vpnBusy = true
                Any()
            }
            assertEquals(size in 1..MAX_CONFIG_BYTES, called)
            assertEquals(size in 1..MAX_CONFIG_BYTES, outcome is VpnAdmission.Admitted<*>)
            if (outcome is VpnAdmission.Admitted<*>) assertArrayEquals(bytes, outcome.bytes)
        }
    }

    @Test
    fun markerIsReadAtEachAdmissionAndUnknownNeverMeansClear(): Unit {
        val f = Fixture()
        f.publish(previous)
        for (state in listOf(MarkerState.ACTIVE, MarkerState.UNKNOWN)) {
            f.marker.value = state
            assertFalse(f.activation.ready())
            assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
            assertUnavailable(f)
        }
        f.marker.value = MarkerState.CLEAR
        f.marker.failRead = true
        assertUnavailable(f)
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
        f.marker.failRead = false
        assertTrue(f.activation.ready())
    }

    @Test
    fun ambiguousReservationPermanentlyBlocksThisCoordinator(): Unit {
        val f = Fixture()
        f.publish(previous)
        val outcome = f.activation.admitVpn<Any> {
            f.vpnBusy = true
            throw IOException("synthetic")
        }
        assertTrue(outcome is VpnAdmission.Unknown<*>)
        f.vpnBusy = false
        assertUnavailable(f)
        assertFalse(f.activation.withSourceMutation { true })
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
    }

    @Test
    fun reservationContradictingStateReturnsOnlyCleanupHandle(): Unit {
        val f = Fixture()
        f.publish(previous)
        val handle = Any()
        val outcome = f.activation.admitVpn { handle }
        assertSame(handle, (outcome as VpnAdmission.Unknown).cleanupReservation)
        assertUnavailable(f)
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
    }

    @Test
    fun unreadableVpnStateAfterReservationReturnsOnlyCleanupHandle(): Unit {
        val f = Fixture()
        f.publish(previous)
        val handle = Any()
        val outcome = f.activation.admitVpn {
            f.vpnBusy = true
            f.vpnReadFailed = true
            handle
        }
        assertSame(handle, (outcome as VpnAdmission.Unknown).cleanupReservation)
        f.vpnReadFailed = false
        f.vpnBusy = false
        assertUnavailable(f)
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
    }

    @Test
    fun searchReadsVpnStateAfterConcurrentPowerReservation(): Unit {
        val f = Fixture()
        f.publish(previous)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val admission = async {
            f.activation.admitVpn {
                entered.countDown()
                await(release)
                f.vpnBusy = true
                Any()
            }
        }
        await(entered)
        val profiles = Input()
        val (search, searchThread) = asyncWithThread { f.activation.reserveSearch(1, 2, profiles) }
        awaitBlockedAt(searchThread, ResultActivation::class.java.name, "reserveSearch")
        release.countDown()
        assertTrue(admission.get(10, TimeUnit.SECONDS) is VpnAdmission.Admitted<*>)
        assertEquals(SearchStartV3.BUSY, denied(search.get(10, TimeUnit.SECONDS)))
        assertFalse(profiles.closed)
        assertEquals(0, f.port.starts)
    }

    @Test
    fun concurrentPowerRequestsCannotBothReserve(): Unit {
        val f = Fixture()
        f.publish(previous)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = async {
            f.activation.admitVpn {
                entered.countDown()
                await(release)
                f.vpnBusy = true
                Any()
            }
        }
        await(entered)
        var secondCalled = false
        val (second, secondThread) = asyncWithThread { f.activation.admitVpn { secondCalled = true; Any() } }
        awaitBlockedAt(secondThread, SearchController::class.java.name, "withSourceMutation")
        release.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is VpnAdmission.Admitted<*>)
        assertTrue(second.get(10, TimeUnit.SECONDS) is VpnAdmission.Unavailable)
        assertFalse(secondCalled)
    }

    @Test
    fun reentrantSearchDuringReservationCannotUseDisconnectedSnapshot(): Unit {
        val f = Fixture()
        f.publish(previous)
        val admission = f.activation.admitVpn {
            assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
            f.vpnBusy = true
            Any()
        }
        assertTrue(admission is VpnAdmission.Admitted<*>)
        assertEquals(0, f.port.starts)
    }

    @Test
    fun reentrantAdmissionDuringSourceMutationCannotUsePartialState(): Unit {
        val f = Fixture()
        f.publish(previous)
        assertTrue(f.activation.withSourceMutation {
            assertTrue(f.revisions.update(ResultRevision("1", "changing")))
            assertUnavailable(f)
            assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
            f.revisions.update(current)
        })
        assertTrue(f.activation.ready())
    }

    @Test
    fun reentrantAdmissionDuringVpnStateReadIsDenied(): Unit {
        val f = Fixture()
        f.publish(previous)
        f.onVpnRead = {
            assertUnavailable(f)
            assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
        }
        val admission = f.activation.admitVpn { f.vpnBusy = true; Any() }
        assertTrue(admission is VpnAdmission.Admitted<*>)
    }

    @Test
    fun uncertainSourceMutationPreservesCauseAndBlocksAdmission(): Unit {
        val f = Fixture()
        f.publish(previous)
        val failure = IOException("synthetic")
        try {
            f.activation.withSourceMutation { throw failure }
            throw AssertionError("mutation should fail")
        } catch (caught: IOException) {
            assertSame(failure, caught)
        }
        assertUnavailable(f)
        assertEquals(SearchStartV3.BUSY, denied(f.activation.reserveSearch(1, 2, Input())))
    }

    @Test
    fun sourceMutationAndPowerCheckUseSameGuard(): Unit {
        val f = Fixture()
        f.publish(previous)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutation = async {
            f.activation.withSourceMutation {
                entered.countDown()
                await(release)
                f.revisions.update(ResultRevision("1", "changed"))
            }
        }
        await(entered)
        val (admission, admissionThread) = asyncWithThread { f.activation.admitVpn { f.vpnBusy = true; Any() } }
        awaitBlockedAt(admissionThread, SearchController::class.java.name, "withSourceMutation")
        release.countDown()
        assertTrue(mutation.get(10, TimeUnit.SECONDS))
        assertTrue(admission.get(10, TimeUnit.SECONDS) is VpnAdmission.Unavailable)
        assertFalse(f.vpnBusy)
        assertNotNull(f.store.resolve(current))
    }

    @Test
    fun cancellationBeforeCommitKeepsAndAdmitsPreviousGood(): Unit {
        val f = Fixture()
        f.publish(previous)
        f.startSearch()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.storage.onPrepare = { entered.countDown(); await(release) }
        val offered = Input("replacement".toByteArray())
        val publication = async { f.port.offer(offered) }
        await(entered)
        f.controller.cancel()
        assertUnavailable(f)
        release.countDown()
        assertEquals(SearchResultReceiptV3.CANCELLED_CLOSED, publication.get(10, TimeUnit.SECONDS))
        assertTrue(offered.closed)
        assertTrue(offered.streamClosed)
        f.port.cancelled()
        assertEquals(SearchPhase.CANCELLED, f.controller.state.value.phase)
        val admission = f.activation.admitVpn {
            f.vpnBusy = true
            Any()
        }
        assertArrayEquals(previous, (admission as VpnAdmission.Admitted).bytes)
        assertEquals(1, f.storage.generations.size)
    }

    @Test
    fun committedResultWinsOverLaterCancelButWaitsForTerminalFence(): Unit {
        val f = Fixture()
        f.publish(previous)
        f.startSearch()
        val offered = Input("replacement".toByteArray())
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, f.port.offer(offered))
        assertTrue(offered.closed)
        assertTrue(offered.streamClosed)
        f.controller.cancel()
        assertUnavailable(f)
        f.port.complete()
        assertEquals(SearchPhase.COMPLETE, f.controller.state.value.phase)
        val admission = f.activation.admitVpn {
            f.vpnBusy = true
            Any()
        }
        assertArrayEquals("replacement".toByteArray(), (admission as VpnAdmission.Admitted).bytes)
    }

    @Test
    fun cancelRacingAnAdmittedCommitCannotReportCancelled(): Unit {
        val f = Fixture()
        f.publish(previous)
        f.startSearch()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.storage.onPublish = { entered.countDown(); await(release) }
        val publication = async { f.port.offer(Input("replacement".toByteArray())) }
        await(entered)
        val (cancel, cancelThread) = asyncWithThread { f.controller.cancel() }
        awaitBlockedAt(cancelThread, ResultStore::class.java.name, "cancel")
        release.countDown()
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, publication.get(10, TimeUnit.SECONDS))
        cancel.get(10, TimeUnit.SECONDS)
        f.port.complete()
        assertEquals(SearchPhase.COMPLETE, f.controller.state.value.phase)
        val admission = f.activation.admitVpn { f.vpnBusy = true; Any() }
        assertArrayEquals("replacement".toByteArray(), (admission as VpnAdmission.Admitted).bytes)
    }

    @Test
    fun uncertainPublicationOrUnconfirmedCleanupRetainMarkerAndBlockPower(): Unit {
        for (case in 0..4) {
            val f = Fixture()
            f.publish(previous)
            f.startSearch()
            val offered = Input("replacement".toByteArray(), failClose = case == 1)
            if (case == 0) f.storage.unknownPublish = true
            val receipt = f.port.offer(offered)
            if (case < 2) assertEquals(SearchResultReceiptV3.UNKNOWN, receipt)
            else assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, receipt)
            if (case == 2) f.port.complete(SearchResourceStateV2.QUARANTINED, SearchResultFenceV3.CONFIRMED)
            if (case == 3) f.port.complete(SearchResourceStateV2.RELEASED, SearchResultFenceV3.UNKNOWN)
            if (case == 4) {
                f.marker.failClear = true
                f.port.complete()
            }
            assertEquals(MarkerState.ACTIVE, f.marker.value)
            assertUnavailable(f)
            val recovered = SearchController(Port(), ResultStore(f.storage), f.marker)
            assertEquals(SearchPhase.INTERRUPTED, recovered.state.value.phase)
        }
    }

    @Test
    fun lateOfferCannotReplayAndFailedCloseBlocksAdmission(): Unit {
        for (closeFails in listOf(false, true)) {
            val f = Fixture()
            f.publish(previous)
            f.startSearch()
            assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, f.port.offer(Input("new".toByteArray())))
            val old = f.port.observer!!
            f.port.complete()
            val committed = f.storage.manifest
            val late = Input("stale".toByteArray(), failClose = closeFails)
            val receipt = old.onResult(SearchResultOfferV3(f.port.generation, f.port.phase, 1, 2, digest(late.bytes.inputStream()), late))
            assertEquals(if (closeFails) SearchResultReceiptV3.UNKNOWN else SearchResultReceiptV3.IGNORED_CLOSED, receipt)
            assertEquals(0, late.opens)
            assertEquals(!closeFails, late.closed)
            assertEquals(committed, f.storage.manifest)
            assertEquals(!closeFails, f.activation.ready())
        }
    }

    private fun denied(preparation: SearchStartPreparation): SearchStartV3 = (preparation as SearchStartPreparation.Denied).result

    private fun assertUnavailable(f: Fixture): Unit {
        var called = false
        val outcome = f.activation.admitVpn { called = true; Any() }
        assertTrue(outcome is VpnAdmission.Unavailable)
        assertFalse(called)
    }

    private fun await(latch: CountDownLatch): Unit = check(latch.await(10, TimeUnit.SECONDS))

    private fun awaitBlockedAt(worker: Thread, owner: String, method: String): Unit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
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
        val worker = thread(start = true) { task.run() }
        return task to worker
    }

    private inner class Fixture {
        val storage = Storage()
        val store = ResultStore(storage)
        val revisionStorage = Revisions()
        val revisions = CurrentRevisionStore(revisionStorage)
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, store, marker)
        @Volatile var vpnBusy = false
        @Volatile var vpnReadFailed = false
        var onVpnRead: () -> Unit = {}
        val activation = ResultActivation(controller, store, storage, revisions, marker) {
            onVpnRead()
            if (vpnReadFailed) throw IOException("synthetic")
            !vpnBusy
        }

        init { assertTrue(revisions.update(current)) }

        fun publish(bytes: ByteArray): Unit {
            val result = store.publish(store.begin(current)!!, bytes.inputStream(), digest(bytes.inputStream()))
            assertEquals(Publication.SUCCESS, result)
        }

        fun startSearch(): Unit {
            val prepared = activation.reserveSearch(1, 2, Input()) as SearchStartPreparation.Reserved
            assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
            assertEquals(MarkerState.ACTIVE, marker.value)
        }
    }

    private class Marker : SessionMarker {
        var value = MarkerState.CLEAR
        var failRead = false
        var failClear = false
        override fun read(): MarkerState {
            if (failRead) throw IOException("synthetic")
            return value
        }
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean {
            if (failClear) return false
            value = MarkerState.CLEAR
            return true
        }
    }

    private class Revisions : RevisionStorage {
        var value: ResultRevision? = null
        var failRead = false
        override fun read(): ResultRevision? {
            if (failRead) throw IOException("synthetic")
            return value
        }
        override fun write(revision: ResultRevision): Unit { value = revision }
    }

    private data class Data(val revision: ResultRevision, val sha256: String, val bytes: ByteArray)

    private class Storage : ResultStorage {
        val generations = mutableMapOf<String, Data>()
        var manifest: ResultManifest? = null
        var reads = 0
        var closes = 0
        var failCloseAt = 0
        var next = 0
        var failManifestRead = false
        var unknownPublish = false
        var onPrepare: () -> Unit = {}
        var onPublish: () -> Unit = {}
        var transformRead: (Int, Data) -> Data? = { _, data -> data }
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            val id = (next++).toString()
            generations[id] = Data(revision, sha256, input.readBytes())
            onPrepare()
            return id
        }
        override fun readGeneration(generation: String): StoredResult? {
            val data = generations[generation] ?: return null
            val read = ++reads
            val actual = transformRead(read, data) ?: return null
            val stream = object : ByteArrayInputStream(actual.bytes) {
                override fun close(): Unit {
                    closes++
                    if (read == failCloseAt) throw IOException("synthetic")
                    super.close()
                }
            }
            return StoredResult(actual.revision, actual.sha256, stream)
        }
        override fun readManifest(): ResultManifest? {
            if (failManifestRead) throw IOException("synthetic")
            return manifest
        }
        override fun publish(manifest: ResultManifest): Unit {
            onPublish()
            this.manifest = manifest
            if (unknownPublish) throw DurabilityUnknownException(IOException("synthetic"))
        }
        override fun discard(generation: String): Unit { generations.remove(generation) }
    }

    private class Input(val bytes: ByteArray = "profiles".toByteArray(), val failClose: Boolean = false) : PrivateInputV1 {
        var opens = 0
        var closed = false
        var streamClosed = false
        override fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                override fun close(): Unit { streamClosed = true; super.close() }
            }
        }
        override fun close(): Unit {
            if (failClose) throw IOException("synthetic")
            closed = true
        }
    }

    private class Port : SearchSessionPortV3 {
        var starts = 0
        var observer: SearchSessionObserverV3? = null
        var generation = 0L
        var phase = 0L
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            starts++
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
        fun complete(
            resources: SearchResourceStateV2 = SearchResourceStateV2.RELEASED,
            fence: SearchResultFenceV3 = SearchResultFenceV3.CONFIRMED,
        ): Unit = observer!!.onTerminal(SearchTerminalV3.Complete(generation, phase, resources, fence))
        fun cancelled(): Unit = observer!!.onTerminal(
            SearchTerminalV3.Cancelled(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
    }
}
