package com.seraf.vpnsweep.searchworker

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.resultactivation.MemorySearchCompletionReceiptStorage
import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.resultactivation.SearchCompletionReceiptStorage
import com.seraf.vpnsweep.startSubscriptionIfSafe
import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.searchstorage.*
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.searchEvent
import com.seraf.vpnsweep.subscriptionprotocol.*
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionSnapshotFiles
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage
import com.seraf.vpnsweep.ui.updateVisibleSelection
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreconnectProducerJointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preconnectTimeoutWaitsForExactCleanupAndGoReturnBeforeRetry(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertEquals(ScreenEvent.SEARCH_CLEANUP_PENDING, searchEvent(test.controller.state.value))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertFalse(test.controller.withSourceMutation { true })
            assertTrue(test.portCancelled.await(3, TimeUnit.SECONDS))
            test.releasePortWait.countDown()
            test.controller.cancel()
            assertEquals(SearchPhase.CANCELLING, test.controller.state.value.phase)
            test.unbind.get()!!.invoke(true)
            test.pump()
            test.attempt.get()!!.die()
            test.pump()
            test.closeDuplicate.get()!!.invoke()
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            assertEquals(1, test.anchorCloses.get())
            assertEquals(SearchPhase.CANCELLED, test.controller.state.value.phase)
            val before = test.composition.currentRevision()
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            val after = checkNotNull(test.composition.currentRevision())
            assertNotEquals(before, after)
            val restored = checkNotNull(test.storage.recover(after))
            assertEquals(setOf(ID_B), restored.selectedIds)
            restored.snapshot.close()
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
        } finally { test.releasePortWait.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun failedUnbindKeepsTheSameJobBlocked(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.unbind.get()!!.invoke(false)
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertEquals(ScreenEvent.SEARCH_INTERRUPTED, searchEvent(test.controller.state.value))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
            assertFalse(test.controller.withSourceMutation { true })
            assertFalse(test.subscriptions.select(setOf(ID_B)))
            assertEquals(setOf(ID_A), test.subscriptions.state.value.selectedIds)
            assertEquals(setOf(ID_A, ID_B), updateVisibleSelection(test.subscriptions.state.value.selectedIds, setOf(ID_B), true))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.releasePortWait.countDown(); test.returnRun.countDown() }
    }

    private class Harness(root: File) {
        val processAdmission = SearchOwnerAdmission()
        val marker = Marker()
        val anchorCloses = AtomicInteger()
        val pending = CountDownLatch(1)
        val portWaiting = CountDownLatch(1)
        val portCancelled = CountDownLatch(1)
        val releasePortWait = CountDownLatch(1)
        val openClock = AtomicLong(1_000)
        val returnRun = CountDownLatch(1)
        val firstTerminal = CountDownLatch(1)
        val secondTerminal = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val staleObserver = AtomicReference<SearchSessionObserverV3>()
        @Volatile var blockSecond = false
        val drain = SearchWorkerLocalDrain(1)
        val operation = drain.begin(1)!!
        val unbind = AtomicReference<((Boolean) -> Unit)?>()
        val closeDuplicate = AtomicReference<(() -> Unit)?>()
        val attempt = AtomicReference<FakeAttempt>()
        private val actions = ArrayDeque<() -> Unit>()
        private val clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
        lateinit var controller: SearchController
        lateinit var producer: SearchSessionProducer
        lateinit var composition: ResultActivationComposition
        lateinit var subscriptions: SubscriptionController
        private val source = FakeSubscriptionPort { dispatch -> startSubscriptionIfSafe(controller, dispatch) }
        val storage = SubscriptionStorage(File(root, "subscriptions"), SnapshotFiles()) { composition.currentRevision() }
        private val revisions = FileRevisions(root)

        init {
            producer = SearchSessionProducer(5_000, { snapshot, owner, deliver ->
                if (snapshot.sessionGeneration != 1L) {
                    if (blockSecond) { secondEntered.countDown(); check(releaseSecond.await(3, TimeUnit.SECONDS)) }
                    throw SearchNetworkPolicyUnavailable()
                }
                snapshot.profiles.open().use { it.readBytes() }
                val progress = SearchMobilePhaseProgress(clock, deliver)
                SearchMobilePhaseSession({}, {
                    check(owner.reserveWorker())
                    val identity = WorkerIdentityV1(1, 2uL, 1, 3)
                    val admission = CoordinatorAdmission(owner, identity, SearchNetworkV1(2, 3), RoundOwner { true })
                    val prepared = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
                    check(prepared.beginBind())
                    val stop = object : WorkerCoordinatorPort {
                        override fun bind(): Boolean = true
                        override fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait {
                            portWaiting.countDown()
                            check(releasePortWait.await(3, TimeUnit.SECONDS))
                            return WorkerConfirmationWait.SupervisedStop
                        }
                        override fun confirmedBinder(): IBinder? = null
                        override fun awaitRetired(deadlineElapsedMillis: Long): Boolean = false
                        override fun quarantine(): Unit = admission.disconnected()
                        override fun stop(): Unit = admission.stop()
                        override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean =
                            SupervisedCoordinatorStop(admission, { unbind.set(it); true }, { closeDuplicate.set(it); true },
                                { prepared.close(); true }, unbound).begin(attempt)
                        override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)
                        override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)
                        override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)
                        override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = admission.ownsRetirement(proof, identity)
                    }
                    val network = SearchNetworkV1(2, 3)
                    val actual = SearchWorkerBinderPort(stop, owner, prepared, identity, network, drain,
                        onPreconnectTimeout = {
                            startPreconnectRecovery(1, owner, drain, stop, ::schedule,
                                { confirmed, retry, failed -> FakeAttempt(::schedule, confirmed, retry, failed).also(attempt::set) }, producer)
                                .also { if (it) pending.countDown() }
                        }, provenFrameBound = 1024, provenReplyBound = 1024,
                        stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                            override fun descriptor(): ParcelFileDescriptor = error("no Binder transaction expected")
                            override fun close(): Unit = Unit
                        } }, nowMillis = openClock::get,
                        awaitBounded = { _, _ -> check(portWaiting.await(3, TimeUnit.SECONDS)); openClock.set(2_000); false })
                    val forwarding = object : DeadlineWorkerPort {
                        override fun openUntil(identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
                            configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 =
                            actual.openUntil(identity, network, lease, configuration, startupDeadlineElapsedMillis, cleanupMillis)
                        override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
                            actual.cancelUntil(identity, cleanupDeadlineElapsedMillis)
                            portCancelled.countDown()
                        }
                        override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 =
                            actual.closeUntil(identity, cleanupDeadlineElapsedMillis)
                        override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 =
                            actual.execute(identity, operation, input)
                    }
                    val adapter = SearchEngineWorkerAdapter(1, network, 5_000, 1024, { WorkerRoundClient(forwarding, prepared) }, {},
                        nowNanos = { openClock.get() * 1_000_000 }, localDrain = drain)
                    adapter.open(1, 2, 1, 3, byteArrayOf(1), byteArrayOf(2), 1_000, 1_000)
                    check(returnRun.await(3, TimeUnit.SECONDS))
                    throw IOException("synthetic_preconnect_timeout")
                }, { true }, { error("no result") }, {}, progress, { SearchResourceStateV2.PAUSED_LEASE_HELD }, {}, owner)
            }, clock, reportRecoveryPending = { controller.recoveryPending(it) })
            val port = object : SearchSessionPortV4 {
                override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
                    if (snapshot.sessionGeneration == 1L) staleObserver.set(observer)
                    val token = checkNotNull(processAdmission.reserve())
                    val lease = SearchOwnerLease(42, processAdmission, token, { anchorCloses.incrementAndGet(); true }, clock)
                    check(producer.offerLease(lease) == SearchLeaseHandoff.CLAIMED)
                    val downstream = object : SearchSessionObserverV3 {
                        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = observer.onProgress(phaseEpoch, progress)
                        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = observer.onResult(offer)
                        override fun onTerminal(terminal: SearchTerminalV3): Unit {
                            observer.onTerminal(terminal)
                            if (terminal.sessionGeneration == 1L) firstTerminal.countDown() else secondTerminal.countDown()
                        }
                    }
                    return producer.start(snapshot, phaseEpoch, downstream)
                }
                override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)
                override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)
                override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)
                override fun acceptTerminal(terminal: SearchTerminalV3): Boolean = producer.acceptTerminal(terminal)
            }
            composition = ResultActivationComposition::class.java.getDeclaredConstructor(
                ResultStorage::class.java, RevisionStorage::class.java, SearchSessionPortV3::class.java,
                SessionMarker::class.java, VpnRunRegistry::class.java, SearchCompletionReceiptStorage::class.java,
            ).also { it.isAccessible = true }.newInstance(Storage(), revisions, port, marker, VpnRunRegistry(), MemorySearchCompletionReceiptStorage())
            controller = composition.controller
            subscriptions = SubscriptionController(source, composition,
                commitSource = { storage.commitSource(it, 1) }, commitSelection = storage::commitSelection,
                selectionMutation = { ids -> composition.withOwnedSourceMutation(::selectionOwner) { storage.commitSelection(ids) } })
        }

        fun installSelection(): Unit {
            assertEquals(SubscriptionStartV1.ACCEPTED, subscriptions.load("synthetic"))
            source.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot())
            assertTrue(subscriptions.select(setOf(ID_A)))
        }

        private fun selectionOwner(): AutoCloseable? {
            val token = processAdmission.reserve() ?: return null
            return AutoCloseable { check(processAdmission.release(token)) }
        }

        @Synchronized private fun schedule(delay: Long, action: () -> Unit): Boolean {
            if (delay == 0L) actions.add(action)
            return true
        }
        fun pump(): Unit { while (true) { val action = synchronized(this) { if (actions.isEmpty()) null else actions.removeFirst() } ?: return; action() } }
    }

    private class FakeAttempt(
        schedule: (Long, () -> Unit) -> Boolean,
        confirmed: (SupervisorDeathProof) -> Unit,
        retry: () -> Unit,
        failed: () -> Unit,
    ) : SupervisorAttemptPort {
        private var death: (() -> Unit)? = null
        private val binding = SupervisorBindingAttempt({ schedule(0, it) }, { _, callback -> death = callback; true }, { true }, confirmed, retry, failed)
        override val identity: Any get() = binding.identity
        override fun start(): SupervisorStartResult { binding.bindReturned(true); binding.connected(Any()); return SupervisorStartResult.STARTED }
        override fun connectionTimedOut(): Unit = binding.connectionTimedOut()
        fun die(): Unit = checkNotNull(death).invoke()
    }

    private class Input : PrivateInputV1 {
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override fun close(): Unit = Unit
    }
    private class Snapshot : SubscriptionSnapshotV1 {
        override val records = listOf(
            SubscriptionRecordV1(ID_A, "A", "", true, null),
            SubscriptionRecordV1(ID_B, "B", "", true, null),
        )
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1 = Input()
        override fun close(): Unit = Unit
    }
    private class SnapshotFiles : SubscriptionSnapshotFiles {
        override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit = file.writeBytes(byteArrayOf(1))
        override fun read(file: File): SubscriptionSnapshotV1 = Snapshot()
        override fun syncDirectory(directory: File): Unit = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private class FakeSubscriptionPort(private val admit: (() -> SubscriptionStartV1) -> SubscriptionStartV1) : SubscriptionPortV1 {
        var observer: SubscriptionObserverV1? = null
        override fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1 = admit {
            this.observer = observer
            SubscriptionStartV1.ACCEPTED
        }
        override fun cancel(generation: Long): Unit = Unit
    }
    private class FileRevisions(private val root: File) : RevisionStorage {
        private val current = File(root, "revision")
        override fun read(): ResultRevision? = if (!current.isFile) null else DataInputStream(FileInputStream(current)).use {
            ResultRevision(it.readUTF(), it.readUTF())
        }
        override fun write(revision: ResultRevision): Unit {
            val temporary = File.createTempFile("revision-", ".tmp", root)
            FileOutputStream(temporary).use { raw ->
                val data = DataOutputStream(raw)
                data.writeUTF(revision.source)
                data.writeUTF(revision.selection)
                data.flush()
                raw.fd.sync()
            }
            Files.move(temporary.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
    private class Marker : SessionMarker {
        @Volatile private var state = MarkerState.CLEAR
        override fun read(): MarkerState = state
        override fun markActive(): Boolean { state = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { state = MarkerState.CLEAR; return true }
    }
    private class Storage : ResultStorage {
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String = error("no result")
        override fun readGeneration(generation: String): StoredResult? = null
        override fun readManifest(): ResultManifest? = null
        override fun publish(manifest: ResultManifest): Unit = error("no result")
        override fun discard(generation: String): Unit = Unit
    }
    companion object {
        private val ID_A = "a".repeat(32)
        private val ID_B = "b".repeat(32)
    }
}
