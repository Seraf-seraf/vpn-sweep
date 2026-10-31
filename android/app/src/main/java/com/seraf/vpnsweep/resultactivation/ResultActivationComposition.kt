package com.seraf.vpnsweep.resultactivation

import android.content.Context
import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.CurrentRevisionStore
import com.seraf.vpnsweep.searchstorage.PrivateResultStorage
import com.seraf.vpnsweep.searchstorage.PrivateRevisionStorage
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.ResultStore
import com.seraf.vpnsweep.searchstorage.RevisionStorage
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

    fun withSourceMutation(mutation: () -> ResultRevision): Boolean = activation.withSourceMutation {
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
        check(marker.markClear() && marker.read() == MarkerState.CLEAR) { "marker_clear_unknown" }
        true
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
