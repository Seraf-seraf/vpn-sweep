package com.seraf.vpnsweep.searchworker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal sealed interface WorkerConfirmationWait {
    class Confirmed(val binder: IBinder) : WorkerConfirmationWait
    class DeathPending(val binder: IBinder) : WorkerConfirmationWait
    class NotAlive(val binder: IBinder) : WorkerConfirmationWait
    data object Deadline : WorkerConfirmationWait
    data object SupervisedStop : WorkerConfirmationWait
    data object Unknown : WorkerConfirmationWait
}

internal sealed interface WorkerBinderObservation {
    class Confirmed(val binder: IBinder) : WorkerBinderObservation
    class DeathPending(val binder: IBinder) : WorkerBinderObservation
    class NotAlive(val binder: IBinder) : WorkerBinderObservation
    data object Unknown : WorkerBinderObservation
}

internal enum class WorkerBinderValidation { VALID, NOT_ALIVE, UNKNOWN }

internal fun workerConfirmation(observation: WorkerBinderObservation): WorkerConfirmationWait = when (observation) {
    is WorkerBinderObservation.Confirmed -> WorkerConfirmationWait.Confirmed(observation.binder)
    is WorkerBinderObservation.DeathPending -> WorkerConfirmationWait.DeathPending(observation.binder)
    is WorkerBinderObservation.NotAlive -> WorkerConfirmationWait.NotAlive(observation.binder)
    WorkerBinderObservation.Unknown -> WorkerConfirmationWait.Unknown
}

internal fun observeAdmittedWorker(
    binder: IBinder,
    state: () -> CoordinatorState?,
    validate: () -> WorkerBinderValidation,
): WorkerBinderObservation {
    fun observed(): WorkerBinderObservation = when (state()) {
        CoordinatorState.CONFIRMED -> WorkerBinderObservation.Confirmed(binder)
        CoordinatorState.DEATH_PENDING -> WorkerBinderObservation.DeathPending(binder)
        else -> WorkerBinderObservation.Unknown
    }
    val first = observed()
    if (first !is WorkerBinderObservation.Confirmed) return first
    return when (try { validate() } catch (_: Exception) { WorkerBinderValidation.UNKNOWN } catch (_: LinkageError) { WorkerBinderValidation.UNKNOWN }) {
        WorkerBinderValidation.VALID -> observed()
        WorkerBinderValidation.NOT_ALIVE -> when (val latest = observed()) {
            is WorkerBinderObservation.Confirmed -> WorkerBinderObservation.NotAlive(binder)
            else -> latest
        }
        WorkerBinderValidation.UNKNOWN -> WorkerBinderObservation.Unknown
    }
}

internal interface WorkerCoordinatorPort : PreconnectWorkerStop {
    fun registerConnectedRecovery(recover: () -> Boolean): Boolean = false
    fun bind(): Boolean
    fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait
    fun confirmedBinder(): IBinder?
    fun observeBinder(): WorkerBinderObservation = confirmedBinder()?.let(WorkerBinderObservation::Confirmed)
        ?: WorkerBinderObservation.Unknown
    fun awaitRetired(deadlineElapsedMillis: Long): Boolean
    fun quarantine(): Unit
    fun stop(): Unit
}

internal fun supervisorStopAllowed(
    stopped: Boolean,
    supervised: Boolean,
    bound: Boolean,
    ownsReservation: Boolean,
    connected: Boolean,
    handshakeStarted: Boolean,
    hasAdmittedBinder: Boolean,
    state: CoordinatorState,
): Boolean {
    if (stopped || supervised || !bound || !ownsReservation) return false
    return !(connected || handshakeStarted) ||
        (connected && handshakeStarted && hasAdmittedBinder &&
            (state == CoordinatorState.ADMITTING || state == CoordinatorState.CONFIRMED ||
                state == CoordinatorState.REMOTE_PENDING || state == CoordinatorState.DEATH_PENDING))
}

