package com.seraf.vpnsweep

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.SearchEngineWorker
import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressV1
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV4
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchPhase
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
import com.seraf.vpnsweep.searchworker.createSearchWorkerRound
import com.seraf.vpnsweep.searchworker.openAndroidNetworkOwner
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.searchstate.SubscriptionLoadResult
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SubscriptionUiState
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.StateFlow

internal class SweepApplication : Application() {
    val integration: AppComposition by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppComposition(this) }
}

internal class AppComposition(context: Context) {
    private val app = requireNotNull(context.applicationContext)
    private val lock = Any()
    private val requests = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "subscription-fetch") }
    private var currentRequestGeneration: Long? = null
    private var acceptedSnapshot: AcceptedSubscriptionReceiptV1? = null
    private var activeSearch: SearchLeaseReceiptV1? = null
    private var completedSearch: SearchLeaseReceiptV1? = null
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
    )
    val searchState = activation.controller.state

    init { restoreSubscription() }

    fun loadSubscription(input: String): SubscriptionLoadResult = subscriptions.loadWithReceipt(input)

    fun select(ids: Set<String>): Boolean = subscriptions.select(ids)

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
            searchState.value.phase == SearchPhase.COMPLETE && searchState.value.resultCommitted &&
            AppVpnRegistry.instance.available()
    }

    fun searchEnabled(): Boolean = synchronized(activation.controller) {
        val state = subscriptions.state.value
        val receipt = synchronized(lock) { acceptedSnapshot }
        val revision = try { activation.currentRevision() } catch (_: Exception) { null }
        return state.phase == SubscriptionPhase.READY && state.selectedIds.isNotEmpty() &&
            receipt != null && revision == ResultRevision(receipt.sourceRevision, receipt.selectionRevision) &&
            state.generation == receipt.requestGeneration && workerTransportLimits.isProven &&
            searchState.value.phase in setOf(SearchPhase.IDLE, SearchPhase.COMPLETE, SearchPhase.CANCELLED, SearchPhase.FAILED)
    }

    fun startSearch(): SearchStartV3 = synchronized(activation.controller) {
        val before = subscriptions.state.value
        val receipt = synchronized(lock) { acceptedSnapshot } ?: return SearchStartV3.REJECTED
        val revision = try { activation.currentRevision() } catch (_: Exception) { null } ?: return SearchStartV3.REJECTED
        if (before.phase != SubscriptionPhase.READY || before.selectedIds.isEmpty() ||
            before.generation != receipt.requestGeneration || revision != ResultRevision(receipt.sourceRevision, receipt.selectionRevision) ||
            !workerTransportLimits.isProven
        ) return SearchStartV3.REJECTED
        return subscriptions.startSelected()
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
            searchComplete = searchState.value.phase == SearchPhase.COMPLETE,
            resultCommitted = searchState.value.resultCommitted,
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
                    searchComplete = searchState.value.phase == SearchPhase.COMPLETE,
                    resultCommitted = searchState.value.resultCommitted,
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
            searchComplete = searchState.value.phase == SearchPhase.COMPLETE,
            resultCommitted = searchState.value.resultCommitted,
            vpnLeaseIdentity = identity.vpnLeaseIdentity,
        ) ?: return false
        return current == identity
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
                activeSearch = null
            }
            return revision
        } finally {
            owner.close()
        }
    }

    private fun commitSelection(ids: Set<String>): ResultRevision {
        val owner = acquireOwner() ?: throw IOException("search_owner_unavailable")
        try {
            val revision = storage.commitSelection(ids)
            synchronized(lock) {
                acceptedSnapshot = acceptedSnapshot?.let { current ->
                    current.copy(sourceRevision = revision.source, selectionRevision = revision.selection)
                }
                completedSearch = null
                activeSearch = null
            }
            return revision
        } finally {
            owner.close()
        }
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
        synchronized(lock) {
            acceptedSnapshot = receipt
            activeSearch = null
            completedSearch = null
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

    private inner class ProductionSearchPort : SearchSessionPortV4 {
        private val lock = Any()
        private val identities = LinkedHashMap<Long, SearchLeaseReceiptV1>()
        private val producer = SearchSessionProducer(
            cleanupMillis = DEFAULT_CLEANUP_MILLIS,
            createSession = { snapshot, lease, progress -> createSession(snapshot, lease, progress) },
        )

        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            val imported = synchronized(this@AppComposition.lock) { acceptedSnapshot } ?: return SearchStartV3.REJECTED
            val source = imported.sourceRevision.toLongOrNull() ?: return SearchStartV3.REJECTED
            val selection = imported.selectionRevision.toLongOrNull() ?: return SearchStartV3.REJECTED
            if (snapshot.sessionGeneration <= 0 || phaseEpoch <= 0 || snapshot.sourceRevision != source || snapshot.selectionRevision != selection) {
                return SearchStartV3.REJECTED
            }
            val lease = when (val result = SearchOwnerLock.acquire(app)) {
                is com.seraf.vpnsweep.searchworker.OwnerAcquisition.Acquired -> result.lease
                else -> return SearchStartV3.BUSY
            }
            val leaseIdentity = UUID.randomUUID().toString()
            val receipt = SearchLeaseReceiptV1.fromAcceptedSnapshot(imported, snapshot, leaseIdentity)
                ?: run {
                    try { lease.close() } catch (_: Exception) { }
                    return SearchStartV3.REJECTED
                }
            synchronized(lock) {
                if (identities.containsKey(snapshot.sessionGeneration)) {
                    try { lease.close() } catch (_: Exception) { }
                    return SearchStartV3.BUSY
                }
                val offered = producer.offerLease(lease)
                if (offered != com.seraf.vpnsweep.searchworker.SearchLeaseHandoff.CLAIMED) {
                    if (offered == com.seraf.vpnsweep.searchworker.SearchLeaseHandoff.RETURNED) {
                        try { lease.close() } catch (_: Exception) { }
                    }
                    return if (offered == com.seraf.vpnsweep.searchworker.SearchLeaseHandoff.UNKNOWN) SearchStartV3.UNKNOWN else SearchStartV3.BUSY
                }
                identities[snapshot.sessionGeneration] = receipt
                synchronized(this@AppComposition.lock) {
                    activeSearch = receipt
                    completedSearch = null
                }
                val result = producer.start(snapshot, phaseEpoch, observer)
                if (result != SearchStartV3.ACCEPTED) {
                    identities.remove(snapshot.sessionGeneration)
                    synchronized(this@AppComposition.lock) {
                        if (activeSearch == receipt) activeSearch = null
                    }
                }
                return result
            }
        }

        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.pause(sessionGeneration, phaseEpoch)

        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = producer.resume(sessionGeneration, phaseEpoch)

        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = producer.cancel(sessionGeneration, phaseEpoch)

        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean {
            val receipt = synchronized(lock) { identities[terminal.sessionGeneration] } ?: return false
            if (!producer.acceptTerminal(terminal)) return false
            synchronized(lock) {
                if (identities[terminal.sessionGeneration] != receipt) return false
                identities.remove(terminal.sessionGeneration)
                synchronized(this@AppComposition.lock) {
                    if (activeSearch != receipt) return false
                    activeSearch = null
                    completedSearch = receipt
                }
            }
            return true
        }

        private fun createSession(
            snapshot: SearchSnapshotV1,
            lease: SearchOwnerLeaseControl,
            progress: (Long, SearchProgressV1) -> Unit,
        ): SearchMobilePhaseSession {
            val profiles = snapshot.profiles.open().use { input -> input.readNBytes(MAX_SEARCH_PROFILES_BYTES + 1) }
            if (profiles.isEmpty() || profiles.size > MAX_SEARCH_PROFILES_BYTES) throw IOException("search_profile_size")
            val digest = coreLibraryDigest(app)
            val manifest = Mobile.buildDefaultSearchManifest(profiles, digest)
            val cleanupMillis = Mobile.searchManifestCleanupMillis(manifest)
            val manager = requireNotNull(app.getSystemService(ConnectivityManager::class.java))
            val network = when (val opened = openAndroidNetworkOwner(
                manager,
                leaseCurrent = lease::mayLoadMobile,
                stopRoundAndDns = { producer.ownerLost(snapshot.sessionGeneration) },
            )) {
                is NetworkOwnerOpen.Ready -> opened
                else -> throw IOException("search_resolver_unavailable")
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
                    )
                },
                reportProgress = { progress(snapshot.sessionGeneration, it) },
            )
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

    private fun coreLibraryDigest(context: Context): String {
        val library = File(context.applicationInfo.nativeLibraryDir, "libgojni.so")
        if (!library.isFile || library.length() !in 1..MAX_GOMOBILE_LIBRARY_BYTES) throw IOException("search_core_identity_unavailable")
        val hash = MessageDigest.getInstance("SHA-256")
        library.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        const val MAX_GOMOBILE_LIBRARY_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_CLEANUP_MILLIS = 10_000L
        const val WORKER_FRAME_HEADER_BYTES = 16
    }
}
