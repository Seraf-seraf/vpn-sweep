package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchstate.*
import com.seraf.vpnsweep.searchworker.SupervisorTerminationEvidence
import com.seraf.vpnsweep.searchstorage.*
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionSnapshotFiles
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpgradeRecoveryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun supervisedRecoveryReissuesRealSnapshotAndLeavesOldReceiptInactive(): Unit {
        val root = temporary.newFolder()
        val revisions = Revisions()
        val snapshots = SubscriptionStorage(File(root, "snapshots"), SnapshotFiles()) { revisions.value }
        revisions.value = snapshots.commitSource(Snapshot(), 7)
        revisions.value = snapshots.commitSelection(setOf(SERVER_ID))
        val original = checkNotNull(revisions.value)
        val resultRoot = File(root, "results")
        val results = PrivateResultStorage(resultRoot) { }
        val bytes = "synthetic-result".toByteArray()
        val sha = digest(bytes.inputStream())
        val generation = results.prepare(original, sha, bytes.inputStream())
        val manifest = ResultManifest(generation, original, sha)
        results.publish(manifest)
        val receipts = PrivateSearchCompletionReceiptStorage(resultRoot, directorySync = {})
        val receipt = SearchCompletionReceiptV1.cleanComplete(
            SearchLeaseReceiptV1(7, 1, original.source.toLong(), original.selection.toLong(), LEASE_ID), manifest,
        )
        assertTrue(receipts.write(receipt))
        val intent = RecoveryIntentStore(File(root, "fence")) { }
        val raw = RawMarker()
        val backend = Backend(intent, raw, FIRST_BOOT)
        val composition = composition(results, revisions, RecoveryFencedMarker(raw, backend), receipts)
        backend.reconcileAction = { composition.reissueForUpgrade(snapshots::reissueCurrent) != null }
        val recovery = SupervisedStartupRecovery(backend, raw::markActive, intent::prepareSupervisorRecovery,
            intent::finishSupervisorRecovery, { SupervisorOwner.Acquired(backend.acquireOwner()) })
        assertTrue(recovery.prepare())
        val identity = Any()
        assertTrue(recovery.expectSupervisor(identity))
        var outcome: SupervisorRecoveryResult? = null
        val proof = SupervisorTerminationEvidence(identity) { outcome = recovery.complete(it) }
        assertTrue(proof.beginLink())
        proof.linkResult(true)
        proof.unbindResult(true)
        assertNull(outcome)
        assertEquals(original, revisions.value)
        proof.binderDied()
        assertEquals(SupervisorRecoveryResult.RECOVERED, outcome)
        assertTrue(backend.ownerClosed)
        assertEquals(RecoveryIntentState.Done, intent.read())
        assertEquals(MarkerState.CLEAR, raw.read())
        val revised = checkNotNull(revisions.value)
        assertNotEquals(original, revised)
        val restored = checkNotNull(snapshots.recover(revised))
        assertEquals(setOf(SERVER_ID), restored.selectedIds)
        restored.snapshot.close()
        assertEquals(receipt, receipts.read())
        assertEquals(manifest, results.readManifest())
        assertNull(composition.loadCleanComplete(AcceptedSubscriptionReceiptV1(7, revised.source, revised.selection)))
    }

    @Test fun receiptGenerationAndCurrentManifestAreIndependentlyVerified(): Unit {
        for (fault in listOf("healthy", "old-payload", "missing-old-payload", "old-metadata", "previous-payload", "missing-current")) {
            val root = temporary.newFolder()
            val revisions = Revisions()
            val snapshots = SubscriptionStorage(File(root, "snapshots"), SnapshotFiles()) { revisions.value }
            revisions.value = snapshots.commitSource(Snapshot(), 7)
            val original = checkNotNull(revisions.value)
            val resultRoot = File(root, "results")
            val results = PrivateResultStorage(resultRoot) { }
            val bytesA = "synthetic-a".toByteArray()
            val shaA = digest(bytesA.inputStream())
            val generationA = results.prepare(original, shaA, bytesA.inputStream())
            val manifestA = ResultManifest(generationA, original, shaA)
            results.publish(manifestA)
            val receipts = PrivateSearchCompletionReceiptStorage(resultRoot, directorySync = {})
            val receiptA = SearchCompletionReceiptV1.cleanComplete(
                SearchLeaseReceiptV1(7, 1, original.source.toLong(), original.selection.toLong(), LEASE_ID), manifestA,
            )
            assertTrue(receipts.write(receiptA))
            val bytesB = "synthetic-b".toByteArray()
            val shaB = digest(bytesB.inputStream())
            val generationB = results.prepare(original, shaB, bytesB.inputStream())
            val manifestB = ResultManifest(generationB, original, shaB)
            results.publish(manifestB)
            if (fault == "previous-payload") {
                assertTrue(receipts.write(SearchCompletionReceiptV1.cleanComplete(receiptA.search, manifestB)))
            }
            when (fault) {
                "old-payload" -> File(resultRoot, "$generationA/result").writeText("corrupt")
                "missing-old-payload" -> assertTrue(File(resultRoot, "$generationA/result").delete())
                "old-metadata" -> assertTrue(File(resultRoot, "$generationA/metadata").delete())
                "previous-payload" -> File(resultRoot, "$generationA/result").writeText("corrupt")
                "missing-current" -> assertTrue(File(resultRoot, "current").delete())
            }
            val intent = RecoveryIntentStore(File(root, "fence")) { }
            val raw = RawMarker()
            val before = UpgradeRecovery(Backend(intent, raw, FIRST_BOOT), eligibleAtStartup = true)
            assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, before.arm())
            val backend = Backend(intent, raw, SECOND_BOOT)
            val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
            val composition = composition(results, revisions, RecoveryFencedMarker(raw, backend), receipts)
            backend.reconcileAction = { composition.reissueForUpgrade(snapshots::reissueCurrent) != null }
            val outcome = recovery.recover()
            if (fault == "healthy") {
                assertEquals(UpgradeRecoveryResult.RECOVERED, outcome)
                assertNotEquals(original, revisions.value)
            } else {
                assertEquals(fault, UpgradeRecoveryResult.UNKNOWN, outcome)
                assertEquals(fault, original, revisions.value)
                assertEquals(fault, MarkerState.ACTIVE, raw.read())
                assertTrue(fault, recovery.blocked())
            }
        }
    }

    @Test fun rebootRecoveryClonesRealSnapshotAndInvalidatesRealResultAndReceipt(): Unit {
        val revisions = Revisions()
        val snapshots = SubscriptionStorage(File(temporary.root, "snapshots"), SnapshotFiles()) { revisions.value }
        revisions.value = snapshots.commitSource(Snapshot(), 7)
        revisions.value = snapshots.commitSelection(setOf(SERVER_ID))
        val originalRevision = checkNotNull(revisions.value)
        val resultRoot = File(temporary.root, "results")
        val results = PrivateResultStorage(resultRoot) { }
        val bytes = "synthetic-result".toByteArray()
        val sha = digest(bytes.inputStream())
        val generation = results.prepare(originalRevision, sha, bytes.inputStream())
        val manifest = ResultManifest(generation, originalRevision, sha)
        results.publish(manifest)
        val receipt = SearchCompletionReceiptV1.cleanComplete(
            SearchLeaseReceiptV1(7, 1, originalRevision.source.toLong(), originalRevision.selection.toLong(), LEASE_ID), manifest,
        )
        val receipts = PrivateSearchCompletionReceiptStorage(resultRoot, directorySync = {})
        assertTrue(receipts.write(receipt))

        val intent = RecoveryIntentStore(File(temporary.root, "fence")) { }
        val raw = RawMarker()
        val beforeBoot = Backend(intent, raw, FIRST_BOOT)
        val first = UpgradeRecovery(beforeBoot, eligibleAtStartup = true)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, first.arm())

        val afterBoot = Backend(intent, raw, SECOND_BOOT)
        val recovery = UpgradeRecovery(afterBoot, eligibleAtStartup = true)
        val marker = RecoveryFencedMarker(raw, afterBoot)
        val composition = composition(results, revisions, marker, receipts)
        var restoredSelection: Set<String>? = null
        afterBoot.reconcileAction = {
            val next = composition.reissueForUpgrade(snapshots::reissueCurrent)
            if (next == null) false else {
                val recovered = snapshots.recover(next)
                if (recovered == null) false else {
                    restoredSelection = recovered.selectedIds
                    val generationMatches = recovered.requestGeneration == 7L
                    recovered.snapshot.close()
                    generationMatches
                }
            }
        }
        assertEquals(MarkerState.ACTIVE, marker.read())
        assertEquals(UpgradeRecoveryResult.RECOVERED, recovery.recover())
        assertTrue(composition.controller.completeUpgradeRecovery())
        val revised = checkNotNull(revisions.value)
        assertNotEquals(originalRevision, revised)
        assertEquals(setOf(SERVER_ID), restoredSelection)
        assertTrue(afterBoot.ownerClosed)
        assertEquals(RecoveryIntentState.Done, intent.read())
        assertEquals(MarkerState.CLEAR, raw.read())
        assertEquals(manifest, results.readManifest())
        assertEquals(receipt, receipts.read())
        assertNull(composition.loadCleanComplete(AcceptedSubscriptionReceiptV1(7, revised.source, revised.selection)))
        assertFalse(composition.activation.ready())
        assertTrue(raw.markActive())
        val laterBackend = Backend(intent, raw, SECOND_BOOT)
        val later = UpgradeRecovery(laterBackend, eligibleAtStartup = raw.read() != MarkerState.CLEAR)
        assertTrue(later.blocked())
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, later.arm())
        assertEquals(RecoveryIntentState.Armed(RecoveryStamp(SECOND_BOOT)), intent.read())
    }

    @Test fun restartAfterRevisionBeforeMarkerClearReissuesFromRealStorageAgain(): Unit {
        val root = temporary.newFolder()
        val revisions = Revisions()
        val snapshots = SubscriptionStorage(File(root, "snapshots"), SnapshotFiles()) { revisions.value }
        revisions.value = snapshots.commitSource(Snapshot(), 7)
        revisions.value = snapshots.commitSelection(setOf(SERVER_ID))
        val original = checkNotNull(revisions.value)
        val resultRoot = File(root, "results")
        val results = PrivateResultStorage(resultRoot) { }
        val bytes = "synthetic".toByteArray()
        val sha = digest(bytes.inputStream())
        val generation = results.prepare(original, sha, bytes.inputStream())
        val manifest = ResultManifest(generation, original, sha)
        results.publish(manifest)
        val receipts = PrivateSearchCompletionReceiptStorage(resultRoot, directorySync = {})
        val receipt = SearchCompletionReceiptV1.cleanComplete(
            SearchLeaseReceiptV1(7, 1, original.source.toLong(), original.selection.toLong(), LEASE_ID), manifest,
        )
        assertTrue(receipts.write(receipt))
        val intentDir = File(root, "fence")
        val raw = RawMarker()
        val first = UpgradeRecovery(Backend(RecoveryIntentStore(intentDir) { }, raw, FIRST_BOOT), true)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, first.arm())
        val backend = Backend(RecoveryIntentStore(intentDir) { }, raw, SECOND_BOOT)
        val attempt = UpgradeRecovery(backend, true)
        val composition = composition(results, revisions, RecoveryFencedMarker(raw, backend), receipts)
        backend.reconcileAction = { composition.reissueForUpgrade(snapshots::reissueCurrent) != null }
        raw.failClear = true
        assertEquals(UpgradeRecoveryResult.UNKNOWN, attempt.recover())
        val firstRevision = checkNotNull(revisions.value)
        assertNotEquals(original, firstRevision)
        assertEquals(MarkerState.ACTIVE, raw.read())
        assertTrue(RecoveryIntentStore(intentDir) { }.read() is RecoveryIntentState.Armed)

        raw.failClear = false
        val restartedBackend = Backend(RecoveryIntentStore(intentDir) { }, raw, SECOND_BOOT)
        val restarted = UpgradeRecovery(restartedBackend, true)
        val restartedComposition = composition(results, revisions, RecoveryFencedMarker(raw, restartedBackend), receipts)
        restartedBackend.reconcileAction = { restartedComposition.reissueForUpgrade(snapshots::reissueCurrent) != null }
        assertEquals(UpgradeRecoveryResult.RECOVERED, restarted.recover())
        assertTrue(restartedComposition.controller.completeUpgradeRecovery())
        val finalRevision = checkNotNull(revisions.value)
        assertNotEquals(firstRevision, finalRevision)
        val restored = checkNotNull(snapshots.recover(finalRevision))
        assertEquals(setOf(SERVER_ID), restored.selectedIds)
        restored.snapshot.close()
        assertEquals(receipt, receipts.read())
        assertEquals(manifest, results.readManifest())
        assertFalse(restartedComposition.activation.ready())
    }

    private fun composition(results: ResultStorage, revisions: RevisionStorage, marker: SessionMarker, receipts: SearchCompletionReceiptStorage): ResultActivationComposition {
        val constructor = ResultActivationComposition::class.java.getDeclaredConstructor(
            ResultStorage::class.java, RevisionStorage::class.java, SearchSessionPortV3::class.java,
            SessionMarker::class.java, VpnRunRegistry::class.java, SearchCompletionReceiptStorage::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(results, revisions, Port(), marker, VpnRunRegistry(), receipts)
    }

    private class Revisions : RevisionStorage {
        var value: ResultRevision? = null
        override fun read(): ResultRevision? = value
        override fun write(revision: ResultRevision): Unit { value = revision }
    }

    private class Snapshot : SubscriptionSnapshotV1 {
        override val records = listOf(SubscriptionRecordV1(SERVER_ID, "synthetic", "", true, null))
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1? = null
        override fun close(): Unit = Unit
    }

    private class SnapshotFiles : SubscriptionSnapshotFiles {
        override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit { file.writeText("synthetic-snapshot") }
        override fun read(file: File): SubscriptionSnapshotV1 { check(file.readText() == "synthetic-snapshot"); return Snapshot() }
        override fun syncDirectory(directory: File): Unit = Unit
    }

    private class RawMarker : SessionMarker {
        private var value = MarkerState.ACTIVE
        var failClear = false
        override fun read(): MarkerState = value
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { if (failClear) return false; value = MarkerState.CLEAR; return true }
    }

    private class Backend(private val intent: RecoveryIntentStore, private val raw: RawMarker, private val boot: String) : UpgradeRecoveryBackend {
        override var recoveryBlocked = false
        override var quarantined = false
        var ownerClosed = false
        var reconcileAction: () -> Boolean = { false }
        override fun readIntent(): RecoveryIntentState = intent.read()
        override fun writeIntent(stamp: RecoveryStamp): Boolean = intent.create(stamp)
        override fun finishIntent(): Boolean = intent.finish()
        override fun readStamp(): RecoveryStamp = RecoveryStamp(boot)
        override fun readRawMarker(): MarkerState = raw.read()
        override fun clearRawMarker(): Boolean = raw.markClear()
        override fun acquireOwner(): RecoveryOwner = object : RecoveryOwner { override fun close(): Unit { ownerClosed = true } }
        override fun reconcile(): Boolean = reconcileAction()
    }

    private class Port : SearchSessionPortV3 {
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 = SearchStartV3.REJECTED
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.REJECTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
    }

    private companion object {
        const val SERVER_ID = "00000000000000000000000000000001"
        const val LEASE_ID = "00000000-0000-0000-0000-000000000021"
        const val FIRST_BOOT = "11111111-1111-4111-8111-111111111111"
        const val SECOND_BOOT = "22222222-2222-4222-8222-222222222222"
    }
}
