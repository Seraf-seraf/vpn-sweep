package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.emitWorkerDiagnostic
import com.seraf.vpnsweep.networkPolicyEvent
import com.seraf.vpnsweep.startSubscriptionIfSafe
import com.seraf.vpnsweep.resultactivation.MemorySearchCompletionReceiptStorage
import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.resultactivation.SearchCompletionReceiptStorage
import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstate.*
import com.seraf.vpnsweep.searchstorage.*
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.subscriptionprotocol.*
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionSnapshotFiles
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage
import com.seraf.vpnsweep.ui.updateVisibleSelection
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchNetworkLifecycleJointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preWorkerNetworkEventReleasesOwnershipThenSelectionAndRetryWork(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        val before = test.composition.currentRevision()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.FAILED, test.composition.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertEquals(listOf(ScreenEvent.SEARCH_INIT_NETWORK_CALLBACK_EVENT), test.events.toList())
        assertEquals(0, test.workerStarts.get())
        test.assertOrder("unregister", "profiles-close", "owner-close", "accept-true", "marker-clear", "marker-read-CLEAR")

        assertTrue(test.subscriptions.select(setOf(ID_B)))
        val after = checkNotNull(test.composition.currentRevision())
        assertNotEquals(before, after)
        val restored = checkNotNull(test.storage.recover(after))
        assertEquals(setOf(ID_B), restored.selectedIds)
        restored.snapshot.close()

        test.blockSecond = true
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        assertTrue(test.secondNetworkCheck.await(5, TimeUnit.SECONDS))
        val old = test.observers.first()
        old.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.CAPABILITY_BLOCKED,
            SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
        emitWorkerDiagnostic(2, 1, ScreenEvent.SEARCH_INIT_NETWORK_BLOCKED, test.events::add)
        assertEquals(SearchPhase.RUNNING, test.composition.controller.state.value.phase)
        assertEquals(MarkerState.ACTIVE, test.marker.read())
        assertEquals(setOf(ID_B), test.subscriptions.state.value.selectedIds)
        assertEquals(1, test.events.size)
        test.continueSecond.countDown()
        assertTrue(test.secondTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.FAILED, test.composition.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertEquals(0, test.workerStarts.get())
    }

    @Test fun unconfirmedCleanupBlocksMutationButKeepsLoadedSnapshotVisible(): Unit {
        for (failure in listOf(Failure.UNREGISTER, Failure.LEASE, Failure.PROFILES_CLOSE, Failure.OWNER_CLOSE, Failure.ACCEPT, Failure.READBACK)) {
            val test = Harness(temporary.newFolder(), failure)
            test.installSelection()
            val before = checkNotNull(test.composition.currentRevision())
            test.marker.failReadback = failure == Failure.READBACK
            assertEquals(failure.toString(), SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertTrue(failure.toString(), test.firstTerminal.await(5, TimeUnit.SECONDS))
            assertEquals(failure.toString(), SearchPhase.INTERRUPTED, test.composition.controller.state.value.phase)
            assertFalse(failure.toString(), test.subscriptions.canSelect())
            val draft = updateVisibleSelection(test.subscriptions.state.value.selectedIds, setOf(ID_B), true)
            assertEquals(setOf(ID_A, ID_B), draft)
            assertFalse(failure.toString(), test.subscriptions.select(setOf(ID_B)))
            assertEquals(failure.toString(), setOf(ID_A), test.subscriptions.state.value.selectedIds)
            assertEquals(failure.toString(), 2, test.subscriptions.state.value.records.size)
            assertEquals(failure.toString(), SubscriptionStartV1.BUSY, test.subscriptions.load("synthetic refresh"))
            assertEquals(failure.toString(), 1, test.source.dispatched)
            assertEquals(failure.toString(), before, test.composition.currentRevision())
            val restored = checkNotNull(test.storage.recover(before))
            assertEquals(setOf(ID_A), restored.selectedIds)
            restored.snapshot.close()
            assertEquals(failure.toString(), SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(failure.toString(), 0, test.workerStarts.get())
        }
    }

    @Test fun cancellationDuringNetworkCheckRetiresOnlyThatJob(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        test.blockFirst = true
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        assertTrue(test.firstNetworkCheck.await(5, TimeUnit.SECONDS))
        test.composition.controller.cancel()
        test.continueFirst.countDown()
        assertTrue(test.firstTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(SearchPhase.CANCELLED, test.composition.controller.state.value.phase)
        assertEquals(MarkerState.CLEAR, test.marker.read())
        assertTrue(test.subscriptions.select(setOf(ID_B)))
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        assertTrue(test.secondTerminal.await(5, TimeUnit.SECONDS))
        assertEquals(0, test.workerStarts.get())
    }

    private enum class Failure { NONE, UNREGISTER, LEASE, PROFILES_CLOSE, OWNER_CLOSE, ACCEPT, READBACK }

    private class Harness(root: File, val failure: Failure = Failure.NONE) {
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val events = Collections.synchronizedList(mutableListOf<ScreenEvent>())
        val observers = Collections.synchronizedList(mutableListOf<SearchSessionObserverV3>())
        val firstTerminal = CountDownLatch(1)
        val secondTerminal = CountDownLatch(2)
        val firstNetworkCheck = CountDownLatch(1)
        val continueFirst = CountDownLatch(1)
        val secondNetworkCheck = CountDownLatch(1)
        val continueSecond = CountDownLatch(1)
        val workerStarts = AtomicInteger()
        private val attempts = AtomicInteger()
        @Volatile var blockFirst = false
        @Volatile var blockSecond = false
        @Volatile var activeGeneration: Long? = null
        val admission = SearchOwnerAdmission()
        val clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
        val marker = Marker(trace)
        val revisions = Revisions()
        val source = FakeSubscriptionPort { dispatch -> startSubscriptionIfSafe(composition.controller, dispatch) }
        private val files = SnapshotFiles(this)
        val storage = SubscriptionStorage(File(root, "subscriptions"), files) { composition.currentRevision() }
        private val producer = SearchSessionProducer(5_000, { snapshot, _, _ ->
            snapshot.profiles.open().use { it.readBytes() }
            val attempt = attempts.incrementAndGet()
            var callback: ((DefaultNetworkEvent) -> Unit)? = null
            val opened = NetworkEpochOwner.open(
                register = { registered ->
                    callback = registered
                    AutoCloseable { trace.add("unregister"); if (failure == Failure.UNREGISTER) throw IOException("synthetic") }
                },
                snapshot = {
                    if (attempt == 1 && blockFirst) { firstNetworkCheck.countDown(); check(continueFirst.await(5, TimeUnit.SECONDS)) }
                    if (attempt == 2 && blockSecond) { secondNetworkCheck.countDown(); check(continueSecond.await(5, TimeUnit.SECONDS)) }
                    callback!!(DefaultNetworkEvent.Available(11))
                    DefaultNetworkSnapshot(11, true)
                },
                leaseCurrent = { failure != Failure.LEASE },
                stopRoundAndDns = { error("no worker") },
            )
            requireSearchNetwork(opened)
            workerStarts.incrementAndGet()
            error("unexpected worker")
        }, clock, { generation, reason -> emitWorkerDiagnostic(activeGeneration, generation, networkPolicyEvent(reason), events::add) })
        private val port = object : SearchSessionPortV4 {
            override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
                observers.add(observer)
                val token = checkNotNull(admission.reserve())
                val lease = SearchOwnerLease(42, admission, token, {
                    trace.add("owner-close")
                    failure != Failure.OWNER_CLOSE
                }, clock)
                check(producer.offerLease(lease) == SearchLeaseHandoff.CLAIMED)
                activeGeneration = snapshot.sessionGeneration
                return producer.start(snapshot, phaseEpoch, observer)
            }
            override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
                if (failure == Failure.ACCEPT) producer.ownerLost(terminal.sessionGeneration)
                val accepted = producer.acceptTerminal(terminal)
                trace.add("accept-$accepted")
                if (accepted) activeGeneration = null
                return accepted
            }
            override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)
            override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)
            override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)
        }
        val composition: ResultActivationComposition = ResultActivationComposition::class.java.getDeclaredConstructor(
            ResultStorage::class.java, RevisionStorage::class.java, SearchSessionPortV3::class.java,
            SessionMarker::class.java, VpnRunRegistry::class.java, SearchCompletionReceiptStorage::class.java,
        ).also { it.isAccessible = true }.newInstance(Results(), revisions, port, marker, VpnRunRegistry(), MemorySearchCompletionReceiptStorage())
        val subscriptions = SubscriptionController(source, composition,
            commitSource = { storage.commitSource(it, 1) },
            commitSelection = storage::commitSelection,
            selectionMutation = { ids -> composition.withOwnedSourceMutation(::acquireSelectionOwner) { storage.commitSelection(ids) } },
        )

        init {
            composition.controller.setPhaseListener { state ->
                if (state.phase in setOf(SearchPhase.FAILED, SearchPhase.INTERRUPTED, SearchPhase.CANCELLED)) {
                    firstTerminal.countDown()
                    secondTerminal.countDown()
                }
            }
        }

        fun installSelection(): Unit {
            assertEquals(SubscriptionStartV1.ACCEPTED, subscriptions.load("synthetic"))
            source.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot(this))
            assertTrue(subscriptions.select(setOf(ID_A)))
            trace.clear()
        }

        private fun acquireSelectionOwner(): AutoCloseable? {
            val token = admission.reserve() ?: return null
            return AutoCloseable { check(admission.release(token)) }
        }

        fun assertOrder(vararg wanted: String): Unit {
            val values = trace.toList()
            var previous = -1
            for (event in wanted) {
                previous = (previous + 1 until values.size).firstOrNull { values[it] == event } ?: -1
                assertTrue("$event out of order: $values", previous >= 0)
            }
        }
    }

    private class Snapshot(private val harness: Harness) : SubscriptionSnapshotV1 {
        override val records = listOf(
            SubscriptionRecordV1(ID_A, "A", "", true, null),
            SubscriptionRecordV1(ID_B, "B", "", true, null),
        )
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1 = object : PrivateInputV1 {
            override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1))
            override fun close(): Unit {
                harness.trace.add("profiles-close")
                if (harness.failure == Failure.PROFILES_CLOSE) throw IOException("synthetic")
            }
        }
        override fun close(): Unit = Unit
    }

    private class SnapshotFiles(private val harness: Harness) : SubscriptionSnapshotFiles {
        override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit = file.writeBytes(byteArrayOf(1))
        override fun read(file: File): SubscriptionSnapshotV1 = Snapshot(harness)
        override fun syncDirectory(directory: File): Unit = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private class FakeSubscriptionPort(private val admit: (() -> SubscriptionStartV1) -> SubscriptionStartV1) : SubscriptionPortV1 {
        var observer: SubscriptionObserverV1? = null
        var dispatched = 0
        override fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1 = admit {
            dispatched++
            this.observer = observer
            SubscriptionStartV1.ACCEPTED
        }
        override fun cancel(generation: Long): Unit = Unit
    }

    private class Marker(private val trace: MutableList<String>) : SessionMarker {
        @Volatile private var value = MarkerState.CLEAR
        @Volatile var failReadback = false
        override fun read(): MarkerState = value.also { trace.add("marker-read-$it") }
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean {
            trace.add("marker-clear")
            value = if (failReadback) MarkerState.UNKNOWN else MarkerState.CLEAR
            return true
        }
    }

    private class Revisions : RevisionStorage {
        var value: ResultRevision? = null
        override fun read(): ResultRevision? = value
        override fun write(revision: ResultRevision): Unit { value = revision }
    }

    private class Results : ResultStorage {
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String = error("unexpected result")
        override fun readGeneration(generation: String): StoredResult? = null
        override fun readManifest(): ResultManifest? = null
        override fun publish(manifest: ResultManifest): Unit = error("unexpected result")
        override fun discard(generation: String): Unit = Unit
    }

    companion object {
        private val ID_A = "a".repeat(32)
        private val ID_B = "b".repeat(32)
    }
}
