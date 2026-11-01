package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.*
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.searchEvent
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreconnectProducerJointTest {
    @Test fun preconnectTimeoutWaitsForExactCleanupAndGoReturnBeforeRetry(): Unit {
        val test = Harness()
        assertEquals(SearchStartV3.ACCEPTED, test.controller.start(1, 2, Input(), true))
        try {
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertEquals(SearchPhase.INTERRUPTED, test.controller.state.value.phase)
            assertEquals(ScreenEvent.SEARCH_CLEANUP_PENDING, searchEvent(test.controller.state.value))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertFalse(test.controller.withSourceMutation { true })
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
            assertTrue(test.controller.withSourceMutation { true })
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.controller.start(3, 4, Input(), true))
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
        } finally { test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun failedUnbindKeepsTheSameJobBlocked(): Unit {
        val test = Harness()
        assertEquals(SearchStartV3.ACCEPTED, test.controller.start(1, 2, Input(), true))
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
        } finally { test.returnRun.countDown() }
    }

    private class Harness {
        val processAdmission = SearchOwnerAdmission()
        val marker = Marker()
        val anchorCloses = AtomicInteger()
        val pending = CountDownLatch(1)
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
                    val identity = WorkerIdentityV1(1, 1uL, 1, 1)
                    val admission = CoordinatorAdmission(owner, identity, SearchNetworkV1(2, 3), RoundOwner { true })
                    val prepared = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
                    check(prepared.beginBind())
                    val stop = object : PreconnectWorkerStop {
                        override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean =
                            SupervisedCoordinatorStop(admission, { unbind.set(it); true }, { closeDuplicate.set(it); true },
                                { prepared.close(); true }, unbound).begin(attempt)
                        override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)
                        override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)
                        override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)
                        override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = admission.ownsRetirement(proof, identity)
                    }
                    var entered = false
                    handlePreconnectOpenFailure(true, 10, 10, reserve = { true }, start = {
                        startPreconnectRecovery(1, owner, drain, stop, ::schedule,
                            { confirmed, retry, failed -> FakeAttempt(::schedule, confirmed, retry, failed).also(attempt::set) }, producer)
                            .also { entered = it }
                    }, fail = { error("recovery not started") })
                    check(entered)
                    pending.countDown()
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
            controller = SearchController(port, ResultStore(Storage()), marker)
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
}