internal fun <T : Any> observedWorkerMatches(admitted: T?, pending: T?, remoteMatches: (T) -> Boolean): Boolean =
    admitted != null && admitted === pending && remoteMatches(admitted)

internal fun recoverConnectedDisconnect(recover: () -> Boolean, fail: () -> Unit): Unit {
    val started = try { recover() } catch (_: Exception) { false } catch (_: LinkageError) { false }
    if (!started) fail()
}

internal class SearchWorkerCoordinator(
    context: Context,
    private val lease: SearchOwnerLeaseControl,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val owner: NetworkEpochOwner,
    localDrain: SearchWorkerLocalDrain,
    private val scheduleRecoveryClose: (() -> Unit) -> Boolean,
) : WorkerCoordinatorPort {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val ownerCallbacks = OwnerCallbackGate(lock, localDrain, identity.sessionGeneration)
    private var prepared: PreparedWorkerLeaseDuplicate? = null
    private var passedToAdmission = false
    private val handshakeFinished = CountDownLatch(1)
    private var admittedBinder: IBinder? = null
    private var handshakeRemote: AndroidRemote? = null
    private var connectedRecovery: (() -> Boolean)? = null
    private val admissionLease = object : CoordinatorLease {
        override fun duplicate(): SearchLeaseDuplicateV1? = when (val observed = observeDuplicate()) {
            is WorkerDuplicateObservation.Available -> observed.value
            WorkerDuplicateObservation.Fenced, WorkerDuplicateObservation.Unknown -> null
        }
        override fun observeDuplicate(): WorkerDuplicateObservation = synchronized(lock) {
            when {
                stopped && supervisedStop -> WorkerDuplicateObservation.Fenced
                !bindCalled || stopped || passedToAdmission -> WorkerDuplicateObservation.Unknown
                else -> prepared?.let { passedToAdmission = true; WorkerDuplicateObservation.Available(it) } ?: WorkerDuplicateObservation.Unknown
            }
        }
        override fun anchorHeldForWorker(): Boolean = lease.anchorHeldForWorker()
        override fun observeAnchor(): WorkerAnchorObservation = lease.observeAnchor()
        override fun sealForRecovery(): Boolean = lease.sealForRecovery()
        override fun workerDeathConfirmed(): Unit = lease.workerDeathConfirmed()
        override fun quarantine(): Unit = lease.quarantine()
    }
    private val admission = CoordinatorAdmission(admissionLease, identity, network, owner, ::requestConnectedRecovery)
    private var workerPid = -1
    private val currentCheck = OwnerCurrentCheck(
        Process.myUid(), Process.myPid(), identity, network,
        { synchronized(lock) { if (stopped) -1 else workerPid } },
        { admission.state() == CoordinatorState.CONFIRMED },
        lease::anchorHeldForWorker,
        owner::verifyOnCallback,
    )
    private val ownerBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(SearchOwnerIpcV1.DESCRIPTOR)
                return true
            }
            if (code != SearchOwnerIpcV1.CURRENT) return super.onTransact(code, data, reply, flags)
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
            val entry = ownerCallbacks.begin() ?: return try {
                reply.writeNoException()
                reply.writeInt(0)
                true
            } catch (_: Exception) { false }
            try {
                val uid = Binder.getCallingUid()
                val pid = Binder.getCallingPid()
                val valid = try {
                    data.enforceInterface(SearchOwnerIpcV1.DESCRIPTOR)
                    val requested = WorkerIdentityV1(data.readLong(), data.readLong().toULong(), data.readInt(), data.readLong())
                    val selected = SearchNetworkV1(data.readLong(), data.readLong())
                    data.enforceNoDataAvail()
                    currentCheck.permits(uid, pid, requested, selected)
                } catch (_: Exception) {
                    false
                } catch (_: LinkageError) {
                    false
                }
                if (!valid) rejectCurrent(uid, pid)
                val sent = ownerCallbacks.respond(entry, valid) { permitted ->
                    reply.writeNoException()
                    reply.writeInt(if (permitted) 1 else 0)
                }
                if (!sent) rejectCurrent(uid, pid)
                return sent
            } finally { ownerCallbacks.finish(entry) }
        }
    }
    private var bindCalled = false
    private var reservationOwned = false
    private var bindResult: Boolean? = null
    private var stopped = false
    private var supervisedStop = false
    private var connected = false
    private var handshakeStarted = false
    private var pendingBinder: IBinder? = null
    private var unbindRequested = false
    private var unbindStarted = false
    private var unbindResult: Boolean? = null
    private val unbindWaiters = mutableListOf<(Boolean) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?): Unit {
            val disposition = synchronized(lock) {
                if (stopped && supervisedStop) {
                    0
                } else if (stopped || connected || service == null || service is Binder) {
                    1
                } else {
                    connected = true
                    pendingBinder = service
                    2
                }
            }
            if (disposition == 1) failClosed() else if (disposition == 2) startHandshake()
        }

        override fun onServiceDisconnected(name: ComponentName?): Unit = disconnected()
        override fun onBindingDied(name: ComponentName?): Unit = disconnected()
        override fun onNullBinding(name: ComponentName?): Unit {
            if (!synchronized(lock) { stopped && supervisedStop }) failClosed()
        }
    }

    fun prepare(): PreparedWorkerLeaseDuplicate? = synchronized(lock) {
        if (bindCalled || stopped || reservationOwned || prepared != null) return null
        if (!lease.reserveWorker()) return null
        reservationOwned = true
        val duplicate = lease.duplicate()
        if (duplicate == null) {
            lease.quarantine()
            stopLocked()
            return null
        }
        try {
            PreparedWorkerLeaseDuplicate(duplicate, {
                lease.releaseUnstartedReservation()
                synchronized(lock) { reservationOwned = false }
            }, lease::quarantine).also { prepared = it }
        } catch (_: Throwable) {
            try { duplicate.close() } catch (_: Throwable) { }
            lease.quarantine()
            stopLocked()
            null
        }
    }

    override fun bind(): Boolean {
        synchronized(lock) {
            if (bindCalled || stopped || !reservationOwned) return false
            if (prepared?.beginBind() != true) return false
            bindCalled = true
        }
        val outcome: Boolean? = try {
            appContext.bindService(Intent(appContext, SearchWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        val possiblyBound = outcome != false
        val unbind = synchronized(lock) {
            bindResult = possiblyBound
            if (outcome != true) stopLocked()
            if (unbindRequested && !unbindStarted) {
                unbindStarted = true
                true
            } else {
                false
            }
        }
        if (unbind) dispatchUnbind(possiblyBound)
        if (outcome != true) {
            if (!synchronized(lock) { supervisedStop }) failClosed() else handshakeFinished.countDown()
            return false
        }
        startHandshake()
        return true
    }

    fun state(): CoordinatorState = admission.state()

    override fun registerConnectedRecovery(recover: () -> Boolean): Boolean = synchronized(lock) {
        if (bindCalled || connectedRecovery != null) false else { connectedRecovery = recover; true }
    }

    private fun requestConnectedRecovery(): Boolean {
        val recover = synchronized(lock) { connectedRecovery } ?: return false
        return try { recover() } catch (_: Exception) { false } catch (_: LinkageError) { false }
    }

    override fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait {
        val now = SystemClock.elapsedRealtime()
        if (now < 0) return WorkerConfirmationWait.Unknown
        if (deadlineElapsedMillis <= now) return WorkerConfirmationWait.Deadline
        val remaining = deadlineElapsedMillis - now
        val signalled = try {
            handshakeFinished.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return WorkerConfirmationWait.Unknown
        }
        if (!signalled) return WorkerConfirmationWait.Deadline
        if (synchronized(lock) { stopped && supervisedStop }) return WorkerConfirmationWait.SupervisedStop
        return workerConfirmation(observeBinder())
    }

    override fun confirmedBinder(): IBinder? = (observeBinder() as? WorkerBinderObservation.Confirmed)?.binder

    override fun observeBinder(): WorkerBinderObservation {
        val candidate = synchronized(lock) {
            if (stopped) return WorkerBinderObservation.Unknown
            val admitted = admittedBinder
            if (admitted == null) {
                val pending = pendingBinder
                val remote = handshakeRemote
                if (pending != null && remote != null && remote.matches(pending) && admission.hasLinkedRemote(remote)) {
                    when (admission.state()) {
                        CoordinatorState.DEATH_PENDING -> return WorkerBinderObservation.DeathPending(pending)
                        CoordinatorState.REMOTE_PENDING -> return WorkerBinderObservation.NotAlive(pending)
                        else -> Unit
                    }
                }
                return WorkerBinderObservation.Unknown
            }
            admitted
        }
        return observeAdmittedWorker(candidate, {
            synchronized(lock) { if (!stopped && admittedBinder === candidate) admission.state() else null }
        }) {
            when {
                !lease.anchorHeldForWorker() -> WorkerBinderValidation.UNKNOWN
                !candidate.isBinderAlive -> WorkerBinderValidation.NOT_ALIVE
                !owner.verifyOnCallback(network) -> WorkerBinderValidation.UNKNOWN
                !candidate.isBinderAlive -> WorkerBinderValidation.NOT_ALIVE
                !lease.anchorHeldForWorker() -> WorkerBinderValidation.UNKNOWN
                else -> WorkerBinderValidation.VALID
            }
        }
    }

    override fun awaitRetired(deadlineElapsedMillis: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now >= 0 && deadlineElapsedMillis > now &&
            admission.awaitRetirement(TimeUnit.MILLISECONDS.toNanos(deadlineElapsedMillis - now))
    }

    override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = admission.observeRetirement(completed)

    override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = admission.ownsRetirement(proof, identity)

    override fun beginObservedStop(): Boolean {
        val candidate = synchronized(lock) {
            val binder = admittedBinder ?: return false
            val remote = handshakeRemote ?: return false
            if (supervisedStop || bindResult != true || !reservationOwned || !connected || !handshakeStarted ||
                !observedWorkerMatches(binder, pendingBinder, remote::matches)
            ) return false
            stopLocked()
            remote
        }
        if (!lease.mayLoadMobile()) return false
        return admission.beginObservedStop(candidate)
    }

    override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean {
        val candidate = synchronized(lock) {
            if (!stopped && connected && handshakeStarted && pendingBinder != null && admittedBinder == null) handshakeRemote else null
        }
        if (candidate != null && !admission.awaitLinkedRemote(candidate)) return false
        val duplicate = synchronized(lock) {
            val tracked = admittedBinder != null && admittedBinder === pendingBinder ||
                (handshakeRemote?.let { it.matches(pendingBinder) && admission.hasLinkedRemote(it) } == true)
            if (!supervisorStopAllowed(stopped, supervisedStop, bindResult == true, reservationOwned,
                    connected, handshakeStarted, tracked, admission.state())) return false
            val value = prepared ?: return false
            supervisedStop = true
            stopLocked()
            value
        }
        handshakeFinished.countDown()
        if (!lease.mayLoadMobile()) {
            failClosed()
            return false
        }
        val stop = SupervisedCoordinatorStop(
            admission = admission,
            requestUnbind = { completion -> requestUnbind { success ->
                completion(success || synchronized(lock) { bindResult == false })
            } },
            scheduleClose = scheduleRecoveryClose,
            closeDuplicate = { try { duplicate.close(); true } catch (_: Exception) { false } catch (_: LinkageError) { false } },
            unbound = unbound,
        )
        if (!stop.begin(attempt)) {
            failClosed()
            return false
        }
        return true
    }

    override fun replaceSupervisorAttempt(attempt: Any): Boolean = admission.replaceSupervisorAttempt(attempt)

    override fun supervisorDied(proof: SupervisorDeathProof): Boolean = admission.supervisorDied(proof)

    override fun quarantine(): Unit = failClosed()

    override fun stop(): Unit {
        val (ownsReservation, unstarted) = synchronized(lock) {
            stopLocked()
            if (!bindCalled) bindResult = false
            reservationOwned to if (!bindCalled) prepared else null
        }
        if (!ownsReservation) return
        if (unstarted != null) {
            handshakeFinished.countDown()
            try {
                Thread({ try { unstarted.close() } catch (_: Exception) { lease.quarantine() } }, "search-unstarted-close").start()
            } catch (_: Exception) { lease.quarantine() }
            return
        }
        admission.stop()
        requestUnbind { success ->
            val neverBound = synchronized(lock) { supervisedStop && bindResult == false }
            if (!success && !neverBound) admission.disconnected()
        }
    }

    private fun startHandshake(): Unit {
        val (binder, remote) = synchronized(lock) {
            if (bindResult != true || stopped || handshakeStarted) return
            val candidate = pendingBinder ?: return
            handshakeStarted = true
            val remote = AndroidRemote(candidate).also { handshakeRemote = it }
            candidate to remote
        }
        try {
            Thread({
                try {
                    val accepted = admission.connect(remote)
                    synchronized(lock) {
                        if (accepted && !stopped && pendingBinder === binder) admittedBinder = binder
                    }
                } finally { handshakeFinished.countDown() }
            }, "search-worker-admission").start()
        } catch (_: Exception) {
            failClosed()
        }
    }

    private fun disconnected(): Unit {
        val stopping = synchronized(lock) { stopped }
        if (stopping) {
            requestUnbind { success ->
                val neverBound = synchronized(lock) { supervisedStop && bindResult == false }
                if (!success && !neverBound) admission.disconnected()
            }
        } else recoverConnectedDisconnect(::requestConnectedRecovery, ::failClosed)
    }

    private fun failClosed(): Unit {
        synchronized(lock) { stopLocked() }
        handshakeFinished.countDown()
        admission.disconnected()
        requestUnbind { }
    }

    private fun rejectCurrent(uid: Int, pid: Int): Unit = rejectCurrentIfActive(lock,
        { uid == Process.myUid() && !stopped && workerPid > 0 && workerPid == pid },
        { stopLocked(); lease.quarantine() },
    ) {
        handshakeFinished.countDown()
        admission.disconnected()
        requestUnbind { }
    }

    private fun stopLocked(): Unit {
        stopped = true
        ownerCallbacks.seal()
    }

    private fun requestUnbind(completed: (Boolean) -> Unit): Boolean {
        var result: Boolean? = null
        var dispatch: Boolean? = null
        synchronized(lock) {
            if (unbindResult != null) {
                result = unbindResult
            } else {
                unbindWaiters.add(completed)
                unbindRequested = true
                val bound = bindResult
                if (bound != null && !unbindStarted) {
                    unbindStarted = true
                    dispatch = bound
                }
            }
        }
        result?.let(completed)
        dispatch?.let(::dispatchUnbind)
        return true
    }

    private fun dispatchUnbind(bound: Boolean): Unit {
        if (!bound) {
            unbindCompleted(false)
            return
        }
        try {
            Thread({
                val success = try {
                    appContext.unbindService(connection)
                    true
                } catch (_: Exception) {
                    false
                }
                unbindCompleted(success)
            }, "search-worker-unbind").start()
        } catch (_: Exception) {
            unbindCompleted(false)
        }
    }

    private fun unbindCompleted(success: Boolean): Unit {
        val waiters = synchronized(lock) {
            if (unbindResult != null) return
            unbindResult = success
            unbindWaiters.toList().also { unbindWaiters.clear() }
        }
        waiters.forEach { it(success) }
    }

    private inner class AndroidRemote(private val binder: IBinder) : CoordinatorRemote {
        private var recipient: IBinder.DeathRecipient? = null

        fun matches(candidate: IBinder?): Boolean = binder === candidate

        override fun linkDeath(onDeath: () -> Unit): Boolean = try {
            val receiver = IBinder.DeathRecipient { onDeath() }
            recipient = receiver
            binder.linkToDeath(receiver, 0)
            true
        } catch (_: Exception) {
            false
        }

        override fun isAlive(): Boolean = binder.isBinderAlive

        override fun admit(
            identity: WorkerIdentityV1,
            network: SearchNetworkV1,
            duplicate: SearchLeaseDuplicateV1,
        ): Boolean = observeAdmission(identity, network, duplicate) == WorkerAdmissionObservation.ADMITTED

        override fun observeAdmission(
            identity: WorkerIdentityV1,
            network: SearchNetworkV1,
            duplicate: SearchLeaseDuplicateV1,
        ): WorkerAdmissionObservation {
            val prepared = duplicate as? PreparedWorkerLeaseDuplicate ?: return WorkerAdmissionObservation.UNKNOWN
            val owner = when (val transfer = admission.observeTransfer(prepared)) {
                is WorkerTransferObservation.Claimed -> transfer.value
                WorkerTransferObservation.Fenced -> return WorkerAdmissionObservation.FENCED
                WorkerTransferObservation.Unknown -> return WorkerAdmissionObservation.UNKNOWN
            }
            val descriptor = (owner as? WorkerLeaseDuplicate)?.descriptor
            if (descriptor == null) {
                prepared.endTransfer()
                return WorkerAdmissionObservation.UNKNOWN
            }
            var data: Parcel? = null
            var reply: Parcel? = null
            return try {
                val request = Parcel.obtain().also { data = it }
                val response = Parcel.obtain().also { reply = it }
                request.writeInterfaceToken(SearchWorkerIpcV2.DESCRIPTOR)
                request.writeStrongBinder(ownerBinder)
                request.writeIdentity(identity, network)
                request.writeTypedObject(descriptor, 0)
                if (!workerTransact { binder.transact(SearchWorkerIpcV2.ADMIT, request, response, 0) }) return WorkerAdmissionObservation.UNKNOWN
                response.readException()
                if (response.readInt() != SearchWorkerIpcV2.PENDING_CONFIRMATION) return WorkerAdmissionObservation.UNKNOWN
                val pid = response.readInt()
                response.enforceNoDataAvail()
                if (pid <= 0 || pid == Process.myPid()) return WorkerAdmissionObservation.UNKNOWN
                synchronized(lock) {
                    when {
                        stopped && supervisedStop -> WorkerAdmissionObservation.FENCED
                        stopped || pendingBinder !== binder || workerPid != -1 -> WorkerAdmissionObservation.UNKNOWN
                        else -> {
                            workerPid = pid
                            WorkerAdmissionObservation.ADMITTED
                        }
                    }
                }
            } finally {
                try {
                    data?.recycle()
                    reply?.recycle()
                } finally {
                    prepared.endTransfer()
                }
            }
        }

        override fun confirm(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean =
            transact(SearchWorkerIpcV2.CONFIRM, SearchWorkerIpcV2.CONFIRMED) { it.writeIdentity(identity, network) }

        override fun requestUnbind(completed: (Boolean) -> Unit): Boolean = this@SearchWorkerCoordinator.requestUnbind(completed)

        private fun transact(code: Int, expected: Int, write: (Parcel) -> Unit): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(SearchWorkerIpcV2.DESCRIPTOR)
                write(data)
                if (!workerTransact { binder.transact(code, data, reply, 0) }) return false
                reply.readException()
                val result = reply.readInt()
                reply.enforceNoDataAvail()
                result == expected
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}

private fun Parcel.writeIdentity(identity: WorkerIdentityV1, network: SearchNetworkV1): Unit {
    writeLong(identity.sessionGeneration)
    writeLong(identity.attempt.toLong())
    writeInt(identity.round)
    writeLong(identity.workerGeneration)
    writeLong(network.identity)
    writeLong(network.version)
}
