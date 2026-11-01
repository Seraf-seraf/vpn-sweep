package com.seraf.vpnsweep

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.SearchEngineWorker
import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchidentity.installedCoreSha256
import com.seraf.vpnsweep.searchidentity.requireCoreIdentity
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV4
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SearchTerminalCommitterV1
import com.seraf.vpnsweep.searchstate.SearchStartReason
import com.seraf.vpnsweep.searchstate.SearchStartRejection
import com.seraf.vpnsweep.searchstate.SearchStartStage
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchworker.NetworkOwnerOpen
import com.seraf.vpnsweep.searchworker.SearchEngineWorkerAdapter
import com.seraf.vpnsweep.searchworker.SearchMobilePhaseProgress
import com.seraf.vpnsweep.searchworker.SearchMobilePhaseSession
import com.seraf.vpnsweep.searchworker.SearchOwnerLease
import com.seraf.vpnsweep.searchworker.SearchOwnerLeaseControl
import com.seraf.vpnsweep.searchworker.SearchOwnerLock
import com.seraf.vpnsweep.searchworker.SearchSessionProducer
import com.seraf.vpnsweep.searchworker.SearchNetworkPolicyUnavailable
import com.seraf.vpnsweep.searchworker.WorkerOperationFailure
import com.seraf.vpnsweep.searchworker.WorkerServiceObservation
import com.seraf.vpnsweep.searchworker.createSearchWorkerRound
import com.seraf.vpnsweep.searchworker.openAndroidNetworkOwner
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.searchstate.SubscriptionLoadResult
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SubscriptionUiState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionObserverV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionPortV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import com.seraf.vpnsweep.subscriptionruntime.AndroidSubscriptionPort
import com.seraf.vpnsweep.subscriptionruntime.RecoveredSubscription
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage
import com.seraf.vpnsweep.subscriptionruntime.subscriptionStorage
import com.seraf.vpnsweep.vpn.AppVpnRegistry
import com.seraf.vpnsweep.vpn.VpnRun
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.workerFatalEvent
import com.seraf.vpnsweep.searchui.subscriptionEvent
import com.seraf.vpnsweep.searchui.searchEvent
import com.seraf.vpnsweep.searchui.workerOpenEvent

internal class SweepApplication : Application() {
    val integration: AppComposition by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppComposition(this) }
}

internal enum class SearchReadiness { READY, SELECT_REQUIRED, BUSY, UNKNOWN }

internal fun readinessAfterChecks(ownerRecovery: SearchStartV3?, selectionAvailable: Boolean, selected: Boolean, searchEnabled: Boolean): SearchReadiness = when {
    ownerRecovery == SearchStartV3.BUSY -> SearchReadiness.BUSY
    ownerRecovery == SearchStartV3.UNKNOWN || ownerRecovery == SearchStartV3.REJECTED -> SearchReadiness.UNKNOWN
    !selectionAvailable -> SearchReadiness.UNKNOWN
    !selected -> SearchReadiness.SELECT_REQUIRED
    searchEnabled -> SearchReadiness.READY
    else -> SearchReadiness.UNKNOWN
}

internal fun recheckSearchReadinessFlow(
    ownerBusyRetry: Boolean,
    recover: () -> SearchStartV3,
    selectionAvailable: () -> Boolean,
    hasCommittedSelection: () -> Boolean,
    searchEnabled: () -> Boolean,
): SearchReadiness {
    val recovery = if (ownerBusyRetry) recover() else null
    if (recovery == SearchStartV3.BUSY || recovery == SearchStartV3.UNKNOWN || recovery == SearchStartV3.REJECTED) {
        return readinessAfterChecks(recovery, false, false, false)
    }
    val available = selectionAvailable()
    if (!available) return SearchReadiness.UNKNOWN
    val selected = hasCommittedSelection()
    if (!selected) return SearchReadiness.SELECT_REQUIRED
    return readinessAfterChecks(recovery, true, true, searchEnabled())
}

internal fun emitWorkerDiagnostic(currentGeneration: Long?, generation: Long, event: ScreenEvent, append: (ScreenEvent) -> Unit): Unit {
    if (currentGeneration == generation) append(event)
}

