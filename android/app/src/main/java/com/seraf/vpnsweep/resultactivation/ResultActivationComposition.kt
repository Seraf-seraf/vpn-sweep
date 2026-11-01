package com.seraf.vpnsweep.resultactivation

import android.content.Context
import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.CurrentRevisionStore
import com.seraf.vpnsweep.searchstorage.CurrentRevision
import com.seraf.vpnsweep.searchstorage.PrivateResultStorage
import com.seraf.vpnsweep.searchstorage.PrivateRevisionStorage
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.ResultStore
import com.seraf.vpnsweep.searchstorage.RevisionStorage
import com.seraf.vpnsweep.searchstorage.RevisionCommit
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import java.io.File

internal class ResultActivationComposition private constructor(
    private val storage: ResultStorage,
    private val revisionStorage: RevisionStorage,
    port: SearchSessionPortV3,
    private val marker: SessionMarker,
    val vpnRuns: VpnRunRegistry,
    private val completionReceipts: SearchCompletionReceiptStorage,
) {
    private val store = ResultStore(storage)
    private val revisions = CurrentRevisionStore(revisionStorage)
    val controller = SearchController(port, store, marker)
    val activation = ResultActivation(controller, storage, revisions, marker, vpnRuns::available, completionReceipts)

    fun currentRevision(): ResultRevision? = synchronized(controller) { revisions.load() }

    fun reissueForUpgrade(reissue: (ResultRevision) -> ResultRevision): ResultRevision? = synchronized(controller) {
        val previous = revisions.load() ?: return@synchronized null
        try {
            fun safeReceipts(candidate: ResultRevision?): Boolean = completionReceipts.safeForUpgrade(candidate) {
                storage.hasVerifiedManifestForUpgrade() && storage.verifiedGenerationForUpgrade(it.result)
            }
            if (!safeReceipts(null) || !storage.safeForUpgrade(null)) return@synchronized null
            val candidate = reissue(previous)
            if (candidate == previous || !safeReceipts(candidate) || !storage.safeForUpgrade(candidate)) return@synchronized null
            if (!revisions.update(candidate) || revisions.load() != candidate) return@synchronized null
            candidate
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
    }

    fun prepareCleanComplete(
        accepted: AcceptedSubscriptionReceiptV1,
        searched: SearchLeaseReceiptV1,
    ): Boolean = synchronized(controller) {
        val revision = currentRevisionOrNull() ?: return@synchronized false
        val manifest = try { store.resolve(revision) } catch (_: Exception) { null } ?: return@synchronized false
        val receipt = SearchCompletionReceiptV1.cleanComplete(searched, manifest)
        if (!receipt.matchesAccepted(accepted) || !receipt.matchesCurrent(revision, manifest)) return@synchronized false
        try { completionReceipts.prepare(receipt) } catch (_: Exception) { false }
    }

    fun commitCleanComplete(): Boolean = synchronized(controller) {
        val pending = try { completionReceipts.hasPending() } catch (_: Exception) { true }
        if (markerStateOrNull() != MarkerState.CLEAR || !pending) return@synchronized false
        val revision = currentRevisionOrNull() ?: return@synchronized false
        val receipt = try { completionReceipts.readPrepared() } catch (_: Exception) { null } ?: return@synchronized false
        if (!receipt.matchesCurrentRevision(revision) || verifiedCompletionBytes(storage, receipt, revision) == null) {
            return@synchronized false
        }
        try { completionReceipts.commit() } catch (_: Exception) { false }
    }

    fun recordCleanComplete(
        accepted: AcceptedSubscriptionReceiptV1,
        searched: SearchLeaseReceiptV1,
    ): Boolean = prepareCleanComplete(accepted, searched) && commitCleanComplete()

    fun loadCleanComplete(accepted: AcceptedSubscriptionReceiptV1): SearchLeaseReceiptV1? = synchronized(controller) {
        if (markerStateOrNull() != MarkerState.CLEAR) return@synchronized null
        if (try { completionReceipts.hasPending() } catch (_: Exception) { true }) return@synchronized null
        val revision = currentRevisionOrNull() ?: return@synchronized null
        val receipt = try { completionReceipts.read() } catch (_: Exception) { null } ?: return@synchronized null
        if (!receipt.matchesAccepted(accepted) || !receipt.matchesCurrentRevision(revision) ||
            verifiedCompletionBytes(storage, receipt, revision) == null
        ) return@synchronized null
        receipt.search
    }

    fun withSourceMutation(beforeClear: () -> Unit = {}, mutation: () -> ResultRevision): Boolean = activation.withSourceMutation {
        check(marker.markActive() && marker.read() == MarkerState.ACTIVE) { "marker_active_unknown" }
        val previous = revisionStorage.read()
        val published = storage.readManifest()?.revision
        check(previous == null || (previous.source.isNotEmpty() && previous.selection.isNotEmpty())) { "revision_read_invalid" }
        check(published == null || (published.source.isNotEmpty() && published.selection.isNotEmpty())) { "manifest_revision_invalid" }
        // Return a fresh revision only after its snapshot is durable.
        val revision = mutation()
        require(revision.source.isNotEmpty() && revision.selection.isNotEmpty())
        check(revision != previous && revision != published) { "revision_reused" }
        check(revisions.update(revision)) { "revision_update_failed" }
        beforeClear()
        check(marker.markClear() && marker.read() == MarkerState.CLEAR) { "marker_clear_unknown" }
        true
    }

    fun withOwnedSourceMutation(acquire: () -> AutoCloseable?, mutation: () -> ResultRevision): Boolean {
        val owner = acquire() ?: return false
        var closeAttempted = false
        try {
            return withSourceMutation(beforeClear = {
                closeAttempted = true
                owner.close()
            }, mutation = mutation)
        } finally {
            if (!closeAttempted) owner.close()
        }
    }

    fun withIndependentSelection(
        expected: ResultRevision,
        prepare: () -> ResultRevision,
        accepted: (ResultRevision) -> Unit,
    ): Boolean {
        val candidate = prepare()
        return synchronized(controller) {
            if (!vpnRuns.available() || revisions.load() != expected || candidate.source != expected.source || candidate == expected) {
                return@synchronized false
            }
            val published = storage.readManifest()?.revision
            if (candidate == published) return@synchronized false
            controller.commitSelectionRevision {
                try {
                    if (!revisions.update(candidate) || revisions.load() != candidate) {
                        revisions.markUncertain()
                        RevisionCommit.UNKNOWN
                    } else {
                        accepted(candidate)
                        RevisionCommit.COMMITTED
                    }
                } catch (failure: Throwable) {
                    revisions.markUncertain()
                    throw failure
                }
            }
        }
    }

    fun withIndependentSource(
        prepare: () -> ResultRevision,
        accepted: (ResultRevision) -> Unit,
        initialize: () -> Boolean,
    ): Boolean {
        val expected = synchronized(controller) { revisions.readCurrent() }
        if (expected == CurrentRevision.Unknown) return false
        if (expected == CurrentRevision.Absent) {
            return synchronized(controller) {
                if (revisions.readCurrent() != CurrentRevision.Absent || storage.readManifest() != null ||
                    !storage.safeForUpgrade(null) || !completionReceipts.safeForUpgrade(null) { false }
                ) false else initialize()
            }
        }
        val candidate = prepare()
        return synchronized(controller) {
            if (!vpnRuns.available() || revisions.readCurrent() != expected || !canonicalRevision(candidate)) {
                return@synchronized false
            }
            val previous = (expected as? CurrentRevision.Present)?.revision
            if (previous != null && (!canonicalRevision(previous) || candidate.source == previous.source)) {
                return@synchronized false
            }
            if (candidate == storage.readManifest()?.revision) return@synchronized false
            controller.commitSelectionRevision {
                try {
                    if (!revisions.update(candidate) || revisions.load() != candidate) {
                        revisions.markUncertain()
                        RevisionCommit.UNKNOWN
                    } else {
                        accepted(candidate)
                        RevisionCommit.COMMITTED
                    }
                } catch (failure: Throwable) {
                    revisions.markUncertain()
                    throw failure
                }
            }
        }
    }

    private fun canonicalRevision(revision: ResultRevision): Boolean =
        listOf(revision.source, revision.selection).all { text ->
            val number = text.toLongOrNull()
            number != null && number > 0 && number.toString() == text
        }

    fun ownerAvailable(acquire: () -> AutoCloseable?): Boolean {
        val owner = try { acquire() } catch (_: Exception) { null } catch (_: LinkageError) { null } ?: return false
        return try { owner.close(); true } catch (_: Exception) { false } catch (_: LinkageError) { false }
    }

    companion object {
        fun create(
            context: Context,
            port: SearchSessionPortV3,
            marker: SessionMarker,
            vpnRuns: VpnRunRegistry,
        ): ResultActivationComposition {
            val application = requireNotNull(context.applicationContext)
            return ResultActivationComposition(
                PrivateResultStorage(application),
                PrivateRevisionStorage(application),
                port,
                marker,
                vpnRuns,
                PrivateSearchCompletionReceiptStorage(File(application.noBackupFilesDir, "search-results")),
            )
        }
    }

    private fun currentRevisionOrNull(): ResultRevision? = try { revisions.load() } catch (_: Exception) { null }

    private fun markerStateOrNull(): MarkerState? = try { marker.read() } catch (_: Exception) { null }
}
