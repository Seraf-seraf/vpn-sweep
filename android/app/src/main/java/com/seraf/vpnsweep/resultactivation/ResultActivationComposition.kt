package com.seraf.vpnsweep.resultactivation

import android.content.Context
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

internal class ResultActivationComposition private constructor(
    private val storage: ResultStorage,
    private val revisionStorage: RevisionStorage,
    port: SearchSessionPortV3,
    private val marker: SessionMarker,
    val vpnRuns: VpnRunRegistry,
) {
    private val store = ResultStore(storage)
    private val revisions = CurrentRevisionStore(revisionStorage)
    val controller = SearchController(port, store, marker)
    val activation = ResultActivation(controller, store, storage, revisions, marker, vpnRuns::available)

    fun currentRevision(): ResultRevision? = synchronized(controller) { revisions.load() }

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
            )
        }
    }
}