internal fun workerServiceObservationEvent(observation: WorkerServiceObservation): ScreenEvent = when (observation) {
    WorkerServiceObservation.BUSY -> ScreenEvent.WORKER_SERVICE_BUSY
    WorkerServiceObservation.UNAVAILABLE -> ScreenEvent.WORKER_SERVICE_UNAVAILABLE
    WorkerServiceObservation.DEADLINE -> ScreenEvent.WORKER_SERVICE_DEADLINE
    WorkerServiceObservation.CANCELLED -> ScreenEvent.WORKER_SERVICE_CANCELLED
}

internal fun recoverOwnerBusyForSelection(
    controller: SearchController,
    state: SubscriptionUiState,
    receipt: AcceptedSubscriptionReceiptV1?,
    revision: ResultRevision?,
    limitsProven: Boolean,
): SearchStartV3 = synchronized(controller) {
    if (!controller.state.value.ownerBusyRetry || receipt == null || revision == null ||
        state.phase != SubscriptionPhase.READY || state.selectedIds.isEmpty() ||
        state.generation != receipt.requestGeneration ||
        revision != ResultRevision(receipt.sourceRevision, receipt.selectionRevision) || !limitsProven
    ) return@synchronized SearchStartV3.REJECTED
    controller.retryOwnerBusy()
}

