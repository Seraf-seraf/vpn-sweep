package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionBatchStatusV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionFailureV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionObserverV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionPortV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionProfilesTooLargeException
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SubscriptionPhase { IDLE, LOADING, READY, NO_SUPPORTED, FAILED, CANCELLED }
internal class SubscriptionLoadResult(val status: SubscriptionStartV1, val generation: Long? = null)

internal class SubscriptionUiState(
    val phase: SubscriptionPhase = SubscriptionPhase.IDLE,
    val records: List<SubscriptionRecordV1> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val failure: SubscriptionFailureV1? = null,
    val requestActive: Boolean = false,
    val generation: Long = 0,
)

internal class SubscriptionController(
    private val port: SubscriptionPortV1,
    private val composition: ResultActivationComposition,
    // The callback borrows the snapshot and returns only after its durable commit.
    private val commitSource: (SubscriptionSnapshotV1) -> ResultRevision,
    private val commitSelection: (Set<String>) -> ResultRevision,
    private val onPhase: (SubscriptionPhase) -> Unit = {},
    private val selectionMutation: ((Set<String>) -> Boolean)? = null,
) : SubscriptionObserverV1 {
    private class Request(
        val generation: Long,
        var cancelled: Boolean = false,
        var dispatching: Boolean = true,
        var callbackProcessed: Boolean = false,
    )
    private val mutableState = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = mutableState.asStateFlow()
    private var nextGeneration = 0L
    private var request: Request? = null
    private var installed: SubscriptionSnapshotV1? = null
    private var installedRecords: List<SubscriptionRecordV1> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private val pendingClose = mutableListOf<AutoCloseable>()
    private var uncertainSearchInput: PrivateInputV1? = null

    @Synchronized
    fun restore(
        snapshot: SubscriptionSnapshotV1,
        selectedIds: Set<String>,
        revision: ResultRevision,
        requestGeneration: Long = 1L,
    ): Boolean {
        val restored = try {
            synchronized(composition.controller) {
                if (installed != null || request != null || pendingClose.isNotEmpty() || uncertainSearchInput != null) return@synchronized false
                if (requestGeneration <= 0 || requestGeneration < nextGeneration) return@synchronized false
                if (composition.currentRevision() != revision) return@synchronized false
                val records = snapshot.records.toList()
                if (!validRecords(records)) return@synchronized false
                val supported = HashSet<String>()
                for (record in records) if (record.supported) supported.add(record.id)
                val selection = selectedIds.toSet()
                if (supported.isEmpty() || !supported.containsAll(selection)) return@synchronized false
                installed = snapshot
                installedRecords = records
                this.selectedIds = selection
                nextGeneration = requestGeneration
                publish(visibleState(SubscriptionPhase.READY))
                true
            }
        } catch (failure: Throwable) {
            if (failure !is Exception) {
                closeResource(snapshot)
                throw failure
            }
            false
        }
        if (!restored) closeResource(snapshot)
        return restored
    }

    fun load(input: String): SubscriptionStartV1 = loadWithReceipt(input).status

    fun loadWithReceipt(input: String): SubscriptionLoadResult {
        val current = synchronized(this) {
            if (request != null || pendingClose.isNotEmpty() || uncertainSearchInput != null) return SubscriptionLoadResult(SubscriptionStartV1.BUSY)
            if (nextGeneration == Long.MAX_VALUE) return SubscriptionLoadResult(SubscriptionStartV1.REJECTED)
            Request(++nextGeneration).also {
                request = it
                publish(visibleState(SubscriptionPhase.LOADING))
            }
        }
        val result = try {
            port.start(current.generation, input, this)
        } catch (failure: Throwable) {
            val callbackProcessed = synchronized(this) {
                current.dispatching = false
                if (current.callbackProcessed) {
                    true
                } else {
                    if (request === current) {
                        request = null
                        publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
                    }
                    false
                }
            }
            if (failure !is Exception) throw failure
            return if (callbackProcessed) {
                SubscriptionLoadResult(SubscriptionStartV1.ACCEPTED, current.generation)
            } else {
                SubscriptionLoadResult(SubscriptionStartV1.REJECTED)
            }
        }
        val sendCancel = synchronized(this) {
            current.dispatching = false
            if (result != SubscriptionStartV1.ACCEPTED && request === current) {
                request = null
                publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            }
            result == SubscriptionStartV1.ACCEPTED && current.cancelled
        }
        if (sendCancel) port.cancel(current.generation)
        return SubscriptionLoadResult(result, if (result == SubscriptionStartV1.ACCEPTED) current.generation else null)
    }

    fun cancel(): Unit {
        val cancelNow = synchronized(this) {
            val current = request ?: return
            current.cancelled = true
            request = null
            publish(if (pendingClose.isNotEmpty()) {
                visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL)
            } else {
                visibleState(SubscriptionPhase.CANCELLED)
            })
            if (current.dispatching) null else current.generation
        }
        if (cancelNow != null) port.cancel(cancelNow)
    }

    @Synchronized
    override fun onParsed(generation: Long, status: SubscriptionBatchStatusV1, snapshot: SubscriptionSnapshotV1): Unit {
        val current = request
        if (current == null || current.generation != generation || current.cancelled) {
            closeResource(snapshot)
            return
        }
        current.callbackProcessed = true
        request = null
        if (pendingClose.isNotEmpty()) {
            closeResource(snapshot)
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            return
        }
        val records = snapshot.records.toList()
        val hasSupported = records.any { it.supported }
        if (!validRecords(records) || hasSupported != (status == SubscriptionBatchStatusV1.HAS_SUPPORTED)) {
            if (!closeResource(snapshot)) return
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.MALFORMED))
            return
        }
        if (!hasSupported) {
            if (!closeResource(snapshot)) return
            if (composition.activation.withSourceMutation { true }) {
                publish(visibleState(SubscriptionPhase.NO_SUPPORTED))
            } else {
                publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            }
            return
        }
        val committed = try {
            composition.withSourceMutation { commitSource(snapshot) }
        } catch (failure: Throwable) {
            if (failure !is Exception) {
                closeResource(snapshot)
                throw failure
            }
            false
        }
        if (!committed) {
            if (!closeResource(snapshot)) return
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            return
        }
        val previous = installed
        installed = snapshot
        installedRecords = records
        selectedIds = emptySet()
        if (previous != null) closeResource(previous)
        if (pendingClose.isNotEmpty()) {
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            return
        }
        publish(SubscriptionUiState(phase = SubscriptionPhase.READY, records = records, generation = nextGeneration))
    }

    @Synchronized
    override fun onFailure(generation: Long, failure: SubscriptionFailureV1): Unit {
        val current = request
        if (current == null || current.generation != generation) return
        current.callbackProcessed = true
        request = null
        if (pendingClose.isNotEmpty()) {
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
            return
        }
        publish(visibleState(
            if (failure == SubscriptionFailureV1.CANCELLED) SubscriptionPhase.CANCELLED else SubscriptionPhase.FAILED,
            failure,
        ))
    }

    @Synchronized
    fun canSelect(): Boolean = installed != null && request == null && pendingClose.isEmpty() &&
        uncertainSearchInput == null &&
        composition.activation.withSourceMutation { true }

    @Synchronized
    fun select(ids: Set<String>): Boolean {
        if (ids.isEmpty() || !canSelect()) return false
        val supported = installedRecords.filter { it.supported }.map { it.id }.toSet()
        if (!supported.containsAll(ids)) return false
        val copy = ids.toSet()
        if (copy == selectedIds) return composition.activation.withSourceMutation { true }
        var failed = false
        val committed = try {
            selectionMutation?.invoke(copy) ?: composition.withSourceMutation { commitSelection(copy) }
        } catch (failure: Throwable) {
            if (failure !is Exception) throw failure
            failed = true
            false
        }
        if (committed) {
            selectedIds = copy
            publish(visibleState(if (request == null) SubscriptionPhase.READY else SubscriptionPhase.LOADING))
        } else if (failed) {
            publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL), notifyPhase = false)
        }
        return committed
    }

    fun startSelected(): SearchStartV3 {
        composition.controller.clearStartRejection()
        val prepared = synchronized(this) {
            if (pendingClose.isNotEmpty() || uncertainSearchInput != null) return SearchStartV3.BUSY
            if (installed == null) return rejectStart(SearchStartReason.SNAPSHOT_NOT_READY)
            if (selectedIds.isEmpty()) return rejectStart(SearchStartReason.SELECTION_EMPTY)
            synchronized(composition.controller) {
                val revisions = try { composition.currentRevision() } catch (_: Exception) { null }
                val sourceRevision = revisions?.source?.toLongOrNull()
                val selectionRevision = revisions?.selection?.toLongOrNull()
                if (sourceRevision == null || selectionRevision == null ||
                    sourceRevision.toString() != revisions.source || selectionRevision.toString() != revisions.selection) {
                    return rejectStart(SearchStartReason.REVISION_INVALID)
                }
                val profiles = try {
                    installed?.selectedProfiles(selectedIds)
                } catch (_: SubscriptionProfilesTooLargeException) {
                    publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.LIMIT_EXCEEDED), notifyPhase = false)
                    return rejectStart(SearchStartReason.PROFILE_TOO_LARGE)
                }
                if (profiles == null) {
                    return rejectStart(SearchStartReason.PROFILE_UNAVAILABLE)
                }
                Pair(composition.activation.reserveSearch(sourceRevision, selectionRevision, profiles), profiles)
            }
        }
        val result = when (val preparation = prepared.first) {
            is SearchStartPreparation.Denied -> preparation.result
            is SearchStartPreparation.Reserved -> preparation.dispatch()
        }
        synchronized(this) {
            val input = prepared.second
            when (result) {
                SearchStartV3.ACCEPTED -> Unit
                SearchStartV3.UNKNOWN -> {
                    uncertainSearchInput = input
                    publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL), notifyPhase = false)
                }
                SearchStartV3.BUSY, SearchStartV3.REJECTED -> closeResource(input)
            }
        }
        return result
    }

    private fun rejectStart(reason: SearchStartReason): SearchStartV3 {
        composition.controller.recordStartRejection(SearchStartRejection(SearchStartStage.PREFLIGHT, reason))
        return SearchStartV3.REJECTED
    }

    private fun publish(next: SubscriptionUiState, notifyPhase: Boolean = true): Unit {
        val previous = mutableState.value
        mutableState.value = next
        if (notifyPhase && (previous.phase != next.phase || previous.generation != next.generation)) onPhase(next.phase)
    }

    private fun visibleState(phase: SubscriptionPhase, failure: SubscriptionFailureV1? = null): SubscriptionUiState =
        SubscriptionUiState(phase, installedRecords, selectedIds, failure, requestActive = request != null, generation = nextGeneration)

    private fun validRecords(records: List<SubscriptionRecordV1>): Boolean {
        val ids = HashSet<String>()
        return records.size <= MAX_RECORDS && records.all {
            it.id.isNotEmpty() && ids.add(it.id) && (it.supported == (it.reason == null))
        }
    }

    @Synchronized
    fun retryCleanup(): Boolean {
        if (uncertainSearchInput != null) return false
        val iterator = pendingClose.iterator()
        while (iterator.hasNext()) {
            try {
                iterator.next().close()
                iterator.remove()
            } catch (_: Exception) {
            }
        }
        if (pendingClose.isNotEmpty()) return false
        val phase = when {
            request != null -> SubscriptionPhase.LOADING
            installed == null -> SubscriptionPhase.IDLE
            else -> SubscriptionPhase.READY
        }
        publish(visibleState(phase))
        return true
    }

    private fun closeResource(resource: AutoCloseable): Boolean = try {
        resource.close()
        true
    } catch (_: Exception) {
        pendingClose.add(resource)
        publish(visibleState(SubscriptionPhase.FAILED, SubscriptionFailureV1.INTERNAL))
        false
    }
}

private const val MAX_RECORDS = 10_000
