package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchFailureV1
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
    fun upgradeRevisionReplayKeepsOldReceiptAndResultUnavailable(): Unit {
        val f = Fixture()
        f.publishResult()
        val receipt = f.completionReceipts.read()
        val manifest = f.storage.readManifest()
        assertEquals(ResultRevision("1", "3"), f.composition.reissueForUpgrade { ResultRevision(it.source, "3") })
        assertNull(f.composition.loadCleanComplete(f.accepted))
        assertFalse(f.composition.activation.ready())
        assertEquals(receipt, f.completionReceipts.read())
        assertEquals(manifest, f.storage.readManifest())
        assertEquals(ResultRevision("1", "4"), f.recovered().reissueForUpgrade { ResultRevision(it.source, "4") })
        assertNull(f.recovered().loadCleanComplete(f.accepted))
        assertEquals(receipt, f.completionReceipts.read())
        assertEquals(manifest, f.storage.readManifest())
    }
    @Test
    fun cleanCompleteReceiptRestoresReadinessWithoutStartingSearchOrVpn(): Unit {
        val f = Fixture()
        f.publishResult()
        val startsBeforeRestore = f.port.starts
        assertEquals(f.searched, f.composition.loadCleanComplete(f.accepted))
        assertTrue(f.composition.activation.ready())
        assertTrue(f.registry.available())
        val recovered = f.recovered()
        assertEquals(f.searched, recovered.loadCleanComplete(f.accepted))
        assertTrue(recovered.activation.ready())
        assertTrue(recovered.vpnRuns.available())
        assertEquals(startsBeforeRestore, f.port.starts)
    }

    @Test
    fun staleGenerationRevisionOrManifestMismatchCannotRestoreReadiness(): Unit {
        val f = Fixture()
        f.publishResult()

        assertNull(f.composition.loadCleanComplete(f.accepted.copy(requestGeneration = 12L)))
        assertNull(f.composition.loadCleanComplete(f.accepted.copy(sourceRevision = "3")))

        val saved = checkNotNull(f.completionReceipts.receipt)
        f.completionReceipts.receipt = saved.copy(
            result = saved.result.copy(sha256 = "f".repeat(64)),
        )
        assertFalse(f.composition.activation.ready())
        assertNull(f.composition.loadCleanComplete(f.accepted))

        f.completionReceipts.receipt = saved.copy(
            result = saved.result.copy(generation = "00000000-0000-0000-0000-000000000099"),
        )
        assertFalse(f.composition.activation.ready())
        assertNull(f.composition.loadCleanComplete(f.accepted))

        f.completionReceipts.receipt = saved
        f.revisionStorage.value = ResultRevision("3", "2")
        assertFalse(f.composition.activation.ready())
        assertNull(f.composition.loadCleanComplete(f.accepted))
    }

    @Test
    fun lastGoodReceiptSurvivesNewerUncleanGenerationAtSameRevision(): Unit {
        val f = Fixture()
        f.publishResult()
        val lastGoodReceipt = f.searched
        val lastGoodManifest = checkNotNull(f.storage.readManifest())

        val prepared = f.composition.activation.reserveSearch(1, 2, Input()) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, f.port.offer(Input("unclean-new-result".toByteArray())))
        val newerManifest = checkNotNull(f.storage.readManifest())
        assertTrue(lastGoodManifest.generation != newerManifest.generation)

        f.port.stopped()

        assertEquals(lastGoodReceipt, f.composition.loadCleanComplete(f.accepted))
        assertTrue(f.composition.activation.ready())
        val admission = f.composition.activation.admitVpn {
            f.registry.reserve(stopping = {})
        } as VpnAdmission.Admitted
        assertArrayEquals(f.result, admission.bytes)
        assertTrue(admission.reservation.claimRelease())

        f.storage.corruptGeneration(lastGoodManifest.generation)
        assertNull(f.composition.loadCleanComplete(f.accepted))
        assertFalse(f.composition.activation.ready())
        f.storage.repairGeneration(lastGoodManifest.generation)

        assertTrue(f.composition.withSourceMutation { ResultRevision("3", "2") })
        assertNull(f.composition.loadCleanComplete(f.accepted))
        assertFalse(f.composition.activation.ready())
    }

    @Test
    fun missingReceiptAndInterruptedMarkerKeepPowerUnavailable(): Unit {
        val missing = Fixture()
        assertTrue(missing.composition.withSourceMutation { ResultRevision("1", "2") })
        assertNull(missing.composition.loadCleanComplete(missing.accepted))
        assertFalse(missing.composition.activation.ready())
        assertUnavailable(missing.composition)

        val interrupted = Fixture()
        interrupted.publishResult()
        interrupted.marker.state = MarkerState.ACTIVE
        assertNull(interrupted.composition.loadCleanComplete(interrupted.accepted))
        assertFalse(interrupted.composition.activation.ready())
        assertUnavailable(interrupted.composition)
    }

    @Test
    fun failedReceiptWritePreservesPreviousCleanReceipt(): Unit {
        val f = Fixture()
        f.publishResult()
        val previous = checkNotNull(f.completionReceipts.receipt)
        f.completionReceipts.failWrite = true

        val attempted = f.searched.copy(sessionGeneration = 22L, leaseIdentity = "00000000-0000-0000-0000-000000000022")
        assertFalse(f.composition.recordCleanComplete(f.accepted, attempted))
        assertEquals(previous, f.completionReceipts.receipt)
        assertEquals(f.searched, f.composition.loadCleanComplete(f.accepted))
    }

    @Test
    fun pendingReceiptBlocksPreviousAndCandidateUntilPostClearCommit(): Unit {
        val f = Fixture()
        f.publishResult()
        val previous = checkNotNull(f.completionReceipts.receipt)
        val profiles = Input()
        val prepared = f.composition.activation.reserveSearch(1, 2, profiles) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        val nextResult = "synthetic-second-result".toByteArray()
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, f.port.offer(Input(nextResult)))
        val nextSearch = f.searched

        var preparedBeforeClear = false
        f.port.onAcceptTerminal = { terminal ->
            if (terminal is SearchTerminalV3.Complete) {
                assertEquals(MarkerState.ACTIVE, f.marker.state)
                assertTrue(f.composition.prepareCleanComplete(f.accepted, nextSearch))
                preparedBeforeClear = true
                assertTrue(f.completionReceipts.hasPending())
                assertEquals(previous, f.completionReceipts.receipt)
                assertNull(f.composition.loadCleanComplete(f.accepted))
                assertFalse(f.composition.activation.ready())
            }
        }
        f.port.complete()
        assertTrue(preparedBeforeClear)
        assertEquals(MarkerState.CLEAR, f.marker.state)
        assertNull(f.composition.loadCleanComplete(f.accepted))
        assertFalse(f.composition.activation.ready())
        val restoredBeforeCommitPort = Port()
        val restoredBeforeCommit = compose(
            f.storage,
            f.revisionStorage,
            restoredBeforeCommitPort,
            f.marker,
            VpnRunRegistry(),
            f.completionReceipts,
        )
        assertNull(restoredBeforeCommit.loadCleanComplete(f.accepted))
        assertFalse(restoredBeforeCommit.activation.ready())
        assertEquals(0, restoredBeforeCommitPort.starts)
        assertTrue(restoredBeforeCommit.vpnRuns.available())

        assertTrue(f.composition.commitCleanComplete())
        assertFalse(f.completionReceipts.hasPending())
        assertEquals(nextSearch, f.composition.loadCleanComplete(f.accepted))
        assertTrue(f.composition.activation.ready())
        assertArrayEquals(nextResult, (f.composition.activation.admitVpn { f.registry.reserve(stopping = {}) }
            as VpnAdmission.Admitted).bytes)
        val restoredAfterCommitPort = Port()
        val restoredAfterCommit = compose(
            f.storage,
            f.revisionStorage,
            restoredAfterCommitPort,
            f.marker,
            VpnRunRegistry(),
            f.completionReceipts,
        )
        assertEquals(nextSearch, restoredAfterCommit.loadCleanComplete(f.accepted))
        assertTrue(restoredAfterCommit.activation.ready())
        assertEquals(0, restoredAfterCommitPort.starts)
        assertTrue(restoredAfterCommit.vpnRuns.available())
    }

    @Test
    fun pendingReceiptWithoutPriorAndFailedCommitStayUnavailableUntilRetry(): Unit {
        val f = Fixture()
        assertTrue(f.composition.withSourceMutation { ResultRevision("1", "2") })
        val prepared = f.composition.activation.reserveSearch(1, 2, Input()) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, prepared.dispatch())
        val result = "synthetic-first-result".toByteArray()
        assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, f.port.offer(Input(result)))
        val searched = f.searched

        var preparedBeforeClear = false
        f.port.onAcceptTerminal = { terminal ->
            if (terminal is SearchTerminalV3.Complete) {
                assertEquals(MarkerState.ACTIVE, f.marker.state)
                assertTrue(f.composition.prepareCleanComplete(f.accepted, searched))
                preparedBeforeClear = true
                assertTrue(f.completionReceipts.hasPending())
                assertNull(f.completionReceipts.receipt)
                assertNull(f.composition.loadCleanComplete(f.accepted))
                assertFalse(f.composition.activation.ready())
            }
        }
        f.port.complete()
        assertTrue(preparedBeforeClear)
        f.completionReceipts.failCommit = true
        assertFalse(f.composition.commitCleanComplete())
        assertTrue(f.completionReceipts.hasPending())
        assertNull(f.composition.loadCleanComplete(f.accepted))
        assertFalse(f.composition.activation.ready())
        val restoredPendingPort = Port()
        val restoredPending = compose(
            f.storage,
            f.revisionStorage,
            restoredPendingPort,
            f.marker,
            VpnRunRegistry(),
            f.completionReceipts,
        )
        assertNull(restoredPending.loadCleanComplete(f.accepted))
        assertFalse(restoredPending.activation.ready())
        assertEquals(0, restoredPendingPort.starts)
        assertTrue(restoredPending.vpnRuns.available())

        f.completionReceipts.failCommit = false
        assertTrue(f.composition.commitCleanComplete())
        assertEquals(searched, f.composition.loadCleanComplete(f.accepted))
        assertTrue(f.composition.activation.ready())
        assertEquals(1, f.port.starts)
        assertArrayEquals(result, (f.composition.activation.admitVpn { f.registry.reserve(stopping = {}) }
            as VpnAdmission.Admitted).bytes)
    }

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
        completionReceipts: SearchCompletionReceiptStorage,
    ): ResultActivationComposition {
        val constructor = ResultActivationComposition::class.java.getDeclaredConstructor(
            ResultStorage::class.java,
            RevisionStorage::class.java,
            SearchSessionPortV3::class.java,
            SessionMarker::class.java,
            VpnRunRegistry::class.java,
            SearchCompletionReceiptStorage::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(storage, revisions, port, marker, registry, completionReceipts)
    }

    private inner class Fixture {
        val storage = Storage()
        val revisionStorage = Revisions()
        val port = Port()
        val marker = Marker()
        val registry = VpnRunRegistry()
        val completionReceipts = MemorySearchCompletionReceiptStorage()
        val accepted = AcceptedSubscriptionReceiptV1(11L, "1", "2")
        val searched: SearchLeaseReceiptV1
            get() = SearchLeaseReceiptV1(accepted.requestGeneration, port.generation, 1L, 2L, port.leaseIdentity)
        val composition = compose(storage, revisionStorage, port, marker, registry, completionReceipts)
        val result = "verified-synthetic-result".toByteArray()

        fun recovered(): ResultActivationComposition =
            compose(storage, revisionStorage, Port(), marker, VpnRunRegistry(), completionReceipts)

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
            assertTrue(composition.recordCleanComplete(accepted, searched))
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
        private val results = mutableMapOf<String, Stored>()
        private val corruptedGenerations = mutableSetOf<String>()
        private var manifest: ResultManifest? = null
        private var nextGeneration = 0L

        var failManifestRead = false
        override fun safeForUpgrade(candidate: ResultRevision?): Boolean = !failManifestRead &&
            (candidate == null || manifest?.revision != candidate)
        override fun hasVerifiedManifestForUpgrade(): Boolean = !failManifestRead && manifest != null
        override fun verifiedGenerationForUpgrade(manifest: ResultManifest): Boolean {
            val result = readGeneration(manifest.generation) ?: return false
            return result.input.use { result.revision == manifest.revision && result.sha256 == manifest.sha256 && digest(it) == manifest.sha256 }
        }
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            val id = "00000000-0000-0000-0000-${(nextGeneration++).toString().padStart(12, '0')}"
            results[id] = Stored(revision, sha256, input.readBytes())
            return id
        }
        override fun readGeneration(generation: String): StoredResult? {
            val stored = results[generation] ?: return null
            val bytes = if (generation in corruptedGenerations) "tampered".toByteArray() else stored.bytes
            return StoredResult(stored.revision, stored.sha256, bytes.inputStream())
        }
        override fun readManifest(): ResultManifest? {
            if (failManifestRead) throw IOException("synthetic")
            return manifest
        }
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { results.remove(generation) }

        fun corruptGeneration(generation: String): Unit { corruptedGenerations.add(generation) }
        fun repairGeneration(generation: String): Unit { corruptedGenerations.remove(generation) }

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
        var onAcceptTerminal: (SearchTerminalV3) -> Unit = {}
        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
            onAcceptTerminal(terminal)
            return true
        }
        private var observer: SearchSessionObserverV3? = null
        var generation = 0L
        val leaseIdentity = "00000000-0000-0000-0000-000000000021"
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
        fun stopped(): Unit = observer!!.onTerminal(
            SearchTerminalV3.Stopped(generation, phase, SearchFailureV1.INTERNAL_ERROR, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
        fun cancelled(): Unit = observer!!.onTerminal(
            SearchTerminalV3.Cancelled(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
    }
}

private const val TIMEOUT_SECONDS = 10L