internal class AppComposition(context: Context) {
    private val app = requireNotNull(context.applicationContext)
    private val lock = Any()
    private val eventsLock = Any()
    private val mutableEvents = MutableStateFlow<List<ScreenEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()
    fun addEvent(event: ScreenEvent): Unit = synchronized(eventsLock) {
        mutableEvents.value = (mutableEvents.value + event).takeLast(100)
    }
    private val requests = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "subscription-fetch") }
    private var currentRequestGeneration: Long? = null
    private var acceptedSnapshot: AcceptedSubscriptionReceiptV1? = null
    private var activeSearch: SearchLeaseReceiptV1? = null
    private var completedSearch: SearchLeaseReceiptV1? = null
    private var restoredCompletedSearch: SearchLeaseReceiptV1? = null
    private val startGate = VpnStartGateV1()
    private val workerTransportLimits = SearchWorkerTransportLimits.production()
    private val marker = PrivateSessionMarker(File(app.noBackupFilesDir, "session-state"))
    private val searchPort = ProductionSearchPort()
    val activation: ResultActivationComposition = ResultActivationComposition.create(
        app, searchPort, marker, AppVpnRegistry.instance,
    )
    private val storage: SubscriptionStorage = subscriptionStorage(app) { activation.currentRevision() }
    private val subscriptionPort = GenerationSubscriptionPort(
        AndroidSubscriptionPort(app, requests, SUBSCRIPTION_TIMEOUT_MS),
    ) { generation -> synchronized(lock) { currentRequestGeneration = generation } }
    val subscriptions: SubscriptionController = SubscriptionController(
        subscriptionPort,
        activation,
        commitSource = ::commitSource,
        commitSelection = ::commitSelection,
        onPhase = { phase -> subscriptionEvent(phase)?.let(::addEvent) },
        selectionMutation = { ids -> activation.withOwnedSourceMutation(::acquireOwner) { commitSelection(ids) } },
    )
    val searchState = activation.controller.state

    init {
        activation.controller.setPhaseListener { state ->
            if (state.startRejection == null && !state.ownerBusyPending) searchEvent(state.phase)?.let(::addEvent)
        }
        restoreSubscription()
    }

    fun loadSubscription(input: String): SubscriptionLoadResult = subscriptions.loadWithReceipt(input)

    fun select(ids: Set<String>): Boolean = subscriptions.select(ids)

    fun selectionAvailable(): Boolean {
        if (!subscriptions.canSelect()) return false
        return activation.ownerAvailable(::acquireOwner)
    }

    fun retryOwnerBusyForSelection(): SearchStartV3 = synchronized(activation.controller) {
        val state = subscriptions.state.value
        val receipt = synchronized(lock) { acceptedSnapshot }
        val revision = try { activation.currentRevision() } catch (_: Exception) { null }
        return recoverOwnerBusyForSelection(activation.controller, state, receipt, revision, workerTransportLimits.isProven)
    }

    fun recheckSearchReadiness(): SearchReadiness = synchronized(activation.controller) {
        recheckSearchReadinessFlow(
            ownerBusyRetry = searchState.value.ownerBusyRetry,
            recover = ::retryOwnerBusyForSelection,
            selectionAvailable = ::selectionAvailable,
            hasCommittedSelection = { subscriptions.state.value.selectedIds.isNotEmpty() },
            searchEnabled = ::searchEnabled,
        )
    }

    fun subscriptionState(): StateFlow<SubscriptionUiState> = subscriptions.state

    fun selectedForDisplay(): Boolean = canStartVpn()

    fun canStartVpn(): Boolean = synchronized(activation.controller) {
        val imported = synchronized(lock) { acceptedSnapshot } ?: return false
        val searched = synchronized(lock) { completedSearch } ?: return false
        val revision = try { activation.currentRevision() } catch (_: Exception) { null } ?: return false
        val state = subscriptions.state.value
        return workerTransportLimits.isProven && state.phase == SubscriptionPhase.READY && state.selectedIds.isNotEmpty() &&
            state.generation == imported.requestGeneration &&
            revision.source == imported.sourceRevision && revision.selection == imported.selectionRevision &&
            searched.requestGeneration == imported.requestGeneration &&
            searched.sourceRevision.toString() == revision.source &&
            searched.selectionRevision.toString() == revision.selection &&
            hasCleanCompletion(searched) &&
            AppVpnRegistry.instance.available()
    }

    fun searchEnabled(): Boolean = synchronized(activation.controller) {
        val state = subscriptions.state.value
        val receipt = synchronized(lock) { acceptedSnapshot }
        val revision = try { activation.currentRevision() } catch (_: Exception) { null }
        return state.phase == SubscriptionPhase.READY && state.selectedIds.isNotEmpty() &&
            receipt != null && revision == ResultRevision(receipt.sourceRevision, receipt.selectionRevision) &&
            state.generation == receipt.requestGeneration && workerTransportLimits.isProven &&
            (searchState.value.ownerBusyRetry || searchState.value.phase in setOf(SearchPhase.IDLE, SearchPhase.COMPLETE, SearchPhase.CANCELLED, SearchPhase.FAILED))
    }

    fun startSearch(): SearchStartV3 = synchronized(activation.controller) {
        activation.controller.clearStartRejection()
        val before = subscriptions.state.value
        val receipt = synchronized(lock) { acceptedSnapshot }
            ?: return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.ACCEPTED_RECEIPT_MISSING)
        val revision = try { activation.currentRevision() } catch (_: Exception) { null }
            ?: return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.CURRENT_REVISION_UNAVAILABLE)
        if (before.phase != SubscriptionPhase.READY) {
            return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.SUBSCRIPTION_NOT_READY)
        }
        if (before.selectedIds.isEmpty()) {
            return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.SELECTION_EMPTY)
        }
        if (before.generation != receipt.requestGeneration) {
            return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.GENERATION_MISMATCH)
        }
        if (revision != ResultRevision(receipt.sourceRevision, receipt.selectionRevision)) {
            return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.REVISION_MISMATCH)
        }
        if (!workerTransportLimits.isProven) {
            return rejectSearchStart(SearchStartStage.PREFLIGHT, SearchStartReason.TRANSPORT_LIMITS_UNPROVEN)
        }
        if (searchState.value.ownerBusyRetry) {
            val recovered = activation.controller.retryOwnerBusy()
            if (recovered != SearchStartV3.ACCEPTED) return recovered
        }
        return subscriptions.startSelected()
    }

    private fun rejectSearchStart(stage: SearchStartStage, reason: SearchStartReason): SearchStartV3 {
        activation.controller.recordStartRejection(SearchStartRejection(stage, reason))
        return SearchStartV3.REJECTED
    }

    fun cancelSearch(): Unit = activation.controller.cancel()

    fun prepareVpnStart(): VpnStartTicketV1? = synchronized(activation.controller) {
        if (!workerTransportLimits.isProven) return null
        val imported = synchronized(lock) { acceptedSnapshot } ?: return null
        val searched = synchronized(lock) { completedSearch } ?: return null
        val revision = try { activation.currentRevision() } catch (_: Exception) { null } ?: return null
        val identity = VpnStartIdentityV1.fromCommittedSearch(
            imported,
            searched,
            revision,
            searchComplete = hasCleanCompletion(searched),
            resultCommitted = hasCleanCompletion(searched),
            vpnLeaseIdentity = UUID.randomUUID().toString(),
        ) ?: return null
        if (!activation.activation.ready() || !AppVpnRegistry.instance.available()) return null
        return startGate.issue(identity)
    }

    fun consumeVpnStart(token: String?, reserveRun: () -> VpnRun?): VpnStartAdmissionV1? =
        synchronized(activation.controller) {
            val imported = synchronized(lock) { acceptedSnapshot }
            val searched = synchronized(lock) { completedSearch }
            val revision = try { activation.currentRevision() } catch (_: Exception) { null }
            val pendingIdentity = startGate.pendingIdentity(token)
            val identity = if (imported != null && searched != null && revision != null && pendingIdentity != null) {
                VpnStartIdentityV1.fromCommittedSearch(
                    imported,
                    searched,
                    revision,
                    searchComplete = hasCleanCompletion(searched),
                    resultCommitted = hasCleanCompletion(searched),
                    vpnLeaseIdentity = pendingIdentity.vpnLeaseIdentity,
                )
            } else null
            val state = subscriptions.state.value
            if (!workerTransportLimits.isProven || identity == null || state.phase != SubscriptionPhase.READY || state.selectedIds.isEmpty() ||
                state.generation != imported?.requestGeneration || startGate.consume(token, identity) == null
            ) {
                if (token != null) startGate.cancel(token)
                return@synchronized null
            }

            var acquiredOwner: SearchOwnerLease? = null
            val outcome = activation.activation.admitVpn {
                val owner = acquireOwner() ?: return@admitVpn null
                acquiredOwner = owner
                val run = reserveRun()
                if (run == null) {
                    owner.close()
                    acquiredOwner = null
                    return@admitVpn null
                }
                VpnReservation(owner, run, identity.vpnLeaseIdentity)
            }
            when (outcome) {
                is com.seraf.vpnsweep.resultactivation.VpnAdmission.Admitted -> {
                    val reservation = outcome.reservation
                    if (reservation.leaseIdentity != identity.vpnLeaseIdentity || !reservation.owner.mayLoadMobile()) {
                        reservation.quarantine()
                        null
                    } else {
                        VpnStartAdmissionV1(
                            identity,
                            outcome.bytes,
                            reservation.run,
                            reservation.owner::mayLoadMobile,
                            { isCurrentVpnIdentity(identity) },
                            reservation.owner::close,
                            reservation::quarantineOwner,
                        )
                    }
                }
                is com.seraf.vpnsweep.resultactivation.VpnAdmission.Unknown -> {
                    outcome.cleanupReservation?.let { it.quarantine() }
                    acquiredOwner?.let { owner ->
                        try { owner.quarantine() } catch (_: Exception) { }
                        AppVpnRegistry.instance.quarantine()
                    }
                    null
                }
                com.seraf.vpnsweep.resultactivation.VpnAdmission.Unavailable -> {
                    acquiredOwner?.let { owner ->
                        try { owner.close() } catch (_: Exception) {
                            try { owner.quarantine() } catch (_: Exception) { }
                            AppVpnRegistry.instance.quarantine()
                        }
                    }
                    null
                }
            }
        }

    fun cancelVpnStart(token: String?): Unit {
        if (token != null) startGate.cancel(token)
    }

    fun isCurrentVpnIdentity(identity: VpnStartIdentityV1): Boolean = synchronized(activation.controller) {
        val imported = synchronized(lock) { acceptedSnapshot } ?: return false
        val searched = synchronized(lock) { completedSearch } ?: return false
        val revision = try { activation.currentRevision() } catch (_: Exception) { null } ?: return false
        val current = VpnStartIdentityV1.fromCommittedSearch(
            imported,
            searched,
            revision,
            searchComplete = hasCleanCompletion(searched),
            resultCommitted = hasCleanCompletion(searched),
            vpnLeaseIdentity = identity.vpnLeaseIdentity,
        ) ?: return false
        return current == identity
    }

    private fun hasCleanCompletion(searched: SearchLeaseReceiptV1): Boolean {
        val restored = synchronized(lock) { restoredCompletedSearch == searched }
        val state = searchState.value
        if (restored) return state.phase == SearchPhase.IDLE
        return state.phase == SearchPhase.COMPLETE && state.resultCommitted
    }

    private fun commitSource(snapshot: SubscriptionSnapshotV1): ResultRevision {
        val generation = synchronized(lock) { currentRequestGeneration } ?: throw IOException("subscription_generation")
        val owner = acquireOwner() ?: throw IOException("search_owner_unavailable")
        try {
            val revision = storage.commitSource(snapshot, generation)
            val receipt = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(generation, snapshot, revision)
                ?: throw IOException("subscription_receipt")
            synchronized(lock) {
                acceptedSnapshot = receipt
                completedSearch = null
                restoredCompletedSearch = null
                activeSearch = null
            }
            return revision
        } finally {
            owner.close()
        }
    }

    private fun commitSelection(ids: Set<String>): ResultRevision {
        val revision = storage.commitSelection(ids)
        synchronized(lock) {
            acceptedSnapshot = acceptedSnapshot?.let { current ->
                current.copy(sourceRevision = revision.source, selectionRevision = revision.selection)
            }
            completedSearch = null
            restoredCompletedSearch = null
            activeSearch = null
        }
        return revision
    }

    private fun acquireOwner(): SearchOwnerLease? = when (val result = SearchOwnerLock.acquire(app)) {
        is com.seraf.vpnsweep.searchworker.OwnerAcquisition.Acquired -> result.lease
        else -> null
    }

    private fun restoreSubscription(): Unit {
        val revision = try { activation.currentRevision() } catch (_: Exception) { null } ?: return
        val recovered: RecoveredSubscription = storage.recover(revision) ?: return
        val receipt = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(
            recovered.requestGeneration,
            recovered.snapshot,
            recovered.revision,
        ) ?: run {
            recovered.snapshot.close()
            return
        }
        if (!subscriptions.restore(
                recovered.snapshot,
                recovered.selectedIds,
                recovered.revision,
                recovered.requestGeneration,
            )
        ) return
        val completed = activation.loadCleanComplete(receipt)
            ?.takeIf { activation.activation.ready() }
        synchronized(lock) {
            acceptedSnapshot = receipt
            activeSearch = null
            completedSearch = completed
            restoredCompletedSearch = completed
        }
    }

    private inner class GenerationSubscriptionPort(
        private val delegate: SubscriptionPortV1,
        private val onGeneration: (Long) -> Unit,
    ) : SubscriptionPortV1 {
        override fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1 {
            onGeneration(generation)
            return delegate.start(generation, input, observer)
        }

        override fun cancel(generation: Long): Unit = delegate.cancel(generation)
    }

    private inner class ProductionSearchPort : SearchSessionPortV4, SearchTerminalCommitterV1, com.seraf.vpnsweep.searchstate.NoOwnerBusyRecoveryPort {
        private val lock = Any()
        private var noOwnerBusyGeneration: Long? = null
        private val identities = LinkedHashMap<Long, SearchLeaseReceiptV1>()
        private val producer = SearchSessionProducer(
            cleanupMillis = DEFAULT_CLEANUP_MILLIS,
            createSession = { snapshot, lease, progress -> createSession(snapshot, lease, progress) },
            reportNetworkUnavailable = { generation ->
                synchronized(this@AppComposition.lock) {
                    if (activeSearch?.sessionGeneration == generation) addEvent(ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE)
                }
            },
        )

        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            val imported = synchronized(this@AppComposition.lock) { acceptedSnapshot }
                ?: return rejectStart(SearchStartReason.ACCEPTED_RECEIPT_MISSING)
            val source = imported.sourceRevision.toLongOrNull()
                ?: return rejectStart(SearchStartReason.SNAPSHOT_REVISION_INVALID)
            val selection = imported.selectionRevision.toLongOrNull()
                ?: return rejectStart(SearchStartReason.SNAPSHOT_REVISION_INVALID)
            if (snapshot.sessionGeneration <= 0 || phaseEpoch <= 0 || snapshot.sourceRevision != source || snapshot.selectionRevision != selection) {
                return rejectStart(SearchStartReason.SNAPSHOT_IDENTITY_MISMATCH)
            }
            val lease = when (val result = SearchOwnerLock.acquire(app)) {
                is com.seraf.vpnsweep.searchworker.OwnerAcquisition.Acquired -> result.lease
                com.seraf.vpnsweep.searchworker.OwnerAcquisition.Busy -> {
                    synchronized(lock) {
                        if (noOwnerBusyGeneration != null) return SearchStartV3.UNKNOWN
                        noOwnerBusyGeneration = snapshot.sessionGeneration
                    }
                    return SearchStartV3.BUSY
                }
                com.seraf.vpnsweep.searchworker.OwnerAcquisition.Unavailable -> return SearchStartV3.UNKNOWN
            }
            val leaseIdentity = UUID.randomUUID().toString()
            val receipt = SearchLeaseReceiptV1.fromAcceptedSnapshot(imported, snapshot, leaseIdentity)
                ?: run {
                    if (!closeUnusedLease(lease)) return SearchStartV3.UNKNOWN
                    return rejectStart(SearchStartReason.LEASE_RECEIPT_INVALID)
                }
            synchronized(lock) {
                if (identities.containsKey(snapshot.sessionGeneration)) {
                    if (!closeUnusedLease(lease)) return SearchStartV3.UNKNOWN
                    return SearchStartV3.UNKNOWN
                }
                val offered = producer.offerLease(lease)
                if (offered != com.seraf.vpnsweep.searchworker.SearchLeaseHandoff.CLAIMED) {
                    if (offered == com.seraf.vpnsweep.searchworker.SearchLeaseHandoff.RETURNED) {
                        if (!closeUnusedLease(lease)) return SearchStartV3.UNKNOWN
                    }
                    return SearchStartV3.UNKNOWN
                }
                identities[snapshot.sessionGeneration] = receipt
                synchronized(this@AppComposition.lock) {
                    activeSearch = receipt
                    completedSearch = null
                    restoredCompletedSearch = null
                }
                val result = producer.start(snapshot, phaseEpoch, observer)
                if (result == SearchStartV3.REJECTED) {
                    activation.controller.recordStartRejection(
                        SearchStartRejection(SearchStartStage.PRODUCER, SearchStartReason.PRODUCER_REJECTED),
                    )
                }
                if (result != SearchStartV3.ACCEPTED) {
                    identities.remove(snapshot.sessionGeneration)
                    synchronized(this@AppComposition.lock) {
                        if (activeSearch == receipt) activeSearch = null
                    }
                }
                return if (result == SearchStartV3.ACCEPTED) result else SearchStartV3.UNKNOWN
            }
        }

        override fun isNoOwnerBusy(sessionGeneration: Long): Boolean = synchronized(lock) {
            noOwnerBusyGeneration == sessionGeneration
        }

        override fun verifyOwnerReleased(sessionGeneration: Long): com.seraf.vpnsweep.searchstate.OwnerBusyRecovery = synchronized(lock) {
            if (noOwnerBusyGeneration != sessionGeneration) return@synchronized com.seraf.vpnsweep.searchstate.OwnerBusyRecovery.UNKNOWN
            when (val acquired = SearchOwnerLock.acquire(app)) {
                com.seraf.vpnsweep.searchworker.OwnerAcquisition.Busy -> com.seraf.vpnsweep.searchstate.OwnerBusyRecovery.BUSY
                com.seraf.vpnsweep.searchworker.OwnerAcquisition.Unavailable -> {
                    noOwnerBusyGeneration = null
                    com.seraf.vpnsweep.searchstate.OwnerBusyRecovery.UNKNOWN
                }
                is com.seraf.vpnsweep.searchworker.OwnerAcquisition.Acquired -> {
                    noOwnerBusyGeneration = null
                    if (closeUnusedLease(acquired.lease)) com.seraf.vpnsweep.searchstate.OwnerBusyRecovery.READY
                    else com.seraf.vpnsweep.searchstate.OwnerBusyRecovery.UNKNOWN
                }
            }
        }

        private fun rejectStart(reason: SearchStartReason): SearchStartV3 {
            activation.controller.recordStartRejection(
                SearchStartRejection(SearchStartStage.PORT_DISPATCH, reason),
            )
            return SearchStartV3.REJECTED
        }

        private fun closeUnusedLease(lease: SearchOwnerLease): Boolean = try {
            lease.close()
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)

        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)

        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)

        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
            val receipt = synchronized(lock) { identities[terminal.sessionGeneration] } ?: return false
            if (!producer.acceptTerminal(terminal)) return false
            if (terminal is SearchTerminalV3.Complete) {
                if (terminal.resources != SearchResourceStateV2.RELEASED ||
                    terminal.resultFence != SearchResultFenceV3.CONFIRMED
                ) return false
                val accepted = synchronized(this@AppComposition.lock) {
                    if (activeSearch != receipt) return false
                    acceptedSnapshot
                } ?: return false
                if (!activation.prepareCleanComplete(accepted, receipt)) return false
            }
            synchronized(lock) {
                if (identities[terminal.sessionGeneration] != receipt) return false
                synchronized(this@AppComposition.lock) {
                    if (activeSearch != receipt) return false
                    if (terminal !is SearchTerminalV3.Complete) {
                        identities.remove(terminal.sessionGeneration)
                        activeSearch = null
                        completedSearch = null
                        restoredCompletedSearch = null
                    }
                }
            }
            return true
        }

        override fun commitTerminal(terminal: SearchTerminalV3): Boolean {
            if (terminal !is SearchTerminalV3.Complete ||
                terminal.resources != SearchResourceStateV2.RELEASED ||
                terminal.resultFence != SearchResultFenceV3.CONFIRMED
            ) return false
            val receipt = synchronized(lock) { identities[terminal.sessionGeneration] } ?: return false
            val accepted = synchronized(this@AppComposition.lock) {
                if (activeSearch != receipt) return false
                acceptedSnapshot
            } ?: return false
            if (accepted.requestGeneration != receipt.requestGeneration ||
                !activation.commitCleanComplete()
            ) return false
            synchronized(lock) {
                if (identities[terminal.sessionGeneration] != receipt) return false
                synchronized(this@AppComposition.lock) {
                    if (activeSearch != receipt || acceptedSnapshot != accepted) return false
                    identities.remove(terminal.sessionGeneration)
                    activeSearch = null
                    completedSearch = receipt
                    restoredCompletedSearch = null
                }
            }
            return true
        }

        private fun createSession(
            snapshot: SearchSnapshotV1,
            lease: SearchOwnerLeaseControl,
            progress: (Long, SearchProgressV1) -> Unit,
        ): SearchMobilePhaseSession {
            var stage = ScreenEvent.SEARCH_INIT_PROFILES_FAILED
            try {
            val profiles = snapshot.profiles.open().use { input -> input.readNBytes(MAX_SEARCH_PROFILES_BYTES + 1) }
            if (profiles.isEmpty() || profiles.size > MAX_SEARCH_PROFILES_BYTES) throw IOException("search_profile_size")
            stage = ScreenEvent.SEARCH_INIT_CORE_IDENTITY_FAILED
            val digest = requireCoreIdentity { installedCoreSha256(app) }
            stage = ScreenEvent.SEARCH_INIT_MANIFEST_FAILED
            val manifest = Mobile.buildDefaultSearchManifest(profiles, digest)
            val cleanupMillis = Mobile.searchManifestCleanupMillis(manifest)
            stage = ScreenEvent.SEARCH_INIT_NETWORK_UNKNOWN
            val manager = app.getSystemService(ConnectivityManager::class.java) ?: throw SearchNetworkPolicyUnavailable()
            val network = when (val opened = openAndroidNetworkOwner(
                manager,
                leaseCurrent = lease::mayLoadMobile,
                stopRoundAndDns = { producer.ownerLost(snapshot.sessionGeneration) },
            )) {
                is NetworkOwnerOpen.Ready -> opened
                NetworkOwnerOpen.Unavailable -> throw SearchNetworkPolicyUnavailable()
                NetworkOwnerOpen.Unknown -> throw IOException("search_resolver_unknown")
            }
            val owner = network.owner
            val limits = workerTransportLimits
            val adapter = SearchEngineWorkerAdapter(
                snapshot.sessionGeneration,
                network.network,
                cleanupMillis,
                limits.replyBytes,
                createRound = { identity ->
                    createSearchWorkerRound(
                        app,
                        lease,
                        owner,
                        identity,
                        network.network,
                        limits.frameBytes,
                        limits.replyBytes,
                        reportRetirementFailure = { reason ->
                            synchronized(this@AppComposition.lock) {
                                emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, workerOpenEvent(reason), ::addEvent)
                            }
                        },
                        reportServiceFailure = { reason ->
                            synchronized(this@AppComposition.lock) {
                                emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, workerFatalEvent(reason), ::addEvent)
                            }
                        },
                        reportBindFailure = {
                            synchronized(this@AppComposition.lock) {
                                emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, ScreenEvent.WORKER_BIND_FAILED, ::addEvent)
                            }
                        },
                        reportServiceObservation = { observation ->
                            val event = workerServiceObservationEvent(observation)
                            synchronized(this@AppComposition.lock) {
                                emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, event, ::addEvent)
                            }
                        },
                    )
                },
                reportProgress = { progress(snapshot.sessionGeneration, it) },
                reportKnownFailure = { reason ->
                    synchronized(this@AppComposition.lock) {
                        emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, workerOpenEvent(reason), ::addEvent)
                    }
                },
                reportFatalFailure = { reason ->
                    synchronized(this@AppComposition.lock) {
                        emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, workerFatalEvent(reason), ::addEvent)
                    }
                },
                reportAdmissionFailure = {
                    synchronized(this@AppComposition.lock) {
                        emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, ScreenEvent.WORKER_ADMISSION_FAILED, ::addEvent)
                    }
                },
                reportOperationFailure = { operation ->
                    val event = when (operation) {
                        WorkerOperationFailure.CALL -> ScreenEvent.WORKER_CALL_FAILED
                        WorkerOperationFailure.CALL_INVALID -> ScreenEvent.WORKER_CALL_INVALID
                        WorkerOperationFailure.CALL_STATE -> ScreenEvent.WORKER_CALL_STATE
                        WorkerOperationFailure.CALL_DEADLINE -> ScreenEvent.WORKER_CALL_DEADLINE
                        WorkerOperationFailure.CALL_CANCELLED -> ScreenEvent.WORKER_CALL_CANCELLED
                        WorkerOperationFailure.CANCEL -> ScreenEvent.WORKER_CANCEL_FAILED
                        WorkerOperationFailure.CLOSE -> ScreenEvent.WORKER_CLOSE_FAILED
                    }
                    synchronized(this@AppComposition.lock) {
                        emitWorkerDiagnostic(activeSearch?.sessionGeneration, snapshot.sessionGeneration, event, ::addEvent)
                    }
                },
            )
            stage = ScreenEvent.SEARCH_INIT_SESSION_FAILED
            val session = Mobile.newSearchSession(
                snapshot.sessionGeneration,
                manifest,
                profiles,
                File(app.noBackupFilesDir, "search-state/${snapshot.sessionGeneration}").absolutePath,
                File(app.noBackupFilesDir, "search-staging/${snapshot.sessionGeneration}").absolutePath,
                adapter as SearchEngineWorker,
            )
            val phaseProgress = SearchMobilePhaseProgress { epoch, value -> progress(epoch, value) }
            return SearchMobilePhaseSession(
                prepare = session::preparePhase,
                run = session::runPhase,
                cancel = session::cancelPhase,
                openResult = { com.seraf.vpnsweep.searchworker.openMobileResult(session) },
                close = {
                    session.close()
                    if (!owner.close()) throw IOException("search_network_cleanup_unknown")
                },
                progress = phaseProgress,
                paused = { com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2.PAUSED_LEASE_HELD },
                quarantineOwner = {
                    owner.close()
                    lease.quarantine()
                },
                owner = lease,
            )
            } catch (failure: Throwable) {
                if (failure !is SearchNetworkPolicyUnavailable) {
                    try {
                        synchronized(this@AppComposition.lock) {
                            if (activeSearch?.sessionGeneration == snapshot.sessionGeneration) addEvent(stage)
                        }
                    } catch (_: Exception) { } catch (_: LinkageError) { }
                }
                throw failure
            }
        }
    }

    private class VpnReservation(
        val owner: SearchOwnerLease,
        val run: VpnRun,
        val leaseIdentity: String,
    ) {
        fun quarantine(): Unit = quarantineOwner()

        fun quarantineOwner(): Unit {
            try { owner.quarantine() } finally { run.quarantineCleanup() }
        }
    }

    private class PrivateSessionMarker(private val directory: File) : SessionMarker {
        private val marker = File(directory, "current")

        @Synchronized
        override fun read(): MarkerState = try {
            if (!marker.exists()) MarkerState.CLEAR else when (marker.readText(Charsets.US_ASCII)) {
                ACTIVE -> MarkerState.ACTIVE
                CLEAR -> MarkerState.CLEAR
                else -> MarkerState.UNKNOWN
            }
        } catch (_: Exception) { MarkerState.UNKNOWN }

        @Synchronized
        override fun markActive(): Boolean = write(ACTIVE)

        @Synchronized
        override fun markClear(): Boolean = write(CLEAR)

        private fun write(value: String): Boolean {
            return try {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
                val temporary = File(directory, "marker-${UUID.randomUUID()}")
                FileOutputStream(temporary).use { output ->
                    output.write(value.toByteArray(Charsets.US_ASCII))
                    output.fd.sync()
                }
                if (!temporary.renameTo(marker)) {
                    temporary.delete()
                    return false
                }
                val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC, 0)
                try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
                true
            } catch (_: Exception) {
                false
            }
        }

        private companion object {
            const val ACTIVE = "ACTIVE"
            const val CLEAR = "CLEAR"
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 30_000L
        const val MAX_SEARCH_PROFILES_BYTES = 8 * 1024 * 1024
        const val DEFAULT_CLEANUP_MILLIS = 10_000L
        const val WORKER_FRAME_HEADER_BYTES = 16
    }
}
