package com.seraf.vpnsweep.searchworker

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.CompletionDisposition
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.SearchReceiptState
import com.seraf.vpnsweep.acceptSearchTerminal
import com.seraf.vpnsweep.VpnStartGateV1
import com.seraf.vpnsweep.currentVpnIdentityMatches
import com.seraf.vpnsweep.reconcileRecoveredSubscription
import com.seraf.vpnsweep.restoreBlockedSubscription
import com.seraf.vpnsweep.prepareVerifiedSelection
import com.seraf.vpnsweep.subscriptionSnapshotEnvironmentSafe
import com.seraf.vpnsweep.resultactivation.PrivateSearchCompletionReceiptStorage
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
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreconnectProducerJointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unknownCleanupDoesNotBlockSavingNextSelection(): Unit {
        val root = temporary.newFolder()
        val test = Harness(root)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.unbind.get()!!.invoke(false)
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(setOf(ID_B), test.subscriptions.state.value.selectedIds)
            val revision = checkNotNull(test.composition.currentRevision())
            val reads = test.snapshotReads.get()
            val reopened = SubscriptionStorage(File(root, "subscriptions"), SnapshotFiles({}) { test.snapshotReads.incrementAndGet() }) { revision }
            val display = checkNotNull(reopened.recoverDisplay(revision))
            assertEquals(setOf(ID_B), display.selectedIds)
            assertEquals(listOf("A", "B"), display.records.map { it.title })
            assertEquals(reads, test.snapshotReads.get())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
        } finally { test.releasePortWait.countDown(); test.returnRun.countDown() }
    }

    @Test fun ambiguousSelectionRenameIsNeverReportedAsSaved(): Unit {
        val root = temporary.newFolder()
        val test = Harness(root)
        test.installSelection()
        val before = checkNotNull(test.composition.currentRevision())
        assertTrue(test.subscriptions.select(setOf(ID_A)))
        assertEquals(before, test.composition.currentRevision())
        test.revisions.failAfterRename = true
        assertFalse(test.subscriptions.select(setOf(ID_B)))
        assertEquals(setOf(ID_A), test.subscriptions.state.value.selectedIds)
        assertNull(test.composition.currentRevision())
        assertFalse(test.subscriptions.select(setOf(ID_A)))
        val restarted = Harness(root)
        val visible = checkNotNull(restarted.composition.currentRevision())
        assertNotEquals(before, visible)
        assertEquals(setOf(ID_B), checkNotNull(restarted.storage.recoverDisplay(visible)).selectedIds)
    }

    @Test fun legacyActiveRestoresRealNamesAndPreservesNewSelectionThroughReconcile(): Unit {
        val root = temporary.newFolder()
        val original = Harness(root)
        original.installSelection()
        val revision = checkNotNull(original.composition.currentRevision())
        val display = File(root, "subscriptions/${revision.source}-${revision.selection}/display")
        assertTrue(display.delete())
        val restarted = Harness(root)
        assertTrue(restarted.marker.markActive())
        for (env in listOf<(String) -> String?>(
            { name -> if (name == "xray.browser.dialer") "not-empty" else null },
            { name -> if (name == "XRAY_BROWSER_DIALER") "not-empty" else null },
            { throw SecurityException("synthetic") },
        )) {
            assertNull(restoreBlockedSubscription(revision, restarted.storage, restarted.subscriptions) {
                subscriptionSnapshotEnvironmentSafe(env)
            })
            assertEquals(0, restarted.snapshotReads.get())
        }
        display.writeText("corrupt")
        assertNull(restoreBlockedSubscription(revision, restarted.storage, restarted.subscriptions) { true })
        assertEquals(0, restarted.snapshotReads.get())
        assertTrue(display.delete())
        val restored = checkNotNull(restoreBlockedSubscription(revision, restarted.storage, restarted.subscriptions) {
            subscriptionSnapshotEnvironmentSafe { null }
        })
        restarted.receipts.accepted = checkNotNull(restored.accepted)
        assertEquals(listOf("A", "B"), restarted.subscriptions.state.value.records.map { it.title })
        assertEquals(setOf(ID_A), restarted.subscriptions.state.value.selectedIds)
        assertTrue(restarted.subscriptions.select(setOf(ID_B)))
        assertNull(restarted.receipts.accepted)
        val selected = checkNotNull(restarted.composition.currentRevision())
        assertEquals(setOf(ID_B), checkNotNull(restarted.storage.recoverDisplay(selected)).selectedIds)
        assertTrue(reconcileRecoveredSubscription(restarted.composition, restarted.storage,
            restarted.subscriptions, Any(), restarted.receipts))
        val reissued = checkNotNull(restarted.composition.currentRevision())
        assertNotEquals(selected, reissued)
        assertEquals(setOf(ID_B), restarted.subscriptions.state.value.selectedIds)
        assertEquals(listOf("A", "B"), restarted.subscriptions.state.value.records.map { it.title })
        assertEquals(setOf(ID_B), checkNotNull(restarted.storage.recoverDisplay(reissued)).selectedIds)
        assertEquals(AcceptedSubscriptionReceiptV1(1, reissued.source, reissued.selection), restarted.receipts.accepted)
        assertEquals(MarkerState.ACTIVE, restarted.marker.read())
    }

    @Test fun displayOnlyRestoreNeverCreatesAcceptedReceipt(): Unit {
        val root = temporary.newFolder()
        val original = Harness(root)
        original.installSelection()
        val revision = checkNotNull(original.composition.currentRevision())
        val restarted = Harness(root)
        assertTrue(restarted.marker.markActive())
        val display = checkNotNull(restoreBlockedSubscription(revision, restarted.storage, restarted.subscriptions) {
            error("display-only restore must not load Go")
        })
        assertNull(display.accepted)
        assertEquals(0, restarted.snapshotReads.get())
        assertEquals(listOf("A", "B"), restarted.subscriptions.state.value.records.map { it.title })
        assertTrue(restarted.subscriptions.select(setOf(ID_B)))
        assertNull(restarted.receipts.accepted)
        assertEquals(SearchStartV3.BUSY, restarted.subscriptions.startSelected())
        assertEquals(0, restarted.snapshotReads.get())
    }

    @Test fun savedSelectionInvalidatesOldVpnIdentityAndSupersedesOldCompletion(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        val original = checkNotNull(test.composition.currentRevision())
        val recovered = checkNotNull(test.storage.recover(original))
        val accepted = checkNotNull(AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(1, recovered.snapshot, original))
        recovered.snapshot.close()
        val searched = SearchLeaseReceiptV1(1, 1, original.source.toLong(), original.selection.toLong(), "00000000-0000-4000-8000-000000000001")
        test.receipts.accepted = accepted
        test.receipts.active = searched
        test.receipts.completed = searched
        val vpnLease = "00000000-0000-4000-8000-000000000002"
        val identity = checkNotNull(test.receipts.vpnIdentity(original, vpnLease) { true })
        val gate = VpnStartGateV1()
        val ticket = checkNotNull(gate.issue(identity))
        assertTrue(test.receipts.completionDisposition(searched, original) is CompletionDisposition.Current)
        assertTrue(test.subscriptions.select(setOf(ID_B)))
        val current = checkNotNull(test.composition.currentRevision())
        assertNull(test.receipts.accepted)
        assertNull(test.receipts.completed)
        assertEquals(searched, test.receipts.active)
        assertEquals(CompletionDisposition.Superseded, test.receipts.completionDisposition(searched, current))
        assertNull(test.receipts.vpnIdentity(current, vpnLease) { true })
        assertFalse(currentVpnIdentityMatches(test.controller, identity, { false }) {
            test.receipts.vpnIdentity(current, vpnLease) { true }
        })
        assertEquals(identity, gate.pendingIdentity(ticket.token))
    }

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
            val before = test.composition.currentRevision()
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            val after = checkNotNull(test.composition.currentRevision())
            assertNotEquals(before, after)
            assertNull(test.receipts.accepted)
            assertEquals(setOf(ID_B), checkNotNull(test.storage.recoverDisplay(after)).selectedIds)
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertTrue(test.portCancelled.await(3, TimeUnit.SECONDS))
            test.releasePortWait.countDown()
            test.controller.cancel()
            assertEquals(SearchPhase.CANCELLING, test.controller.state.value.phase)
            test.unbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.attempt.get()!!.die()
            test.pump()
            test.closeDuplicate.get()!!.invoke()
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            assertEquals(1, test.anchorCloses.get())
            assertEquals(SearchPhase.CANCELLED, test.controller.state.value.phase)
            val restored = checkNotNull(test.storage.recover(after))
            assertEquals(setOf(ID_B), restored.selectedIds)
            restored.snapshot.close()
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            assertEquals(AcceptedSubscriptionReceiptV1(1, after.source, after.selection), test.receipts.accepted)
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
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(setOf(ID_B), test.subscriptions.state.value.selectedIds)
            assertEquals(setOf(ID_A, ID_B), updateVisibleSelection(test.subscriptions.state.value.selectedIds, setOf(ID_A), true))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.releasePortWait.countDown(); test.returnRun.countDown() }
    }

    @Test fun connectedCancelWaitsForOriginalWorkerAndLocalDrainBeforeStartingSavedSelection(): Unit = exerciseConnected(false, false)

    @Test fun failedAutomaticConnectedCancelUsesSameConfirmedRecovery(): Unit = exerciseConnected(true, false)

    @Test fun timedOutAutomaticConnectedCancelWaitsForIpcReturnBeforeRecovery(): Unit = exerciseConnected(false, true)

    @Test fun originalBinderDeathBeforeStopRecoversOnlyAfterFullDrain(): Unit = exerciseConnectedDeath(true)

    @Test fun disconnectBeforeBinderDeathDoesNotPretendDeath(): Unit = exerciseConnectedDeath(false)

    private fun exerciseConnectedDeath(deathFirst: Boolean): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            if (deathFirst) test.originalDeath.get()!!.invoke() else test.disconnect.get()!!.invoke()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            if (!deathFirst) test.originalDeath.get()!!.invoke()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun connectedUnknownStillSavesSelectionWithoutAllowingOverlap(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.unbind.get()!!.invoke(false)
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            val revision = checkNotNull(test.composition.currentRevision())
            assertEquals(setOf(ID_B), checkNotNull(test.storage.recoverDisplay(revision)).selectedIds)
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
        } finally { test.returnRun.countDown() }
    }

    @Test fun connectedCallCompletionAfterFenceDrainsBeforeRetry(): Unit = exerciseCallAfterFence("after")

    @Test fun connectedCallFenceBeforeDispatchDrainsBeforeRetry(): Unit = exerciseCallAfterFence("dispatch")

    @Test fun deadBinderDuringCallAfterFenceDrainsBeforeRetry(): Unit = exerciseCallAfterFence("remoteAfter")

    @Test fun confirmedDeathBeforeCallBinderObservationWaitsForFullCleanup(): Unit = exerciseDeathPendingCall(false)

    @Test fun confirmedDeathAfterCallBinderObservationWaitsForFullCleanup(): Unit = exerciseDeathPendingCall(true)

    @Test fun notAliveObservationRequiresActualDeathBeforeReleasingOwner(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "notAlive")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.serviceFailures.contains(WorkerFatalV2.UNKNOWN_DELIVERY))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.originalDeath.get()!!.invoke()
            test.pump()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.resumeCallSnapshot.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun binderNotAliveIsDistinctFromAnchorOrNetworkFailure(): Unit {
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
        } as IBinder
        val state = AtomicReference(CoordinatorState.CONFIRMED)
        val death = observeAdmittedWorker(binder, state::get) {
            state.set(CoordinatorState.DEATH_PENDING)
            WorkerBinderValidation.NOT_ALIVE
        }
        assertTrue(death is WorkerBinderObservation.DeathPending)
        state.set(CoordinatorState.CONFIRMED)
        val unavailable = observeAdmittedWorker(binder, state::get) { WorkerBinderValidation.NOT_ALIVE }
        assertTrue(unavailable is WorkerBinderObservation.NotAlive)
        state.set(CoordinatorState.CONFIRMED)
        val invalid = observeAdmittedWorker(binder, state::get) {
            state.set(CoordinatorState.DEATH_PENDING)
            WorkerBinderValidation.UNKNOWN
        }
        assertEquals(WorkerBinderObservation.Unknown, invalid)
    }

    private fun exerciseDeathPendingCall(afterBinder: Boolean): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true,
            callRace = if (afterBinder) "deathAfterBinder" else "deathBeforeBinder")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            if (afterBinder) assertTrue(test.callBinderCaptured.await(3, TimeUnit.SECONDS))
            else assertTrue(test.callFrameEntered.await(3, TimeUnit.SECONDS))
            Thread { try { test.originalDeath.get()!!.invoke() } finally { test.deathHandlerReturned.countDown() } }.start()
            assertTrue(test.deathHandlerEntered.await(3, TimeUnit.SECONDS))
            test.resumeCallFrame.countDown()
            test.resumeCallBinder.countDown()
            test.allowCallReturn.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            test.resumeDeathHandler.countDown()
            assertTrue(test.deathHandlerReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.serviceFailures.contains(WorkerFatalV2.WORKER_DIED))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.resumeCallFrame.countDown(); test.resumeCallBinder.countDown(); test.allowCallReturn.countDown()
            test.resumeCallSnapshot.countDown(); test.resumeDeathHandler.countDown()
            test.returnRun.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun completedCallCannotReturnBeforeProducerRecoveryFence(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "handshake", pauseRecoveryStart = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            Thread { test.controller.cancel() }.start()
            assertTrue(test.recoveryStartEntered.await(3, TimeUnit.SECONDS))
            test.allowCallReturn.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.recoveryWaitEntered.await(3, TimeUnit.SECONDS))
            assertEquals(1L, test.callReturned.count)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.resumeRecoveryStart.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
        } finally {
            test.allowCallReturn.countDown(); test.resumeCallSnapshot.countDown(); test.resumeRecoveryStart.countDown()
            test.returnRun.countDown()
        }
    }

    @Test fun timedOutCallAndGoReturnBeforeSetupStillCompleteAfterLateProof(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "handshake",
            pauseRecoveryStart = true, virtualClock = true, pauseWatch = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.recoveryStartEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            test.allowCallReturn.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.fakeTime.set(10_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            test.returnRun.countDown()
            assertTrue(test.recoveryPhaseDone.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.resumeRecoveryStart.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            completeConnectedProof(test)
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.allowCallReturn.countDown(); test.resumeCallSnapshot.countDown(); test.resumeRecoveryStart.countDown(); test.resumeWatchSample.countDown()
            test.returnRun.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun failedRecoveryScheduleReportsUnknownAndRejectsLateTask(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, rejectRecoverySchedule = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
            checkNotNull(test.lateRecoveryAction.get()).invoke()
            assertNull(test.attempt.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.returnRun.countDown() }
    }

    @Test fun failedWholeRunAutomaticallyStopsOwnedRoundBeforeRetry(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, runFailureBeforeAdapterClose = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.returnRun.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            completeConnectedProof(test)
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun connectedOpenBeforeDispatchWaitsForItsLocalDrain(): Unit = exerciseConnectedOpen("before", false)

    @Test fun connectedOpenTransactionDeathWaitsForItsLocalDrain(): Unit = exerciseConnectedOpen("during", false)

    @Test fun connectedOpenWaitsForBoundedTaskBookkeeping(): Unit = exerciseConnectedOpen("bookkeeping", false)

    @Test fun connectedOpenBookkeepingFailureKeepsOwnerBlocked(): Unit = exerciseConnectedOpen("bookkeepingFailure", true)

    @Test fun admissionStopsBeforeRemoteDeathAndWaitsForTransfer(): Unit = exerciseAdmissionStop(false)

    @Test fun admissionDeathBeforeStopWaitsForTransfer(): Unit = exerciseAdmissionStop(true)

    @Test fun admissionDeathAfterOpenTimeoutReservationWaitsForSameRecovery(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, openingRace = "admittingTimeout",
            pauseRecoveryStart = true, virtualClock = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        val duplicateCloseDone = CountDownLatch(1)
        try {
            assertTrue(test.openingEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.recoveryStartEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.fakeTime.get() > 6_000)
            test.originalDeath.get()!!.invoke()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.resumeRecoveryStart.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            Thread { try { test.closeDuplicate.get()!!.invoke() } finally { duplicateCloseDone.countDown() } }.start()
            test.attempt.get()!!.die()
            test.pump()
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(1L, duplicateCloseDone.count)
            test.waitForDrainSchedule = true
            test.resumeOpening.countDown()
            assertTrue(duplicateCloseDone.await(3, TimeUnit.SECONDS))
            assertTrue(test.admissionReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.openingReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            test.pumpUntilFirstTerminal()
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.resumeRecoveryStart.countDown(); test.resumeOpening.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown()
        }
    }

    private fun exerciseAdmissionStop(deathFirst: Boolean): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, openingRace = "admitting")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        val duplicateCloseDone = CountDownLatch(1)
        try {
            assertTrue(test.openingEntered.await(3, TimeUnit.SECONDS))
            if (deathFirst) test.originalDeath.get()!!.invoke() else test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            Thread { try { test.closeDuplicate.get()!!.invoke() } finally { duplicateCloseDone.countDown() } }.start()
            test.attempt.get()!!.die()
            if (!deathFirst) test.originalDeath.get()!!.invoke()
            test.pump()
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(1L, duplicateCloseDone.count)
            test.waitForDrainSchedule = true
            test.resumeOpening.countDown()
            assertTrue(duplicateCloseDone.await(3, TimeUnit.SECONDS))
            assertTrue(test.admissionReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.openingReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.resumeOpening.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun confirmedDeathBeforeOpenConfirmationUsesExactBinderProof(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, openingRace = "confirmationDeath")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.openingEntered.await(3, TimeUnit.SECONDS))
            Thread { try { test.originalDeath.get()!!.invoke() } finally { test.deathHandlerReturned.countDown() } }.start()
            assertTrue(test.deathHandlerEntered.await(3, TimeUnit.SECONDS))
            test.resumeOpening.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.openingReturned.await(3, TimeUnit.SECONDS))
            test.resumeDeathHandler.countDown()
            assertTrue(test.deathHandlerReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.resumeOpening.countDown(); test.resumeDeathHandler.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun deathBeforeBinderPublicationStopsSameRemoteAndWaitsForOpenTask(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, openingRace = "publication")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.publicationEntered.await(3, TimeUnit.SECONDS))
            test.originalDeath.get()!!.invoke()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.waitForDrainSchedule = true
            test.resumePublication.countDown()
            assertTrue(test.openingReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.resumePublication.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun connectedOpenLocalCloseFailureKeepsOwnerBlocked(): Unit = exerciseConnectedOpen("during", true)

    private fun exerciseConnectedOpen(position: String, failedClose: Boolean): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, openingRace = position,
            openingCloseFailure = failedClose && position != "bookkeepingFailure")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.openingEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.waitForDrainSchedule = true
            test.resumeOpening.countDown()
            assertTrue(test.openingReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            if (failedClose) {
                assertEquals(MarkerState.ACTIVE, test.marker.read())
                assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
                assertEquals(0, test.anchorCloses.get())
            } else {
                assertEquals(MarkerState.CLEAR, test.marker.read())
                test.blockSecond = true
                assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
                assertEquals(setOf(ID_B), test.profilesSelected.get())
                test.releaseSecond.countDown()
                assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
            }
        } finally { test.resumeOpening.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun phaseIntentRejectsNormalOutcomeAndStaleGeneration(): Unit {
        val normal = PhaseRecoveryIntent(1, 2)
        assertFalse(normal.claimRunExit())
        assertFalse(normal.request(1))
        val recovering = PhaseRecoveryIntent(1, 3)
        assertFalse(recovering.request(2))
        assertTrue(recovering.request(1))
        assertTrue(recovering.claimRunExit())
        recovering.close()
        assertFalse(recovering.request(1))
        val next = PhaseRecoveryIntent(1, 4)
        assertTrue(next.request(1))
        assertFalse(recovering.request(1))
    }

    @Test fun recoveryClaimBeforeRunOutcomePreservesRecoveryUntilDrain(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, pausePhaseClaim = "before")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.returnRun.countDown()
            assertTrue(test.phaseClaimEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.resumePhaseClaim.countDown()
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
        } finally { test.resumePhaseClaim.countDown(); test.returnRun.countDown() }
    }

    @Test fun normalRunOutcomeClaimBeforeRecoveryKeepsUnknownGuard(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, pausePhaseClaim = "after")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.returnRun.countDown()
            assertTrue(test.phaseClaimEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            test.resumePhaseClaim.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
        } finally { test.resumePhaseClaim.countDown(); test.returnRun.countDown() }
    }

    @Test fun lateConfirmedCleanupAndTerminalReleaseSavedSelection(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, virtualClock = true,
            blockRecoveryClose = true, blockLeaseClose = true, blockTerminalAcceptance = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.fakeTime.set(10_000)
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            completeConnectedProof(test)
            test.returnRun.countDown()
            assertTrue(test.recoveryCloseEntered.await(3, TimeUnit.SECONDS))
            test.fakeTime.set(20_000)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(1, test.recoveryCloseCalls.get())
            assertEquals(0, test.anchorCloses.get())
            test.resumeRecoveryClose.countDown()
            assertTrue(test.leaseCloseEntered.await(3, TimeUnit.SECONDS))
            test.fakeTime.set(30_000)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.resumeLeaseClose.countDown()
            assertTrue(test.terminalAcceptEntered.await(3, TimeUnit.SECONDS))
            test.fakeTime.set(40_000)
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.resumeTerminalAccept.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            assertEquals(1, test.recoveryCloseCalls.get())
            assertEquals(1, test.anchorCloses.get())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.returnRun.countDown(); test.resumeRecoveryClose.countDown(); test.resumeLeaseClose.countDown()
            test.resumeTerminalAccept.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun unfinishedOrFailedRecoveryCloseNeverClearsMarker(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, virtualClock = true, failRecoveryClose = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.fakeTime.set(10_000)
            completeConnectedProof(test)
            test.returnRun.countDown()
            assertTrue(test.recoveryCloseEntered.await(3, TimeUnit.SECONDS))
            test.fakeTime.set(20_000)
            assertEquals(1, test.recoveryCloseCalls.get())
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.resumeRecoveryClose.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(1, test.recoveryCloseCalls.get())
            assertEquals(0, test.anchorCloses.get())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.returnRun.countDown(); test.resumeRecoveryClose.countDown() }
    }

    @Test fun retiredWorkerDelayedResultDeliveryReleasesOwnerAfterDeadline(): Unit = exerciseDelayedResultDelivery(false)

    @Test fun retiredWorkerDelayedResultCloseFailureKeepsOwnerBlocked(): Unit = exerciseDelayedResultDelivery(true)

    @Test fun cancellationAfterRetirementBeforeResultOpenWaitsForLateCleanup(): Unit = exerciseRetirementPublication(false)

    @Test fun cancellationAfterRetireReturnBeforePublicationWaitsForLateCleanup(): Unit = exerciseRetirementPublication(true)

    @Test fun failedRetirementAfterPendingCancelKeepsOwnerBlocked(): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true,
            deliveryRace = true, failResultRetirement = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.resultRetirementEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.controller.cancel()
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.resumeResultRetirement.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.resumeWatchSample.countDown(); test.resumeResultRetirement.countDown() }
    }

    private fun exerciseRetirementPublication(beforePublication: Boolean): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true,
            deliveryRace = true, pauseAfterRetirement = !beforePublication, pauseBeforeRetirementPublication = beforePublication)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.retiredEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.controller.cancel()
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.resumeRetired.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertNull(test.results.readManifest())
            assertEquals(0, test.resultInputCloses.get())
            assertEquals(1, test.resultSessionCloses.get())
            assertEquals(1, test.anchorCloses.get())
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.resumeWatchSample.countDown(); test.resumeRetired.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun cancellationDuringSessionCloseWaitsForItsLateReturn(): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true,
            deliveryRace = true, blockResultSessionClose = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.resultPrepared.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.resumeResultPrepare.countDown()
            assertTrue(test.resultSessionCloseEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(0, test.anchorCloses.get())
            test.resumeResultSessionClose.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertNull(test.results.readManifest())
            assertEquals(1, test.resultInputCloses.get())
            assertEquals(1, test.resultSessionCloses.get())
            assertEquals(1, test.anchorCloses.get())
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.resumeWatchSample.countDown(); test.resumeResultPrepare.countDown()
            test.resumeResultSessionClose.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun cancellationAfterRetirementWaitsForPriorControlTask(): Unit = exercisePriorControlTask(false)

    @Test fun failedPriorControlTaskKeepsRetiredOwnerBlocked(): Unit = exercisePriorControlTask(true)

    private fun exercisePriorControlTask(failed: Boolean): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true, deliveryRace = true,
            pausePriorControl = true, failPriorControl = failed)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.deliveryRunEntered.await(3, TimeUnit.SECONDS))
            test.controller.pause()
            assertTrue(test.priorControlReturned.await(3, TimeUnit.SECONDS))
            test.resumeDeliveryRun.countDown()
            assertTrue(test.resultPrepared.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.controller.cancel()
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            test.resumeResultPrepare.countDown()
            assertTrue(test.resultInputClosed.await(3, TimeUnit.SECONDS))
            assertTrue(test.retiredCancelWait.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(0, test.anchorCloses.get())
            assertEquals(1L, test.resultSessionCloseEntered.count)
            test.resumePriorControl.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertNull(test.results.readManifest())
            if (failed) {
                assertEquals(MarkerState.ACTIVE, test.marker.read())
                assertEquals(0, test.anchorCloses.get())
                assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            } else {
                assertEquals(MarkerState.CLEAR, test.marker.read())
                assertEquals(1, test.anchorCloses.get())
                test.blockSecond = true
                assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
                assertEquals(setOf(ID_B), test.profilesSelected.get())
                test.releaseSecond.countDown()
                assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
            }
        } finally {
            test.resumeDeliveryRun.countDown(); test.resumePriorControl.countDown(); test.resumeWatchSample.countDown()
            test.resumeResultPrepare.countDown(); test.releaseSecond.countDown()
        }
    }

    private fun exerciseDelayedResultDelivery(failedClose: Boolean): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true,
            deliveryRace = true, failResultSessionClose = failedClose, productionTerminalGate = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.resultPrepared.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.controller.cancel()
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(0, test.resultInputCloses.get())
            assertEquals(0, test.anchorCloses.get())
            test.resumeResultPrepare.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertNull(test.results.readManifest())
            assertEquals(1, test.resultInputCloses.get())
            assertEquals(1, test.resultSessionCloses.get())
            if (failedClose) {
                assertEquals(MarkerState.ACTIVE, test.marker.read())
                assertEquals(0, test.anchorCloses.get())
                assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            } else {
                assertEquals(MarkerState.CLEAR, test.marker.read())
                assertEquals(1, test.anchorCloses.get())
                assertNull(test.receipts.active)
                assertFalse(test.terminalIdentities.containsKey(1))
                test.blockSecond = true
                assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
                assertEquals(setOf(ID_B), test.profilesSelected.get())
                assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
                val secondReceipt = checkNotNull(test.receipts.active)
                assertEquals(2L, secondReceipt.sessionGeneration)
                test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                    SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
                assertEquals(MarkerState.ACTIVE, test.marker.read())
                assertEquals(secondReceipt, test.receipts.active)
                test.releaseSecond.countDown()
                assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
            }
        } finally {
            test.resumeWatchSample.countDown(); test.resumeResultPrepare.countDown(); test.releaseSecond.countDown()
        }
    }

    @Test fun terminalIdentityMismatchKeepsMarkerAndReceipt(): Unit = exerciseTerminalRejection(true)

    @Test fun terminalProducerRejectionKeepsMarkerAndReceipt(): Unit = exerciseTerminalRejection(false)

    @Test fun supersededCompleteCannotReplaceNewSelectionOrActiveReceipt(): Unit {
        val test = Harness(temporary.newFolder())
        test.installSelection()
        val firstRevision = checkNotNull(test.composition.currentRevision())
        val first = SearchLeaseReceiptV1(1, 1, firstRevision.source.toLong(), firstRevision.selection.toLong(),
            java.util.UUID(0, 1).toString())
        val identities = linkedMapOf(1L to first)
        val identityLock = Any()
        val receiptLock = Any()
        test.receipts.active = first
        assertTrue(test.marker.markActive())
        assertTrue(test.subscriptions.select(setOf(ID_B)))
        val secondRevision = checkNotNull(test.composition.currentRevision())
        assertNotEquals(firstRevision, secondRevision)
        val terminal = SearchTerminalV3.Complete(1, 1, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED)
        var producerAccepts = 0
        assertTrue(acceptSearchTerminal(terminal, identityLock, identities, receiptLock, test.receipts,
            { producerAccepts++; true }, test.composition))
        assertEquals(1, producerAccepts)
        assertNull(test.receipts.active)
        assertFalse(identities.containsKey(1))
        assertEquals(MarkerState.ACTIVE, test.marker.read())
        assertEquals(setOf(ID_B), checkNotNull(test.storage.recoverDisplay(secondRevision)).selectedIds)

        val second = SearchLeaseReceiptV1(1, 2, secondRevision.source.toLong(), secondRevision.selection.toLong(),
            java.util.UUID(0, 2).toString())
        identities[2] = second
        test.receipts.active = second
        assertFalse(acceptSearchTerminal(terminal, identityLock, identities, receiptLock, test.receipts,
            { error("stale terminal reached producer") }, test.composition))
        assertEquals(second, test.receipts.active)
        assertEquals(secondRevision, test.composition.currentRevision())
        assertEquals(MarkerState.ACTIVE, test.marker.read())
    }

    private fun exerciseTerminalRejection(mismatch: Boolean): Unit {
        val test = Harness(temporary.newFolder(), virtualClock = true, pauseWatch = true,
            deliveryRace = true, productionTerminalGate = true)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.resultPrepared.await(3, TimeUnit.SECONDS))
            assertTrue(test.watchSampleEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.controller.cancel()
            val receipt = checkNotNull(test.receipts.active)
            if (mismatch) test.receipts.active = receipt.copy(leaseIdentity = java.util.UUID(0, 99).toString())
            else test.rejectProducerTerminal = true
            val expectedActive = test.receipts.active
            test.fakeTime.set(20_000)
            test.resumeWatchSample.countDown()
            assertTrue(test.recoveryWatchRetained.await(3, TimeUnit.SECONDS))
            test.resumeResultPrepare.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(expectedActive, test.receipts.active)
            assertTrue(test.terminalIdentities.containsKey(1))
            assertNull(test.results.readManifest())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
        } finally { test.resumeWatchSample.countDown(); test.resumeResultPrepare.countDown() }
    }

    private fun completeConnectedProof(test: Harness): Unit {
        test.unbind.get()!!.invoke(true)
        test.originalUnbind.get()!!.invoke(true)
        test.pumpUntilAttemptStarted()
        test.closeDuplicate.get()!!.invoke()
        test.attempt.get()!!.die()
        test.originalDeath.get()!!.invoke()
        test.pump()
        test.waitForDrainSchedule = true
        test.operation.closed(true)
        assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
        test.pump()
    }

    @Test fun interruptedRecoveryDrainWaitsForOperationAndClosesOnce(): Unit {
        val progress = SearchMobilePhaseProgress({ 1L }) { _, _ -> }
        val operationEntered = CountDownLatch(1)
        val finishOperation = CountDownLatch(1)
        val waiterEntered = CountDownLatch(1)
        val waiterDone = CountDownLatch(1)
        val closes = AtomicInteger()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val session = SearchMobilePhaseSession({}, { "interrupted" }, { true }, { error("no result") },
            { closes.incrementAndGet() }, progress, { SearchResourceStateV2.PAUSED_LEASE_HELD }, {})
        val operation = Thread {
            progress.operation {
                operationEntered.countDown()
                check(finishOperation.await(3, TimeUnit.SECONDS))
            }
        }
        val waiter = Thread {
            try {
                waiterEntered.countDown()
                session.closeForRecovery()
                interrupted.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) { failure.set(error) }
            finally { waiterDone.countDown() }
        }
        try {
            operation.start()
            assertTrue(operationEntered.await(3, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(waiterEntered.await(3, TimeUnit.SECONDS))
            waiter.interrupt()
            finishOperation.countDown()
            assertTrue(waiterDone.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(interrupted.get())
            assertEquals(1, closes.get())
        } finally { finishOperation.countDown() }
    }

    @Test fun connectedCleanupWaitsForCallTaskAfterItsOutcome(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "outcome")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.allowCallReturn.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.operation.closed(true)
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.waitForDrainSchedule = true
            test.resumeCallOutcome.countDown()
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally {
            test.startCall.countDown()
            test.allowCallReturn.countDown()
            test.resumeCallOutcome.countDown()
            test.resumeCallSnapshot.countDown()
            test.returnRun.countDown()
            test.releaseSecond.countDown()
        }
    }

    private fun exerciseCallAfterFence(mode: String): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = mode)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            if (mode == "dispatch") assertTrue(test.callBinderCaptured.await(3, TimeUnit.SECONDS))
            else assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.allowCallReturn.countDown()
            test.resumeCallBinder.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            if (mode == "remoteAfter") assertTrue(test.serviceFailures.contains(WorkerFatalV2.UNKNOWN_DELIVERY))
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.startCall.countDown(); test.allowCallReturn.countDown(); test.resumeCallBinder.countDown(); test.resumeCallSnapshot.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun remoteCallFailureBeforeFenceRemainsReportedAndBlockedWithoutDeathProof(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "remoteBefore")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            assertTrue(test.serviceFailures.contains(WorkerFatalV2.UNKNOWN_DELIVERY))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.originalDeath.get()!!.invoke()
            test.pump()
            test.waitForDrainSchedule = true
            test.operation.closed(true)
            assertTrue(test.drainScheduled.await(3, TimeUnit.SECONDS))
            test.pump()
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.startCall.countDown(); test.resumeCallSnapshot.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    @Test fun localCallCloseFailureAfterFenceCannotBecomeConfirmedCleanup(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "close")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.allowCallReturn.countDown()
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            test.pump()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
        } finally { test.startCall.countDown(); test.allowCallReturn.countDown(); test.resumeCallSnapshot.countDown(); test.returnRun.countDown() }
    }

    @Test fun connectedCallFailureBeforeFenceCannotBecomeConfirmedCleanup(): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true, callRace = "before")
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            test.startCall.countDown()
            assertTrue(test.callSnapshot.await(3, TimeUnit.SECONDS))
            assertTrue(test.callEntered.await(3, TimeUnit.SECONDS))
            assertTrue(test.callOutcome.await(3, TimeUnit.SECONDS))
            test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            test.resumeCallSnapshot.countDown()
            assertTrue(test.callReturned.await(3, TimeUnit.SECONDS))
            test.pump()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertEquals(0, test.anchorCloses.get())
        } finally { test.startCall.countDown(); test.allowCallReturn.countDown(); test.resumeCallSnapshot.countDown(); test.returnRun.countDown() }
    }

    private fun exerciseConnected(automaticFailure: Boolean, automaticTimeout: Boolean): Unit {
        val test = Harness(temporary.newFolder(), connectedMode = true,
            automaticCancelFailure = automaticFailure, automaticCancelTimeout = automaticTimeout)
        test.installSelection()
        assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
        try {
            assertTrue(test.connectedReady.await(3, TimeUnit.SECONDS))
            if (automaticFailure || automaticTimeout) test.triggerAutomaticCancel.countDown() else test.controller.cancel()
            assertTrue(test.pending.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            assertTrue(test.subscriptions.select(setOf(ID_B)))
            assertEquals(SearchStartV3.BUSY, test.subscriptions.startSelected())
            test.unbind.get()!!.invoke(true)
            test.originalUnbind.get()!!.invoke(true)
            test.pumpUntilAttemptStarted()
            test.closeDuplicate.get()!!.invoke()
            test.attempt.get()!!.die()
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            test.originalDeath.get()!!.invoke()
            test.pump()
            assertEquals(0, test.anchorCloses.get())
            test.operation.closed(true)
            test.pump()
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            if (automaticTimeout) {
                assertEquals(0, test.anchorCloses.get())
                test.releaseCancelTransaction.countDown()
                assertTrue(test.cancelTransactionReturned.await(3, TimeUnit.SECONDS))
                assertTrue(test.scheduledAfterCancel.await(3, TimeUnit.SECONDS))
                test.pump()
            }
            test.returnRun.countDown()
            assertTrue(test.firstTerminal.await(3, TimeUnit.SECONDS))
            assertEquals(MarkerState.CLEAR, test.marker.read())
            assertEquals(1, test.anchorCloses.get())
            test.blockSecond = true
            assertEquals(SearchStartV3.ACCEPTED, test.subscriptions.startSelected())
            assertEquals(setOf(ID_B), test.profilesSelected.get())
            assertTrue(test.secondEntered.await(3, TimeUnit.SECONDS))
            test.staleObserver.get()!!.onTerminal(SearchTerminalV3.Stopped(1, 1, SearchFailureV1.INTERRUPTED,
                SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED))
            assertEquals(MarkerState.ACTIVE, test.marker.read())
            test.releaseSecond.countDown()
            assertTrue(test.secondTerminal.await(3, TimeUnit.SECONDS))
        } finally { test.triggerAutomaticCancel.countDown(); test.releaseCancelTransaction.countDown(); test.returnRun.countDown(); test.releaseSecond.countDown() }
    }

    private class Harness(root: File, private val connectedMode: Boolean = false, private val automaticCancelFailure: Boolean = false,
        private val automaticCancelTimeout: Boolean = false, private val callRace: String? = null,
        private val pauseRecoveryStart: Boolean = false, private val pausePhaseClaim: String? = null,
        private val virtualClock: Boolean = false, private val blockRecoveryClose: Boolean = false,
        private val failRecoveryClose: Boolean = false, private val blockLeaseClose: Boolean = false,
        private val blockTerminalAcceptance: Boolean = false, private val pauseWatch: Boolean = false,
        private val rejectRecoverySchedule: Boolean = false, private val openingRace: String? = null,
        private val openingCloseFailure: Boolean = false, private val runFailureBeforeAdapterClose: Boolean = false,
        private val deliveryRace: Boolean = false, private val failResultSessionClose: Boolean = false,
        private val pauseAfterRetirement: Boolean = false, private val pauseBeforeRetirementPublication: Boolean = false,
        private val blockResultSessionClose: Boolean = false, private val failResultRetirement: Boolean = false,
        private val pausePriorControl: Boolean = false, private val failPriorControl: Boolean = false,
        private val productionTerminalGate: Boolean = false) {
        val processAdmission = SearchOwnerAdmission()
        val marker = Marker()
        val anchorCloses = AtomicInteger()
        val pending = CountDownLatch(1)
        val connectedReady = CountDownLatch(1)
        val triggerAutomaticCancel = CountDownLatch(1)
        val cancelTransactionEntered = CountDownLatch(1)
        val releaseCancelTransaction = CountDownLatch(1)
        val cancelTransactionReturned = CountDownLatch(1)
        val scheduledAfterCancel = CountDownLatch(1)
        val startCall = CountDownLatch(1)
        val callSnapshot = CountDownLatch(1)
        val resumeCallSnapshot = CountDownLatch(1)
        val callBinderCaptured = CountDownLatch(1)
        val resumeCallBinder = CountDownLatch(1)
        val callFrameEntered = CountDownLatch(1)
        val resumeCallFrame = CountDownLatch(1)
        val deathHandlerEntered = CountDownLatch(1)
        val resumeDeathHandler = CountDownLatch(1)
        val deathHandlerReturned = CountDownLatch(1)
        val recoveryStartEntered = CountDownLatch(1)
        val resumeRecoveryStart = CountDownLatch(1)
        val recoveryWaitEntered = CountDownLatch(1)
        val phaseClaimEntered = CountDownLatch(1)
        val phaseOutcomeClaimed = CountDownLatch(1)
        val recoveryPhaseDone = CountDownLatch(1)
        val resumePhaseClaim = CountDownLatch(1)
        val watchSampleEntered = CountDownLatch(1)
        val resumeWatchSample = CountDownLatch(1)
        val recoveryWatchRetained = CountDownLatch(1)
        val resultPrepared = CountDownLatch(1)
        val resumeResultPrepare = CountDownLatch(1)
        val resultInputCloses = AtomicInteger()
        val resultInputClosed = CountDownLatch(1)
        val resultSessionCloses = AtomicInteger()
        val resultSessionCloseEntered = CountDownLatch(1)
        val resumeResultSessionClose = CountDownLatch(1)
        val resultRetirementEntered = CountDownLatch(1)
        val resumeResultRetirement = CountDownLatch(1)
        val retiredCancelWait = CountDownLatch(1)
        val retiredEntered = CountDownLatch(1)
        val resumeRetired = CountDownLatch(1)
        val deliveryRunEntered = CountDownLatch(1)
        val resumeDeliveryRun = CountDownLatch(1)
        val priorControlReturned = CountDownLatch(1)
        val resumePriorControl = CountDownLatch(1)
        val lateRecoveryAction = AtomicReference<(() -> Unit)?>()
        val openingEntered = CountDownLatch(1)
        val resumeOpening = CountDownLatch(1)
        val openingReturned = CountDownLatch(1)
        val admissionReturned = CountDownLatch(1)
        val publicationEntered = CountDownLatch(1)
        val resumePublication = CountDownLatch(1)
        val recoveryCloseEntered = CountDownLatch(1)
        val resumeRecoveryClose = CountDownLatch(1)
        val recoveryCloseCalls = AtomicInteger()
        val leaseCloseEntered = CountDownLatch(1)
        val resumeLeaseClose = CountDownLatch(1)
        val terminalAcceptEntered = CountDownLatch(1)
        val resumeTerminalAccept = CountDownLatch(1)
        val fakeTime = AtomicLong(1_000)
        val callEntered = CountDownLatch(1)
        val allowCallReturn = CountDownLatch(1)
        val callOutcome = CountDownLatch(1)
        val resumeCallOutcome = CountDownLatch(1)
        val callReturned = CountDownLatch(1)
        val firstCallSnapshot = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstBoundedOutcome = java.util.concurrent.atomic.AtomicBoolean(true)
        val serviceFailures = java.util.Collections.synchronizedList(mutableListOf<WorkerFatalV2>())
        val drainScheduled = CountDownLatch(1)
        @Volatile var waitForDrainSchedule = false
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
        val profilesSelected = AtomicReference<Set<String>>()
        val snapshotReads = AtomicInteger()
        @Volatile var blockSecond = false
        val drain = SearchWorkerLocalDrain(1)
        val operation = drain.begin(1)!!
        val unbind = AtomicReference<((Boolean) -> Unit)?>()
        val originalUnbind = AtomicReference<((Boolean) -> Unit)?>()
        val originalDeath = AtomicReference<(() -> Unit)?>()
        val disconnect = AtomicReference<(() -> Unit)?>()
        val closeDuplicate = AtomicReference<(() -> Unit)?>()
        val attempt = AtomicReference<FakeAttempt>()
        val attemptStarted = CountDownLatch(1)
        private val actions = LinkedBlockingQueue<() -> Unit>()
        private val clock = { if (virtualClock) fakeTime.get() else TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
        lateinit var controller: SearchController
        lateinit var producer: SearchSessionProducer
        lateinit var composition: ResultActivationComposition
        lateinit var subscriptions: SubscriptionController
        val receipts = SearchReceiptState()
        val terminalIdentities = LinkedHashMap<Long, SearchLeaseReceiptV1>()
        private val terminalIdentityLock = Any()
        private val terminalReceiptLock = Any()
        var rejectProducerTerminal = false
        private val source = FakeSubscriptionPort { dispatch -> startSubscriptionIfSafe(controller, dispatch) }
        val storage = SubscriptionStorage(File(root, "subscriptions"), SnapshotFiles({ profilesSelected.set(it) }) { snapshotReads.incrementAndGet() }) { composition.currentRevision() }
        val revisions = FileRevisions(root)
        val results = PrivateResultStorage(File(root, "results")) { }

        init {
            producer = SearchSessionProducer(5_000, { snapshot, owner, deliver ->
                if (snapshot.sessionGeneration != 1L) {
                    if (blockSecond) { secondEntered.countDown(); check(releaseSecond.await(3, TimeUnit.SECONDS)) }
                    throw SearchNetworkPolicyUnavailable()
                }
                snapshot.profiles.open().use { it.readBytes() }
                val progress = SearchMobilePhaseProgress(clock, deliver)
                if (deliveryRace) {
                    val session = SearchMobilePhaseSession({}, {
                    check(owner.reserveWorker())
                    if (pausePriorControl) {
                        deliveryRunEntered.countDown()
                        check(resumeDeliveryRun.await(3, TimeUnit.SECONDS))
                    }
                    owner.workerDeathConfirmed()
                    "success"
                }, { true }, {
                    val bytes = "synthetic-result".toByteArray()
                    LocalOpenedResult(digest(bytes.inputStream()), object : PrivateInputV1 {
                        override fun open(): InputStream = bytes.inputStream()
                        override fun close(): Unit { resultInputCloses.incrementAndGet(); resultInputClosed.countDown() }
                    })
                }, {
                    resultSessionCloses.incrementAndGet()
                    resultSessionCloseEntered.countDown()
                    if (blockResultSessionClose) check(resumeResultSessionClose.await(3, TimeUnit.SECONDS))
                    if (failResultSessionClose) throw IOException("synthetic_session_close_failure")
                }, progress, {
                    if (failResultRetirement) {
                        resultRetirementEntered.countDown()
                        check(resumeResultRetirement.await(3, TimeUnit.SECONDS))
                        SearchResourceStateV2.QUARANTINED
                    } else SearchResourceStateV2.PAUSED_LEASE_HELD
                }, {}, owner)
                    if (!pausePriorControl) session else object : SearchPhaseSession by session {
                        override fun cancelPhase(epoch: Long): Boolean {
                            val result = session.cancelPhase(epoch)
                            priorControlReturned.countDown()
                            check(resumePriorControl.await(3, TimeUnit.SECONDS))
                            if (failPriorControl) throw IOException("synthetic_control_failure")
                            return result
                        }
                    }
                } else if (connectedMode) connectedSession(owner, progress) else SearchMobilePhaseSession({}, {
                    check(owner.reserveWorker())
                    val recoveryIntent = checkNotNull(producer.recoveryIntent(1))
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
                                { prepared.close(); true }, unbound).begin(attempt).also { if (it) pending.countDown() }
                        override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)
                        override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)
                        override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)
                        override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = admission.ownsRetirement(proof, identity)
                    }
                    val network = SearchNetworkV1(2, 3)
                    val actual = SearchWorkerBinderPort(stop, owner, prepared, identity, network, drain,
                        onPreconnectTimeout = {
                            startPreconnectRecovery(1, owner, drain, stop, ::schedule,
                                { confirmed, retry, failed -> FakeAttempt(::schedule, confirmed, retry, failed) { attemptStarted.countDown() }.also(attempt::set) }, producer, recoveryIntent)
                        }, provenFrameBound = 1024, provenReplyBound = 1024,
                        stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                            override fun descriptor(): ParcelFileDescriptor = error("no Binder transaction expected")
                            override fun close(): Unit = Unit
                        } }, nowMillis = openClock::get,
                        awaitBounded = { _, _ -> check(portWaiting.await(3, TimeUnit.SECONDS)); openClock.set(2_000); false },
                        recoveryIntent = recoveryIntent, onRecoveryFailure = producer::recoveryStartFailed)
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
            }, clock, reportRecoveryPending = { controller.recoveryPending(it) },
                beforePhaseOutcomeClaim = {
                    if (pausePhaseClaim == "before") {
                        phaseClaimEntered.countDown()
                        check(resumePhaseClaim.await(3, TimeUnit.SECONDS))
                    }
                }, afterPhaseOutcomeClaim = {
                    phaseOutcomeClaimed.countDown()
                    if (pausePhaseClaim == "after") {
                        phaseClaimEntered.countDown()
                        check(resumePhaseClaim.await(3, TimeUnit.SECONDS))
                    }
                }, afterRecoveryPhaseDone = { recoveryPhaseDone.countDown() }, beforeWatchSample = {
                    if (pauseWatch) {
                        watchSampleEntered.countDown()
                        check(resumeWatchSample.await(3, TimeUnit.SECONDS))
                    }
                }, onRecoveryWatchRetained = { recoveryWatchRetained.countDown() }, beforeWorkerRetiredPublication = {
                    if (pauseBeforeRetirementPublication) { retiredEntered.countDown(); check(resumeRetired.await(3, TimeUnit.SECONDS)) }
                }, afterWorkerRetired = {
                    if (pauseAfterRetirement) { retiredEntered.countDown(); check(resumeRetired.await(3, TimeUnit.SECONDS)) }
                }, beforeRetiredCancelWait = { retiredCancelWait.countDown() })
            val port = object : SearchSessionPortV4 {
                override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
                    if (snapshot.sessionGeneration == 1L) staleObserver.set(observer)
                    val receipt = if (productionTerminalGate) SearchLeaseReceiptV1.fromAcceptedSnapshot(
                        checkNotNull(receipts.accepted), snapshot, java.util.UUID(0, snapshot.sessionGeneration).toString(),
                    ) ?: error("synthetic receipt invalid") else null
                    val token = checkNotNull(processAdmission.reserve())
                    val lease = SearchOwnerLease(42, processAdmission, token, {
                        if (blockLeaseClose) {
                            leaseCloseEntered.countDown()
                            check(resumeLeaseClose.await(3, TimeUnit.SECONDS))
                        }
                        anchorCloses.incrementAndGet(); true
                    }, clock)
                    check(producer.offerLease(lease) == SearchLeaseHandoff.CLAIMED)
                    if (receipt != null) synchronized(terminalIdentityLock) {
                        terminalIdentities[snapshot.sessionGeneration] = receipt
                        synchronized(terminalReceiptLock) {
                            receipts.active = receipt
                            receipts.completed = null
                            receipts.restoredCompleted = null
                        }
                    }
                    val downstream = object : SearchSessionObserverV3 {
                        override fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit = observer.onProgress(phaseEpoch, progress)
                        override fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3 = observer.onResult(offer)
                        override fun onTerminal(terminal: SearchTerminalV3): Unit {
                            observer.onTerminal(terminal)
                            if (terminal.sessionGeneration == 1L) {
                                firstTerminal.countDown()
                                actions.offer({})
                            } else secondTerminal.countDown()
                        }
                    }
                    val started = producer.start(snapshot, phaseEpoch, downstream)
                    if (started != SearchStartV3.ACCEPTED && receipt != null) synchronized(terminalIdentityLock) {
                        terminalIdentities.remove(snapshot.sessionGeneration)
                        synchronized(terminalReceiptLock) { if (receipts.active == receipt) receipts.active = null }
                    }
                    return started
                }
                override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)
                override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)
                override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)
                override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
                    if (blockTerminalAcceptance && terminal.sessionGeneration == 1L) {
                        terminalAcceptEntered.countDown()
                        check(resumeTerminalAccept.await(3, TimeUnit.SECONDS))
                    }
                    return if (productionTerminalGate) acceptSearchTerminal(terminal, terminalIdentityLock, terminalIdentities,
                        terminalReceiptLock, receipts, { value -> !rejectProducerTerminal && producer.acceptTerminal(value) }, composition)
                    else producer.acceptTerminal(terminal)
                }
            }
            composition = ResultActivationComposition::class.java.getDeclaredConstructor(
                ResultStorage::class.java, RevisionStorage::class.java, SearchSessionPortV3::class.java,
                SessionMarker::class.java, VpnRunRegistry::class.java, SearchCompletionReceiptStorage::class.java,
            ).also { it.isAccessible = true }.newInstance(
                if (!deliveryRace) results else object : ResultStorage by results {
                    override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
                        val generation = results.prepare(revision, sha256, input)
                        resultPrepared.countDown()
                        check(resumeResultPrepare.await(3, TimeUnit.SECONDS))
                        return generation
                    }
                }, revisions, port, marker, VpnRunRegistry(),
                PrivateSearchCompletionReceiptStorage(File(root, "results"), directorySync = { }),
            )
            controller = composition.controller
            subscriptions = SubscriptionController(source, composition,
                commitSource = { storage.commitSource(it, 1) }, commitSelection = storage::commitSelection,
                independentSelection = { records, ids ->
                    val expected = checkNotNull(composition.currentRevision())
                    composition.withIndependentSelection(expected, { storage.prepareSelection(expected, records, ids) }, { receipts.invalidateForSelection() })
                }, verifyIndependentSelection = { ids ->
                    val revision = composition.currentRevision()
                    revision != null && storage.isCurrentSelection(revision, ids)
                }, independentProfiles = { revision, ids ->
                    val token = processAdmission.reserve()
                    if (token == null) null else prepareVerifiedSelection(revision, ids, storage, subscriptions,
                        { true }, { check(processAdmission.release(token)) }, { processAdmission.quarantine(token) }) { accepted ->
                        if (composition.currentRevision() != revision || subscriptions.state.value.generation != accepted.requestGeneration) false
                        else { receipts.accepted = accepted; true }
                    }
                })
        }

        private fun connectedSession(owner: SearchOwnerLeaseControl, progress: SearchMobilePhaseProgress): SearchPhaseSession {
            lateinit var adapter: SearchEngineWorkerAdapter
            val portTime = if (virtualClock) fakeTime else AtomicLong(clock())
            return SearchMobilePhaseSession({}, {
                check(owner.reserveWorker())
                val recoveryIntent = checkNotNull(producer.recoveryIntent(1))
                val identity = WorkerIdentityV1(1, 2uL, 1, 3)
                val network = SearchNetworkV1(2, 3)
                val preparedRef = AtomicReference<SearchLeaseDuplicateV1?>()
                val lease = object : CoordinatorLease {
                    override fun duplicate(): SearchLeaseDuplicateV1 = checkNotNull(preparedRef.get())
                    override fun anchorHeldForWorker(): Boolean = true
                    override fun sealForRecovery(): Boolean = owner.sealForRecovery()
                    override fun workerDeathConfirmed(): Unit = owner.workerDeathConfirmed()
                    override fun quarantine(): Unit = owner.quarantine()
                }
                val handler = AtomicReference<(() -> Boolean)?>()
                val admittingScenario = openingRace == "admitting" || openingRace == "admittingTimeout"
                val handshakeFinished = CountDownLatch(1)
                val supervisedStop = java.util.concurrent.atomic.AtomicBoolean(false)
                val admission = CoordinatorAdmission(lease, identity, network, RoundOwner { true }) {
                    if (callRace == "deathBeforeBinder" || callRace == "deathAfterBinder" || openingRace == "confirmationDeath") {
                        deathHandlerEntered.countDown()
                        check(resumeDeathHandler.await(3, TimeUnit.SECONDS))
                    }
                    handler.get()?.invoke() == true
                }
                val remote = object : CoordinatorRemote {
                    override fun linkDeath(onDeath: () -> Unit): Boolean { originalDeath.set(onDeath); return true }
                    override fun isAlive(): Boolean = true
                    override fun admit(identity: WorkerIdentityV1, network: SearchNetworkV1, duplicate: SearchLeaseDuplicateV1): Boolean {
                        val prepared = duplicate as PreparedWorkerLeaseDuplicate
                        checkNotNull(prepared.beginTransfer())
                        try {
                            if (admittingScenario) {
                                openingEntered.countDown()
                                check(resumeOpening.await(3, TimeUnit.SECONDS))
                                throw WorkerRemoteDeliveryFailure(android.os.DeadObjectException())
                            }
                            return true
                        } finally { prepared.endTransfer() }
                    }
                    override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean = true
                    override fun requestUnbind(completed: (Boolean) -> Unit): Boolean { originalUnbind.set(completed); return true }
                }
                val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
                    when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
                } as IBinder
                val prepared = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
                check(prepared.beginBind())
                preparedRef.set(prepared)
                val stop = object : WorkerCoordinatorPort {
                    override fun registerConnectedRecovery(recover: () -> Boolean): Boolean { handler.set(recover); return true }
                    override fun bind(): Boolean {
                        if (admittingScenario) {
                            Thread({
                                try { admission.connect(remote) }
                                finally { handshakeFinished.countDown(); admissionReturned.countDown() }
                            }, "synthetic-admission").start()
                            return true
                        }
                        val accepted = admission.connect(remote)
                        if (accepted && openingRace == "publication") {
                            publicationEntered.countDown()
                            check(resumePublication.await(3, TimeUnit.SECONDS))
                        }
                        return accepted
                    }
                    override fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait {
                        if (admittingScenario) {
                            val remaining = deadlineElapsedMillis - portTime.get()
                            if (remaining <= 0 || !handshakeFinished.await(remaining, TimeUnit.MILLISECONDS)) return WorkerConfirmationWait.Deadline
                            if (supervisedStop.get()) return WorkerConfirmationWait.SupervisedStop
                            return workerConfirmation(observeBinder())
                        }
                        if (openingRace == "confirmationDeath") {
                            openingEntered.countDown()
                            check(resumeOpening.await(3, TimeUnit.SECONDS))
                            return workerConfirmation(observeBinder())
                        }
                        return WorkerConfirmationWait.Confirmed(binder)
                    }
                    override fun confirmedBinder(): IBinder? = if (admission.state() == CoordinatorState.CONFIRMED) binder else null
                    override fun observeBinder(): WorkerBinderObservation = observeAdmittedWorker(binder, admission::state) {
                        if (callRace == "notAlive" && connectedReady.count == 0L) WorkerBinderValidation.NOT_ALIVE else WorkerBinderValidation.VALID
                    }
                    override fun awaitRetired(deadlineElapsedMillis: Long): Boolean = false
                    override fun quarantine(): Unit = admission.disconnected()
                    override fun stop(): Unit = admission.stop()
                    override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean {
                        if (!admission.awaitLinkedRemote(remote)) return false
                        if (!supervisorStopAllowed(false, false, true, true, true, true, admission.hasLinkedRemote(remote), admission.state())) return false
                        supervisedStop.set(true)
                        handshakeFinished.countDown()
                        return SupervisedCoordinatorStop(admission, { unbind.set(it); true }, { closeDuplicate.set(it); true },
                            { prepared.close(); true }, unbound).begin(attempt).also { if (it) pending.countDown() }
                    }
                    override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)
                    override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)
                    override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)
                    override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = admission.ownsRetirement(proof, identity)
                }
                val actual = SearchWorkerBinderPort(stop, owner, prepared, identity, network, drain,
                    onPreconnectTimeout = {
                        if (pauseRecoveryStart) {
                            recoveryStartEntered.countDown()
                            check(resumeRecoveryStart.await(3, TimeUnit.SECONDS))
                        }
                        startPreconnectRecovery(1, owner, drain, stop, ::schedule,
                            { confirmed, retry, failed -> FakeAttempt(::schedule, confirmed, retry, failed) { attemptStarted.countDown() }.also(attempt::set) }, producer, recoveryIntent)
                    }, provenFrameBound = 1024, provenReplyBound = 1024,
                    stageFrameOverride = { _, _ ->
                        if (callRace == "deathBeforeBinder" && connectedReady.count == 0L) {
                            callFrameEntered.countDown()
                            check(resumeCallFrame.await(3, TimeUnit.SECONDS))
                        }
                        object : StagedWorkerFrame {
                        override fun descriptor(): ParcelFileDescriptor = error("fake transaction has no FD")
                        override fun close(): Unit {
                            if (openingCloseFailure && connectedReady.count != 0L) throw IOException("synthetic_open_fd_close_failure")
                        }
                    } }, statusTransactionOverride = { _, code, _ ->
                        if (openingRace == "during" && code == SearchWorkerIpcV2.OPEN_FD) {
                            openingEntered.countDown()
                            check(resumeOpening.await(3, TimeUnit.SECONDS))
                            throw android.os.DeadObjectException()
                        }
                        if (automaticCancelFailure && code == SearchWorkerIpcV2.CANCEL_ROUND) throw android.os.DeadObjectException()
                        if (automaticCancelTimeout && code == SearchWorkerIpcV2.CANCEL_ROUND) {
                            cancelTransactionEntered.countDown()
                            try { check(releaseCancelTransaction.await(3, TimeUnit.SECONDS)) }
                            finally { cancelTransactionReturned.countDown() }
                        }
                        com.seraf.vpnsweep.core.mobile.Mobile.SearchWorkerReady.toInt()
                    }, reportServiceFailure = { serviceFailures.add(it) }, nowMillis = portTime::get, awaitBounded = { done, millis ->
                        if (openingRace == "admittingTimeout") {
                            check(openingEntered.await(3, TimeUnit.SECONDS))
                            portTime.addAndGet(millis + 1)
                            false
                        } else if (automaticCancelTimeout && connectedReady.count == 0L) {
                            check(cancelTransactionEntered.await(3, TimeUnit.SECONDS))
                            portTime.addAndGet(millis + 1)
                            false
                        } else done.await(millis, TimeUnit.MILLISECONDS)
                    }, callTransactionOverride = { _, _, _, ticket ->
                        val child = checkNotNull(ticket.retain())
                        trackedWorkerResource(child, { Any() }, { if (callRace == "close") throw IOException("synthetic_local_close_failure") }) {
                            callEntered.countDown()
                            if (callRace == "before") throw IOException("synthetic_call_failure")
                            if (callRace == "remoteBefore") throw android.os.DeadObjectException()
                            check(allowCallReturn.await(3, TimeUnit.SECONDS))
                            if (callRace == "remoteAfter") throw android.os.DeadObjectException()
                            WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
                        }
                    }, afterCallSnapshot = {
                        if (callRace != null && firstCallSnapshot.compareAndSet(true, false)) {
                            callSnapshot.countDown()
                            check(resumeCallSnapshot.await(3, TimeUnit.SECONDS))
                        }
                    }, afterCallOutcome = {
                        if (callRace != null) callOutcome.countDown()
                        if (callRace == "outcome") check(resumeCallOutcome.await(3, TimeUnit.SECONDS))
                    }, afterCallBinder = {
                        if (callRace == "dispatch" || callRace == "deathAfterBinder") {
                            callBinderCaptured.countDown()
                            check(resumeCallBinder.await(3, TimeUnit.SECONDS))
                        }
                    }, afterOpenBinder = {
                        if (openingRace == "before") {
                            openingEntered.countDown()
                            check(resumeOpening.await(3, TimeUnit.SECONDS))
                        }
                    }, afterBoundedOutcome = {
                        if ((openingRace == "bookkeeping" || openingRace == "bookkeepingFailure") && firstBoundedOutcome.compareAndSet(true, false)) {
                            openingEntered.countDown()
                            check(resumeOpening.await(3, TimeUnit.SECONDS))
                            if (openingRace == "bookkeepingFailure") throw IOException("synthetic_bounded_bookkeeping_failure")
                        }
                    }, beforeRecoveryWait = { recoveryWaitEntered.countDown() }, recoveryIntent = recoveryIntent,
                    onRecoveryFailure = producer::recoveryStartFailed,
                    scheduleRecoveryStart = { action ->
                        if (rejectRecoverySchedule) { lateRecoveryAction.set(action); false }
                        else try { Thread(action, "synthetic-recovery-start").apply { isDaemon = true }.start(); true }
                        catch (_: Exception) { false }
                    })
                disconnect.set { recoverConnectedDisconnect({ handler.get()?.invoke() == true }, admission::disconnected) }
                adapter = SearchEngineWorkerAdapter(1, network, 5_000, 1024, { WorkerRoundClient(actual, prepared) }, {},
                    nowNanos = { if (openingRace == "admittingTimeout") portTime.get() * 1_000_000 else System.nanoTime() }, localDrain = drain)
                val openResult = try { adapter.open(1, 2, 1, 3, byteArrayOf(1), byteArrayOf(2), 5_000, 5_000) }
                finally { openingReturned.countDown() }
                if (openingRace != null) {
                    check(returnRun.await(3, TimeUnit.SECONDS))
                    throw IOException("synthetic_open_cancelled")
                }
                check(openResult == com.seraf.vpnsweep.core.mobile.Mobile.SearchWorkerReady)
                connectedReady.countDown()
                if (automaticCancelFailure || automaticCancelTimeout) {
                    check(triggerAutomaticCancel.await(3, TimeUnit.SECONDS))
                    adapter.cancel(3)
                }
                if (callRace != null) {
                    check(startCall.await(3, TimeUnit.SECONDS))
                    try { adapter.call(3, com.seraf.vpnsweep.core.mobile.Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000) }
                    finally { callReturned.countDown() }
                }
                check(returnRun.await(3, TimeUnit.SECONDS))
                if (pausePhaseClaim == null && !runFailureBeforeAdapterClose) adapter.close(3, 5_000)
                throw IOException("synthetic_cancelled")
            }, { true }, { error("no result") }, {
                if (blockRecoveryClose || failRecoveryClose) {
                    recoveryCloseCalls.incrementAndGet()
                    recoveryCloseEntered.countDown()
                    check(resumeRecoveryClose.await(3, TimeUnit.SECONDS))
                    if (failRecoveryClose) throw IOException("synthetic_session_close_failure")
                }
            }, progress, { SearchResourceStateV2.PAUSED_LEASE_HELD }, {}, owner,
                connectedCancelRecovery = { adapter.beginConnectedCancelRecovery() },
                failedRunRecovery = { if (pausePhaseClaim != null) false else adapter.beginConnectedCancelRecovery() })
        }

        fun installSelection(): Unit {
            assertEquals(SubscriptionStartV1.ACCEPTED, subscriptions.load("synthetic"))
            source.observer!!.onParsed(1, SubscriptionBatchStatusV1.HAS_SUPPORTED, Snapshot { profilesSelected.set(it) })
            val revision = checkNotNull(composition.currentRevision())
            receipts.accepted = AcceptedSubscriptionReceiptV1(1, revision.source, revision.selection)
            assertTrue(subscriptions.select(setOf(ID_A)))
        }

        private fun selectionOwner(): AutoCloseable? {
            val token = processAdmission.reserve() ?: return null
            return AutoCloseable { check(processAdmission.release(token)) }
        }

        @Synchronized private fun schedule(delay: Long, action: () -> Unit): Boolean {
            if (delay == 0L) {
                actions.add(action)
                if (waitForDrainSchedule) drainScheduled.countDown()
                if (automaticCancelTimeout && cancelTransactionReturned.count == 0L && returnRun.count != 0L) scheduledAfterCancel.countDown()
            }
            return true
        }
        fun pump(): Unit { while (true) { val action = actions.poll() ?: return; action() } }
        fun pumpUntilFirstTerminal(): Unit {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (firstTerminal.count != 0L) {
                val left = deadline - System.nanoTime()
                check(left > 0)
                checkNotNull(actions.poll(left, TimeUnit.NANOSECONDS)).invoke()
            }
        }
        fun pumpUntilAttemptStarted(): Unit {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (attemptStarted.count != 0L) {
                val left = deadline - System.nanoTime()
                check(left > 0)
                checkNotNull(actions.poll(left, TimeUnit.NANOSECONDS)).invoke()
            }
        }
    }

    private class FakeAttempt(
        schedule: (Long, () -> Unit) -> Boolean,
        confirmed: (SupervisorDeathProof) -> Unit,
        retry: () -> Unit,
        failed: () -> Unit,
        private val started: () -> Unit,
    ) : SupervisorAttemptPort {
        private var death: (() -> Unit)? = null
        private val binding = SupervisorBindingAttempt({ schedule(0, it) }, { _, callback -> death = callback; started(); true }, { true }, confirmed, retry, failed)
        override val identity: Any get() = binding.identity
        override fun start(): SupervisorStartResult { binding.bindReturned(true); binding.connected(Any()); return SupervisorStartResult.STARTED }
        override fun connectionTimedOut(): Unit = binding.connectionTimedOut()
        fun die(): Unit = checkNotNull(death).invoke()
    }

    private class Input : PrivateInputV1 {
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override fun close(): Unit = Unit
    }
    private class Snapshot(private val onSelected: (Set<String>) -> Unit) : SubscriptionSnapshotV1 {
        override val records = listOf(
            SubscriptionRecordV1(ID_A, "A", "", true, null),
            SubscriptionRecordV1(ID_B, "B", "", true, null),
        )
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1 { onSelected(ids); return Input() }
        override fun close(): Unit = Unit
    }
    private class SnapshotFiles(private val onSelected: (Set<String>) -> Unit, private val onRead: () -> Unit = {}) : SubscriptionSnapshotFiles {
        override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit {
            val payload = ByteArrayOutputStream()
            payload.write(1)
            for (record in snapshot.records) {
                payload.write(record.id.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                payload.write(if (record.supported) 1 else 0)
                payload.write(ByteArray(32))
            }
            val bytes = payload.toByteArray()
            DataOutputStream(file.outputStream()).use { output ->
                output.writeInt(0x53565331)
                output.writeInt(1)
                output.writeInt(snapshot.records.size)
                output.write(MessageDigest.getInstance("SHA-256").digest(bytes))
                output.write(bytes)
            }
        }
        override fun read(file: File): SubscriptionSnapshotV1 { onRead(); return Snapshot(onSelected) }
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
        var failAfterRename = false
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
            if (failAfterRename) throw IOException("synthetic_directory_sync_failure")
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
