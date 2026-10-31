package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstorage.*
import com.seraf.vpnsweep.subscriptionprotocol.*
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SubscriptionControllerTest {
    @Test
    fun cancelClosesLateSnapshotAndDoesNotExposeSensitiveInput(): Unit {
        val port = FakeSubscriptionPort()
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        assertEquals(SubscriptionStartV1.ACCEPTED, controller.load(syntheticUrl("synthetic.invalid/secret-token")))
        controller.cancel()
        val late = Snapshot(listOf(record("a", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, late)
        assertTrue(late.closed)
        assertEquals(SubscriptionPhase.CANCELLED, controller.state.value.phase)
        assertFalse(controller.state.value.toString().contains("secret-token"))
        assertEquals(listOf(1L), port.cancellations)
    }

    @Test
    fun invalidAndNoSupportedAreNotSuccess(): Unit {
        val port = FakeSubscriptionPort()
        var commits = 0
        val controller = legacyController(port, search(), { commits++; true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        port.observer!!.onFailure(1, SubscriptionFailureV1.INVALID_URL)
        assertEquals(SubscriptionFailureV1.INVALID_URL, controller.state.value.failure)
        controller.load("synthetic")
        val unsupported = Snapshot(listOf(record("a", false)))
        port.observer!!.onParsed(2, SubscriptionBatchStatusV1.NO_SUPPORTED, unsupported)
        assertEquals(SubscriptionPhase.NO_SUPPORTED, controller.state.value.phase)
        assertTrue(unsupported.closed)
        assertFalse(controller.select(setOf("a")))
        assertEquals(0, commits)
    }

    @Test
    fun unsupportedSelectionRejectedAndSearchBlocksChanges(): Unit {
        val port = FakeSubscriptionPort()
        val session = search()
        var sourceCommits = 0
        var selectionCommits = 0
        val controller = legacyController(port, session, { sourceCommits++; true }, { selectionCommits++; true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val first = Snapshot(listOf(record("a", true), record("b", false)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, first)
        assertEquals(SubscriptionPhase.READY, controller.state.value.phase)
        assertFalse(controller.select(setOf("b")))
        assertTrue(controller.select(setOf("a")))
        assertEquals(1, selectionCommits)
        assertEquals(SearchStartV3.ACCEPTED, controller.startSelected())
        assertFalse(controller.select(setOf("a")))
        controller.load("synthetic")
        assertEquals(setOf("a"), controller.state.value.selectedIds)
        val next = Snapshot(listOf(record("new", true)))
        port.observer!!.onParsed(2, SubscriptionBatchStatusV1.HAS_SUPPORTED, next)
        assertTrue(next.closed)
        assertEquals(setOf("a"), controller.state.value.selectedIds)
        assertFalse(first.closed)
        assertEquals(1, sourceCommits)
        assertEquals(1, selectionCommits)
    }

    @Test
    fun previousSelectionRemainsUsableAfterCancelledFailedOrUnsupportedRefresh(): Unit {
        for (outcome in listOf("cancel", "failure", "unsupported")) {
            val port = FakeSubscriptionPort()
            val session = search()
            val controller = legacyController(port, session, { true }, { true }, { ResultRevision("1", "2") })
            controller.load("synthetic")
            val previous = Snapshot(listOf(record("previous", true)))
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, previous)
            assertTrue(controller.select(setOf("previous")))
            controller.load("synthetic")
            when (outcome) {
                "cancel" -> controller.cancel()
                "failure" -> port.observer!!.onFailure(2, SubscriptionFailureV1.TIMEOUT)
                else -> port.observer!!.onParsed(2, SubscriptionBatchStatusV1.NO_SUPPORTED, Snapshot(listOf(record("unsupported", false))))
            }
            assertEquals(setOf("previous"), controller.state.value.selectedIds)
            assertFalse(previous.closed)
            assertEquals(SearchStartV3.ACCEPTED, controller.startSelected())
        }
    }

    @Test
    fun staleRefreshAfterCancelOrFailureDoesNotReplaceSelection(): Unit {
        for (cancelled in listOf(true, false)) {
            val port = FakeSubscriptionPort()
            var sourceCommits = 0
            val controller = legacyController(port, search(), { sourceCommits++; true }, { true }, { ResultRevision("1", "2") })
            controller.load("synthetic")
            val previous = Snapshot(listOf(record("previous", true)))
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, previous)
            assertTrue(controller.select(setOf("previous")))
            controller.load("synthetic")
            if (cancelled) controller.cancel() else port.observer!!.onFailure(2, SubscriptionFailureV1.TIMEOUT)
            controller.load("synthetic")
            val stale = Snapshot(listOf(record("stale", true)))
            port.observer!!.onParsed(2, SubscriptionBatchStatusV1.HAS_SUPPORTED, stale)
            assertTrue(stale.closed)
            assertFalse(previous.closed)
            assertEquals(1, sourceCommits)
            assertEquals(setOf("previous"), controller.state.value.selectedIds)
            assertEquals(SubscriptionPhase.LOADING, controller.state.value.phase)
            assertTrue(controller.state.value.requestActive)
        }
    }

    @Test
    fun selectingDuringRefreshKeepsLoadingAndCanCancel(): Unit {
        val port = FakeSubscriptionPort()
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot(listOf(record("a", true))))
        assertEquals(SubscriptionStartV1.ACCEPTED, controller.load("synthetic"))
        assertTrue(controller.select(setOf("a")))
        assertEquals(setOf("a"), controller.state.value.selectedIds)
        assertEquals(SubscriptionPhase.LOADING, controller.state.value.phase)
        assertTrue(controller.state.value.requestActive)
        controller.cancel()
        assertEquals(SubscriptionPhase.CANCELLED, controller.state.value.phase)
        assertFalse(controller.state.value.requestActive)
        assertEquals(listOf(2L), port.cancellations)
    }

    @Test
    fun cleanupDuringLoadingRestoresLoadingAndCanCancel(): Unit {
        for (hasInstalled in listOf(false, true)) {
            val port = FakeSubscriptionPort()
            val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
            var oldGeneration = 1L
            if (hasInstalled) {
                controller.load("synthetic")
                port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot(listOf(record("a", true))))
                assertTrue(controller.select(setOf("a")))
                oldGeneration++
            }
            controller.load("synthetic")
            controller.cancel()
            val currentGeneration = oldGeneration + 1
            assertEquals(SubscriptionStartV1.ACCEPTED, controller.load("synthetic"))
            val late = Snapshot(listOf(record("late", true)))
            late.closeFailures = 1
            port.observer!!.onParsed(oldGeneration, SubscriptionBatchStatusV1.HAS_SUPPORTED, late)
            assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
            assertTrue(controller.state.value.requestActive)
            assertTrue(controller.retryCleanup())
            assertTrue(late.closed)
            assertEquals(SubscriptionPhase.LOADING, controller.state.value.phase)
            assertTrue(controller.state.value.requestActive)
            assertEquals(if (hasInstalled) setOf("a") else emptySet<String>(), controller.state.value.selectedIds)
            controller.cancel()
            assertEquals(listOf(oldGeneration, currentGeneration), port.cancellations)
            assertFalse(controller.state.value.requestActive)
        }
    }

    @Test
    fun cleanupFailureKeepsFailureVisibleWhenActiveRequestEnds(): Unit {
        for (outcome in listOf("cancel", "failure", "parsed", "parsed-close-failure")) {
            val port = FakeSubscriptionPort()
            val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
            controller.load("synthetic")
            controller.cancel()
            controller.load("synthetic")
            val late = Snapshot(listOf(record("late", true)))
            late.closeFailures = 1
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, late)
            assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
            assertEquals(SubscriptionFailureV1.INTERNAL, controller.state.value.failure)
            assertTrue(controller.state.value.requestActive)
            when (outcome) {
                "cancel" -> {
                    controller.cancel()
                    assertEquals(listOf(1L, 2L), port.cancellations)
                }
                "failure" -> port.observer!!.onFailure(2, SubscriptionFailureV1.TIMEOUT)
                else -> {
                    val current = Snapshot(listOf(record("current", true)))
                    if (outcome == "parsed-close-failure") current.closeFailures = 1
                    port.observer!!.onParsed(2, SubscriptionBatchStatusV1.HAS_SUPPORTED, current)
                    assertEquals(outcome == "parsed", current.closed)
                }
            }
            assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
            assertEquals(SubscriptionFailureV1.INTERNAL, controller.state.value.failure)
            assertFalse(controller.state.value.requestActive)
            assertTrue(controller.retryCleanup())
            assertTrue(late.closed)
            assertEquals(SubscriptionPhase.IDLE, controller.state.value.phase)
            assertFalse(controller.state.value.requestActive)
        }
    }

    @Test
    fun activeRequestRemainsCancellableWhenCleanupFailureIsPresented(): Unit {
        val port = FakeSubscriptionPort()
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        controller.cancel()
        controller.load("synthetic")
        val late = Snapshot(listOf(record("late", true)))
        late.closeFailures = 1
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, late)

        assertTrue(controller.state.value.requestActive)
        assertEquals(SubscriptionFailureV1.INTERNAL, controller.state.value.failure)

        controller.cancel()
        assertEquals(listOf(1L, 2L), port.cancellations)
        assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.state.value.requestActive)
    }

    @Test
    fun synchronousFailuresCarryTheAcceptedRequestGeneration(): Unit {
        val port = FakeSubscriptionPort()
        port.onStart = { generation, observer -> observer.onFailure(generation, SubscriptionFailureV1.INVALID_URL) }
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        val first = controller.loadWithReceipt(syntheticUrl("first.synthetic.invalid/sub"))
        val oldState = controller.state.value
        val second = controller.loadWithReceipt(syntheticUrl("second.synthetic.invalid/sub"))
        val currentState = controller.state.value

        assertEquals(SubscriptionStartV1.ACCEPTED, first.status)
        assertEquals(1L, first.generation)
        assertEquals(1L, oldState.generation)
        assertEquals(SubscriptionStartV1.ACCEPTED, second.status)
        assertEquals(2L, second.generation)
        assertEquals(2L, currentState.generation)
        assertEquals(SubscriptionFailureV1.INVALID_URL, oldState.failure)
        assertEquals(SubscriptionFailureV1.INVALID_URL, currentState.failure)
    }

    @Test
    fun selectionCommitAndSearchReadRevisionsUnderSameGuard(): Unit {
        val port = FakeSubscriptionPort()
        val runtime = FakeSearchPort()
        val session = search(runtime)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var revision = ResultRevision("1", "2")
        val controller = legacyController(port, session, { true }, {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            revision = ResultRevision("1", "3")
            true
        }, { revision })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("new", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        val selecting = thread { assertTrue(controller.select(setOf("new"))) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        var start: SearchStartV3? = null
        val starting = thread { start = controller.startSelected() }
        release.countDown()
        selecting.join(10_000)
        starting.join(10_000)
        assertFalse(selecting.isAlive)
        assertFalse(starting.isAlive)
        assertEquals(SearchStartV3.ACCEPTED, start)
        assertEquals(setOf("new"), snapshot.requestedIds)
        assertEquals(3L, runtime.snapshot?.selectionRevision)
    }

    @Test
    fun failedSnapshotCloseRetainsOwnershipAndBlocksUntilRetry(): Unit {
        val port = FakeSubscriptionPort()
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        controller.cancel()
        val late = Snapshot(listOf(record("late", true)))
        late.closeFailures = 1
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, late)
        assertFalse(late.closed)
        assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
        assertTrue(controller.retryCleanup())
        assertTrue(late.closed)
        assertEquals(SubscriptionStartV1.ACCEPTED, controller.load("synthetic"))
    }

    @Test
    fun replacementCloseFailureBlocksAndRetainsPreviousSnapshot(): Unit {
        val port = FakeSubscriptionPort()
        val controller = legacyController(port, search(), { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val previous = Snapshot(listOf(record("previous", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, previous)
        previous.closeFailures = 1
        controller.load("synthetic")
        port.observer!!.onParsed(2, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot(listOf(record("next", true))))
        assertFalse(previous.closed)
        assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
        assertTrue(controller.retryCleanup())
        assertTrue(previous.closed)
    }

    @Test
    fun rejectedSearchStreamCloseFailureRetainsOwnership(): Unit {
        val port = FakeSubscriptionPort()
        val runtime = FakeSearchPort()
        runtime.result = SearchStartV3.REJECTED
        val session = search(runtime)
        val controller = legacyController(port, session, { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("a", true)))
        snapshot.selectedInput.closeFailures = 1
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertTrue(controller.select(setOf("a")))
        assertEquals(SearchStartV3.REJECTED, controller.startSelected())
        assertFalse(snapshot.selectedInput.closed)
        assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
        assertTrue(controller.retryCleanup())
        assertTrue(snapshot.selectedInput.closed)
    }

    @Test
    fun uncertainSearchStartKeepsProfileOwnershipUnresolved(): Unit {
        for (throwOnStart in listOf(false, true)) {
            val port = FakeSubscriptionPort()
            val runtime = FakeSearchPort()
            runtime.result = SearchStartV3.UNKNOWN
            runtime.throwOnStart = throwOnStart
            val session = search(runtime)
            val controller = legacyController(port, session, { true }, { true }, { ResultRevision("1", "2") })
            controller.load("synthetic")
            val snapshot = Snapshot(listOf(record("a", true)))
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
            assertTrue(controller.select(setOf("a")))
            assertEquals(SearchStartV3.UNKNOWN, controller.startSelected())
            assertFalse(snapshot.selectedInput.closed)
            assertFalse(controller.retryCleanup())
            assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
            assertEquals(SearchPhase.INTERRUPTED, session.state.value.phase)
        }
    }

    @Test
    fun searchStartDoesNotHoldSubscriptionOrSearchLocksAcrossCallback(): Unit {
        val port = FakeSubscriptionPort()
        val runtime = FakeSearchPort()
        val session = search(runtime)
        val controller = legacyController(port, session, { true }, { true }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("a", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertTrue(controller.select(setOf("a")))
        runtime.onStart = { observer ->
            val callback = thread {
                observer.onProgress(1, SearchProgressV1(1, SearchProgressKindV1.ATTEMPT_STARTED, SearchProgressOutcomeV1.RUNNING, 0u, 0, 0, 0, 0, 0))
                assertFalse(controller.select(setOf("a")))
                session.cancel()
            }
            callback.join(10_000)
            check(!callback.isAlive)
        }
        assertEquals(SearchStartV3.ACCEPTED, controller.startSelected())
        assertEquals(listOf(1L to 1L), runtime.cancellations)
        assertEquals(SearchPhase.CANCELLING, session.state.value.phase)
    }

    @Test
    fun durableSourceAndSelectionUseGuardAndSharedVpnRegistry(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val snapshot = Snapshot(listOf(record("a", true)))
        var sourceWrites = 0
        var selectionWrites = 0
        val controller = SubscriptionController(port, fixture.composition, { borrowed ->
            assertSame(snapshot, borrowed)
            assertFalse(snapshot.closed)
            assertTrue(Thread.holdsLock(fixture.composition.controller))
            assertEquals(MarkerState.ACTIVE, fixture.marker.value)
            sourceWrites++
            ResultRevision("1", "1")
        }, { ids ->
            assertEquals(setOf("a"), ids)
            assertTrue(Thread.holdsLock(fixture.composition.controller))
            assertEquals(MarkerState.ACTIVE, fixture.marker.value)
            selectionWrites++
            ResultRevision("1", "2")
        })
        controller.load("synthetic")
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertEquals(ResultRevision("1", "1"), fixture.composition.currentRevision())
        assertTrue(controller.select(setOf("a")))
        assertEquals(ResultRevision("1", "2"), fixture.composition.currentRevision())
        val writesBeforeNoOp = fixture.marker.activeCalls
        assertTrue(controller.select(setOf("a")))
        assertEquals(writesBeforeNoOp, fixture.marker.activeCalls)
        assertEquals(1, sourceWrites)
        assertEquals(1, selectionWrites)

        val vpnRun = checkNotNull(fixture.registry.reserve(stopping = {}))
        assertEquals(SearchStartV3.BUSY, controller.startSelected())
        assertEquals(1, snapshot.selectedInput.closeAttempts)
        assertTrue(snapshot.selectedInput.closed)
        assertEquals(0, fixture.runtime.starts)
        assertTrue(vpnRun.claimRelease())
        snapshot.selectedInput = FakeInput()
        snapshot.onSelected = {
            assertTrue(Thread.holdsLock(controller))
            assertTrue(Thread.holdsLock(fixture.composition.controller))
        }
        fixture.runtime.onStart = {
            assertFalse(Thread.holdsLock(controller))
            assertFalse(Thread.holdsLock(fixture.composition.controller))
            fixture.runtime.snapshot!!.profiles.close()
        }
        assertEquals(SearchStartV3.ACCEPTED, controller.startSelected())
        assertEquals(1L, fixture.runtime.snapshot!!.sourceRevision)
        assertEquals(2L, fixture.runtime.snapshot!!.selectionRevision)
        assertEquals(1, snapshot.selectedInput.closeAttempts)
    }

    @Test
    fun failedBorrowedSourceCommitKeepsLastGoodAndClosesIncomingOnce(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val lastGood = ResultManifest("old", ResultRevision("0", "0"), "synthetic")
        fixture.storage.manifest = lastGood
        var partialWrite = false
        var fail = false
        val controller = SubscriptionController(port, fixture.composition, { borrowed ->
            assertFalse((borrowed as Snapshot).closed)
            assertEquals(MarkerState.ACTIVE, fixture.marker.value)
            if (fail) {
                partialWrite = true
                throw IOException("synthetic partial write")
            }
            ResultRevision("1", "1")
        }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val previous = Snapshot(listOf(record("previous", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, previous)
        assertTrue(controller.select(setOf("previous")))
        fail = true
        controller.load("synthetic")
        val incoming = Snapshot(listOf(record("incoming", true)))
        port.observer!!.onParsed(2, SubscriptionBatchStatusV1.HAS_SUPPORTED, incoming)
        assertTrue(partialWrite)
        assertEquals(MarkerState.ACTIVE, fixture.marker.value)
        assertEquals(lastGood, fixture.storage.manifest)
        assertEquals(ResultRevision("1", "2"), fixture.revisions.value)
        assertEquals(setOf("previous"), controller.state.value.selectedIds)
        assertFalse(previous.closed)
        assertTrue(incoming.closed)
        assertEquals(1, incoming.closeAttempts)
        assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
        assertFalse(controller.select(setOf("previous")))
        assertEquals(SearchStartV3.BUSY, controller.startSelected())
        assertTrue(previous.selectedInput.closed)
        assertEquals(0, fixture.runtime.starts)
    }

    @Test
    fun failedSelectionAndUnknownRevisionWriteKeepLastGoodAndBlockAdmission(): Unit {
        for (failure in listOf("callback", "write-before", "write-after", "reuse-current", "reuse-published")) {
            val fixture = Fixture()
            val port = FakeSubscriptionPort()
            val lastGood = ResultManifest("old", ResultRevision("0", "0"), "synthetic")
            fixture.storage.manifest = lastGood
            var selection = 0
            val controller = SubscriptionController(port, fixture.composition, { ResultRevision("1", "1") }, {
                selection++
                if (selection == 1) ResultRevision("1", "2") else when (failure) {
                    "callback" -> throw IOException("synthetic partial write")
                    "reuse-current" -> ResultRevision("1", "2")
                    "reuse-published" -> lastGood.revision
                    else -> ResultRevision("1", "3")
                }
            })
            controller.load("synthetic")
            val snapshot = Snapshot(listOf(record("a", true), record("b", true)))
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
            assertTrue(controller.select(setOf("a")))
            fixture.revisions.failBeforeWrite = failure == "write-before"
            fixture.revisions.failAfterWrite = failure == "write-after"
            assertFalse("failure=$failure", controller.select(setOf("b")))
            assertEquals("failure=$failure", MarkerState.ACTIVE, fixture.marker.value)
            assertEquals(lastGood, fixture.storage.manifest)
            assertEquals(setOf("a"), controller.state.value.selectedIds)
            assertFalse(snapshot.closed)
            assertEquals(if (failure == "write-after") ResultRevision("1", "3") else ResultRevision("1", "2"), fixture.revisions.value)
            if (failure == "write-before" || failure == "write-after") assertNull(fixture.composition.currentRevision())
            assertEquals(if (failure == "write-before" || failure == "write-after") SearchStartV3.REJECTED else SearchStartV3.BUSY, controller.startSelected())
            assertEquals(0, fixture.runtime.starts)
        }
    }

    @Test
    fun missingOrNoncanonicalRevisionRejectsBeforeCreatingInput(): Unit {
        for (revision in listOf(null, ResultRevision("01", "2"), ResultRevision("1", "x"), ResultRevision("9223372036854775808", "2"))) {
            val fixture = Fixture()
            val port = FakeSubscriptionPort()
            val controller = SubscriptionController(port, fixture.composition, { ResultRevision("1", "1") }, { ResultRevision("1", "2") })
            controller.load("synthetic")
            val snapshot = Snapshot(listOf(record("a", true)))
            port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
            assertTrue(controller.select(setOf("a")))
            fixture.revisions.value = revision
            assertEquals(SearchStartV3.REJECTED, controller.startSelected())
            assertNull(snapshot.requestedIds)
            assertEquals(0, fixture.runtime.starts)
            assertEquals(0, snapshot.selectedInput.closeAttempts)
        }
    }

    @Test
    fun unknownReservationRetainsInputEvenWithoutDispatch(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val lastGood = ResultManifest("old", ResultRevision("0", "0"), "synthetic")
        fixture.storage.manifest = lastGood
        val controller = SubscriptionController(port, fixture.composition, { ResultRevision("1", "1") }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("a", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertTrue(controller.select(setOf("a")))
        fixture.marker.throwAfterActive = true
        assertEquals(SearchStartV3.UNKNOWN, controller.startSelected())
        assertEquals(MarkerState.ACTIVE, fixture.marker.value)
        assertEquals(lastGood, fixture.storage.manifest)
        assertEquals(0, fixture.runtime.starts)
        assertEquals(0, snapshot.selectedInput.closeAttempts)
        assertFalse(snapshot.selectedInput.closed)
        assertFalse(controller.retryCleanup())
        assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
    }

    @Test
    fun oversizedSelectedProfilesShowLimitWithoutChangingLastGoodOrOwnership(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val lastGood = ResultManifest("old", ResultRevision("0", "0"), "synthetic")
        fixture.storage.manifest = lastGood
        val controller = SubscriptionController(port, fixture.composition, { ResultRevision("1", "1") }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("a", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertTrue(controller.select(setOf("a")))
        val markerWrites = fixture.marker.activeCalls
        snapshot.onSelected = { throw SubscriptionProfilesTooLargeException() }
        assertEquals(SearchStartV3.REJECTED, controller.startSelected())
        assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
        assertEquals(SubscriptionFailureV1.LIMIT_EXCEEDED, controller.state.value.failure)
        assertEquals(setOf("a"), controller.state.value.selectedIds)
        assertEquals(lastGood, fixture.storage.manifest)
        assertEquals(ResultRevision("1", "2"), fixture.revisions.value)
        assertEquals(markerWrites, fixture.marker.activeCalls)
        assertEquals(0, fixture.runtime.starts)
        assertFalse(snapshot.closed)
        assertEquals(0, snapshot.closeAttempts)
        assertEquals(0, snapshot.selectedInput.closeAttempts)

        snapshot.onSelected = { throw IllegalStateException("synthetic") }
        assertThrows(IllegalStateException::class.java) { controller.startSelected() }
    }

    @Test
    fun readingProfilesAndReservingShareOneGuardAndDispatchOutsideBothLocks(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val controller = SubscriptionController(port, fixture.composition, { ResultRevision("1", "1") }, { ResultRevision("1", "2") })
        controller.load("synthetic")
        val snapshot = Snapshot(listOf(record("a", true)))
        port.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, snapshot)
        assertTrue(controller.select(setOf("a")))
        val mutation = FutureTask(Callable { fixture.composition.withSourceMutation { ResultRevision("9", "9") } })
        var contender: Thread? = null
        snapshot.onSelected = {
            assertTrue(Thread.holdsLock(controller))
            assertTrue(Thread.holdsLock(fixture.composition.controller))
            contender = thread { mutation.run() }
            awaitBlocked(checkNotNull(contender), SearchController::class.java.name, "withSourceMutation")
        }
        fixture.runtime.onStart = {
            assertFalse(Thread.holdsLock(controller))
            assertFalse(Thread.holdsLock(fixture.composition.controller))
        }
        assertEquals(SearchStartV3.ACCEPTED, controller.startSelected())
        assertFalse(mutation.get(10, TimeUnit.SECONDS))
        assertEquals(ResultRevision("1", "2"), fixture.revisions.value)
        assertEquals(1L, fixture.runtime.snapshot!!.sourceRevision)
        assertEquals(2L, fixture.runtime.snapshot!!.selectionRevision)
    }

    private fun awaitBlocked(target: Thread, className: String, method: String): Unit {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (target.state == Thread.State.BLOCKED && target.stackTrace.any { it.className == className && it.methodName == method }) return
            Thread.yield()
        }
        error("thread did not block at $className.$method")
    }

    @Test
    fun restoreAcceptsMatchingRevisionWithoutChangingMarkerOrRevisions(): Unit {
        for (marker in listOf(MarkerState.CLEAR, MarkerState.UNKNOWN)) {
            for (withSelection in listOf(false, true)) {
                val fixture = Fixture(initialMarker = marker)
                val port = FakeSubscriptionPort()
                val revision = ResultRevision("1", "2")
                fixture.revisions.value = revision
                val controller = SubscriptionController(port, fixture.composition, { error("unexpected source commit") }, { error("unexpected selection commit") })
                val snapshot = Snapshot(listOf(record("a", true)))
                val ids = if (withSelection) mutableSetOf("a") else mutableSetOf()
                assertTrue(controller.restore(snapshot, ids, revision))
                ids.clear()
                assertEquals(if (withSelection) setOf("a") else emptySet<String>(), controller.state.value.selectedIds)
                assertEquals(SubscriptionPhase.READY, controller.state.value.phase)
                assertEquals(revision, fixture.revisions.value)
                assertEquals(0, fixture.revisions.writes)
                assertEquals(0, fixture.marker.activeCalls)
                assertEquals(marker, fixture.marker.value)
                assertFalse(snapshot.closed)
                if (!withSelection) {
                    assertEquals(SearchStartV3.REJECTED, controller.startSelected())
                } else if (marker == MarkerState.UNKNOWN) {
                    assertEquals(SearchStartV3.BUSY, controller.startSelected())
                    assertEquals(1, snapshot.selectedInput.closeAttempts)
                    assertEquals(0, fixture.runtime.starts)
                }
            }
        }
    }

    @Test
    fun restoreRejectsInvalidOrBusySnapshotAndRetainsFailedCleanup(): Unit {
        for (case in listOf("stale", "selection", "no-supported", "duplicate", "occupied", "request", "close-failure")) {
            val fixture = Fixture()
            val port = FakeSubscriptionPort()
            val revision = ResultRevision("1", "2")
            fixture.revisions.value = revision
            val controller = SubscriptionController(port, fixture.composition, { error("unexpected source commit") }, { error("unexpected selection commit") })
            val previous = Snapshot(listOf(record("previous", true)))
            if (case == "occupied") assertTrue(controller.restore(previous, setOf("previous"), revision))
            if (case == "request") assertEquals(SubscriptionStartV1.ACCEPTED, controller.load("synthetic"))
            val records = when (case) {
                "no-supported" -> listOf(record("a", false))
                "duplicate" -> listOf(record("a", true), record("a", true))
                else -> listOf(record("a", true))
            }
            val snapshot = Snapshot(records)
            if (case == "close-failure") snapshot.closeFailures = 1
            val incomingRevision = if (case == "stale" || case == "close-failure") ResultRevision("1", "3") else revision
            val ids = if (case == "selection") setOf("missing") else setOf("a")
            assertFalse("case=$case", controller.restore(snapshot, ids, incomingRevision))
            assertEquals(1, snapshot.closeAttempts)
            assertEquals(case != "close-failure", snapshot.closed)
            assertEquals(revision, fixture.revisions.value)
            assertEquals(0, fixture.revisions.writes)
            assertEquals(0, fixture.marker.activeCalls)
            if (case == "occupied") {
                assertFalse(previous.closed)
                assertEquals(setOf("previous"), controller.state.value.selectedIds)
            }
            if (case == "request") {
                assertTrue(controller.state.value.requestActive)
                controller.cancel()
            }
            if (case == "close-failure") {
                assertEquals(SubscriptionPhase.FAILED, controller.state.value.phase)
                assertEquals(SubscriptionStartV1.BUSY, controller.load("synthetic"))
                assertTrue(controller.retryCleanup())
                assertTrue(snapshot.closed)
                assertEquals(2, snapshot.closeAttempts)
            }
        }
    }

    @Test
    fun restoreEnforcesSnapshotRecordLimit(): Unit {
        for (count in listOf(0, 9_999, 10_000, 10_001)) {
            val fixture = Fixture()
            val revision = ResultRevision("1", "2")
            fixture.revisions.value = revision
            val controller = SubscriptionController(FakeSubscriptionPort(), fixture.composition, { error("unexpected source commit") }, { error("unexpected selection commit") })
            val snapshot = Snapshot(List(count) { record("id-$it", true) })
            val accepted = count in 1..10_000
            assertEquals(accepted, controller.restore(snapshot, emptySet(), revision))
            assertEquals(!accepted, snapshot.closed)
            assertEquals(if (accepted) 0 else 1, snapshot.closeAttempts)
            assertEquals(0, fixture.marker.activeCalls)
            assertEquals(0, fixture.revisions.writes)
        }
    }

    @Test
    fun restoreSerializesRevisionCheckAndCompetingInstallations(): Unit {
        val fixture = Fixture()
        val port = FakeSubscriptionPort()
        val revision = ResultRevision("1", "2")
        fixture.revisions.value = revision
        val controller = SubscriptionController(port, fixture.composition, { error("unexpected source commit") }, { error("unexpected selection commit") })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.revisions.onRead = {
            assertTrue(Thread.holdsLock(controller))
            assertTrue(Thread.holdsLock(fixture.composition.controller))
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val first = Snapshot(listOf(record("first", true)))
        val second = Snapshot(listOf(record("second", true)))
        val firstResult = FutureTask(Callable { controller.restore(first, setOf("first"), revision) })
        val secondResult = FutureTask(Callable { controller.restore(second, setOf("second"), revision) })
        thread { firstResult.run() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val secondThread = thread { secondResult.run() }
        try {
            awaitBlocked(secondThread, SubscriptionController::class.java.name, "restore")
        } finally {
            release.countDown()
        }
        assertTrue(firstResult.get(10, TimeUnit.SECONDS))
        assertFalse(secondResult.get(10, TimeUnit.SECONDS))
        assertFalse(first.closed)
        assertTrue(second.closed)
        assertEquals(1, second.closeAttempts)
        assertEquals(setOf("first"), controller.state.value.selectedIds)
        assertEquals(0, fixture.revisions.writes)
        assertEquals(0, fixture.marker.activeCalls)
    }

    private fun syntheticUrl(hostAndPath: String): String = "https:" + "/" + "/" + hostAndPath

    private fun record(id: String, supported: Boolean): SubscriptionRecordV1 = SubscriptionRecordV1(
        id, "local", "local", supported, if (supported) null else SubscriptionRejectV1.UNSUPPORTED_RECORD,
    )

    private val fixtures = mutableMapOf<SearchController, Fixture>()

    private fun search(runtime: FakeSearchPort = FakeSearchPort()): SearchController {
        val fixture = Fixture(runtime)
        return fixture.composition.controller.also { fixtures[it] = fixture }
    }

    private fun legacyController(
        port: FakeSubscriptionPort,
        search: SearchController,
        commitSource: () -> Boolean,
        commitSelection: (Set<String>) -> Boolean,
        currentRevisions: () -> ResultRevision?,
    ): SubscriptionController {
        val fixture = fixtures.getValue(search)
        fun fresh(): ResultRevision {
            val requested = checkNotNull(currentRevisions())
            val previous = fixture.revisions.value
            return if (requested != previous && requested != fixture.storage.manifest?.revision) requested
            else ResultRevision(requested.source, (checkNotNull(previous).selection.toLong() + 1).toString())
        }
        return SubscriptionController(port, fixture.composition, { check(commitSource()); fresh() }, { ids ->
            check(commitSelection(ids))
            fresh()
        })
    }

    private class Fixture(val runtime: FakeSearchPort = FakeSearchPort(), initialMarker: MarkerState = MarkerState.CLEAR) {
        val storage = FakeStorage()
        val revisions = FakeRevisions()
        val marker = FakeMarker(initialMarker)
        val registry = VpnRunRegistry()
        val composition: ResultActivationComposition = ResultActivationComposition::class.java.getDeclaredConstructor(
            ResultStorage::class.java,
            RevisionStorage::class.java,
            SearchSessionPortV3::class.java,
            SessionMarker::class.java,
            VpnRunRegistry::class.java,
        ).also { it.isAccessible = true }.newInstance(storage, revisions, runtime, marker, registry)
    }

    private class Snapshot(override val records: List<SubscriptionRecordV1>) : SubscriptionSnapshotV1 {
        var closed = false
        var closeAttempts = 0
        var closeFailures = 0
        var selectedInput = FakeInput()
        var requestedIds: Set<String>? = null
        var onSelected: () -> Unit = {}
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1? {
            requestedIds = ids.toSet()
            onSelected()
            return selectedInput
        }
        override fun close(): Unit {
            closeAttempts++
            if (closeFailures > 0) { closeFailures--; throw java.io.IOException("synthetic") }
            closed = true
        }
    }

    private class FakeInput : PrivateInputV1 {
        var closed = false
        var closeAttempts = 0
        var closeFailures = 0
        override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close(): Unit {
            closeAttempts++
            if (closeFailures > 0) { closeFailures--; throw java.io.IOException("synthetic") }
            closed = true
        }
    }

    private class FakeSubscriptionPort : SubscriptionPortV1 {
        var observer: SubscriptionObserverV1? = null
        var onStart: (Long, SubscriptionObserverV1) -> Unit = { _, _ -> }
        val cancellations = mutableListOf<Long>()
        override fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1 {
            this.observer = observer
            onStart(generation, observer)
            return SubscriptionStartV1.ACCEPTED
        }
        override fun cancel(generation: Long): Unit { cancellations.add(generation) }
    }

    private class FakeSearchPort : SearchSessionPortV3 {
        var snapshot: SearchSnapshotV1? = null
        var starts = 0
        var result = SearchStartV3.ACCEPTED
        var throwOnStart = false
        var onStart: (SearchSessionObserverV3) -> Unit = {}
        val cancellations = mutableListOf<Pair<Long, Long>>()
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            this.snapshot = snapshot
            starts++
            onStart(observer)
            if (throwOnStart) throw IllegalStateException("synthetic")
            return result
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.ACCEPTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit { cancellations.add(sessionGeneration to phaseEpoch) }
    }

    private class FakeMarker(var value: MarkerState = MarkerState.CLEAR) : SessionMarker {
        var activeCalls = 0
        var throwAfterActive = false
        override fun read(): MarkerState = value
        override fun markActive(): Boolean {
            activeCalls++
            value = MarkerState.ACTIVE
            if (throwAfterActive) throw IOException("synthetic")
            return true
        }
        override fun markClear(): Boolean { value = MarkerState.CLEAR; return true }
    }

    private class FakeRevisions : RevisionStorage {
        var value: ResultRevision? = null
        var failBeforeWrite = false
        var failAfterWrite = false
        var writes = 0
        var onRead: () -> Unit = {}
        override fun read(): ResultRevision? { onRead(); return value }
        override fun write(revision: ResultRevision): Unit {
            writes++
            if (failBeforeWrite) throw IOException("synthetic")
            value = revision
            if (failAfterWrite) throw IOException("synthetic")
        }
    }

    private class FakeStorage : ResultStorage {
        var manifest: ResultManifest? = null
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String = error("unused")
        override fun readGeneration(generation: String): StoredResult? = null
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit = error("unused")
        override fun discard(generation: String): Unit = Unit
    }
}
