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

internal class SearchWorkerCoordinator(
    context: Context,
    private val lease: SearchOwnerLease,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val owner: NetworkEpochOwner,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var prepared: PreparedWorkerLeaseDuplicate? = null
    private var passedToAdmission = false
    private val handshakeFinished = CountDownLatch(1)
    private var admittedBinder: IBinder? = null
    private val admissionLease = object : CoordinatorLease {
        override fun duplicate(): SearchLeaseDuplicateV1? = synchronized(lock) {
            if (!bindCalled || passedToAdmission) null else prepared?.also { passedToAdmission = true }
        }
        override fun anchorHeldForWorker(): Boolean = lease.anchorHeldForWorker()
        override fun workerDeathConfirmed(): Unit = lease.workerDeathConfirmed()
        override fun quarantine(): Unit = lease.quarantine()
    }
    private val admission = CoordinatorAdmission(admissionLease, identity, network, owner)
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
            return try {
                reply.writeNoException()
                reply.writeInt(if (valid) 1 else 0)
                true
            } catch (_: Exception) {
                rejectCurrent(uid, pid)
                false
            }
        }
    }
    private var bindCalled = false
    private var reservationOwned = false
    private var bindResult: Boolean? = null
    private var stopped = false
    private var connected = false
    private var handshakeStarted = false
    private var pendingBinder: IBinder? = null
    private var unbindRequested = false
    private var unbindStarted = false
    private var unbindResult: Boolean? = null
    private val unbindWaiters = mutableListOf<(Boolean) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?): Unit {
            val invalid = synchronized(lock) {
                if (stopped || connected || service == null || service is Binder) {
                    true
                } else {
                    connected = true
                    pendingBinder = service
                    false
                }
            }
            if (invalid) failClosed() else startHandshake()
        }

        override fun onServiceDisconnected(name: ComponentName?): Unit = disconnected()
        override fun onBindingDied(name: ComponentName?): Unit = disconnected()
        override fun onNullBinding(name: ComponentName?): Unit = failClosed()
    }

    fun prepare(): PreparedWorkerLeaseDuplicate? = synchronized(lock) {
        if (bindCalled || stopped || reservationOwned || prepared != null) return null
        if (!lease.reserveWorker()) return null
        reservationOwned = true
        val duplicate = lease.duplicate()
        if (duplicate == null) {
            lease.quarantine()
            stopped = true
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
            stopped = true
            null
        }
    }

    fun bind(): Boolean {
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
            if (outcome != true) stopped = true
            if (unbindRequested && !unbindStarted) {
                unbindStarted = true
                true
            } else {
                false
            }
        }
        if (unbind) dispatchUnbind(possiblyBound)
        if (outcome != true) {
            failClosed()
            return false
        }
        startHandshake()
        return true
    }

    fun state(): CoordinatorState = admission.state()

    fun awaitConfirmed(deadlineElapsedMillis: Long): IBinder? {
        val now = SystemClock.elapsedRealtime()
        if (now < 0 || deadlineElapsedMillis <= now) return null
        val remaining = deadlineElapsedMillis - now
        val signalled = try {
            handshakeFinished.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (signalled) confirmedBinder() else null
    }

    fun confirmedBinder(): IBinder? {
        val candidate = synchronized(lock) { if (stopped) null else admittedBinder } ?: return null
        val valid = try {
            admission.state() == CoordinatorState.CONFIRMED && lease.anchorHeldForWorker() && candidate.isBinderAlive &&
                owner.verifyOnCallback(network) && candidate.isBinderAlive && lease.anchorHeldForWorker() &&
                admission.state() == CoordinatorState.CONFIRMED
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        return if (valid) synchronized(lock) { if (!stopped && admittedBinder === candidate) candidate else null } else null
    }

    fun awaitRetired(deadlineElapsedMillis: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now >= 0 && deadlineElapsedMillis > now &&
            admission.awaitRetirement(TimeUnit.MILLISECONDS.toNanos(deadlineElapsedMillis - now))
    }

    fun quarantine(): Unit = failClosed()

    fun stop(): Unit {
        val (ownsReservation, unstarted) = synchronized(lock) {
            stopped = true
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
        requestUnbind { success -> if (!success) admission.disconnected() }
    }

    private fun startHandshake(): Unit {
        val binder = synchronized(lock) {
            if (bindResult != true || stopped || handshakeStarted) return
            val candidate = pendingBinder ?: return
            handshakeStarted = true
            candidate
        }
        try {
            Thread({
                try {
                    val accepted = admission.connect(AndroidRemote(binder))
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
            requestUnbind { success -> if (!success) admission.disconnected() }
        } else {
            failClosed()
        }
    }

    private fun failClosed(): Unit {
        synchronized(lock) { stopped = true }
        handshakeFinished.countDown()
        admission.disconnected()
        requestUnbind { }
    }

    private fun rejectCurrent(uid: Int, pid: Int): Unit = rejectCurrentIfActive(lock,
        { uid == Process.myUid() && !stopped && workerPid > 0 && workerPid == pid },
        { stopped = true; lease.quarantine() },
    ) {
        handshakeFinished.countDown()
        admission.disconnected()
        requestUnbind { }
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

        override fun linkDeath(onDeath: () -> Unit): Boolean = try {
            val receiver = IBinder.DeathRecipient { onDeath() }
            recipient = receiver
            binder.linkToDeath(receiver, 0)
            binder.isBinderAlive
        } catch (_: Exception) {
            false
        }

        override fun isAlive(): Boolean = binder.isBinderAlive

        override fun admit(
            identity: WorkerIdentityV1,
            network: SearchNetworkV1,
            duplicate: SearchLeaseDuplicateV1,
        ): Boolean {
            val prepared = duplicate as? PreparedWorkerLeaseDuplicate ?: return false
            val owner = prepared.beginTransfer() ?: return false
            val descriptor = (owner as? WorkerLeaseDuplicate)?.descriptor
            if (descriptor == null) {
                prepared.endTransfer()
                return false
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
                if (!binder.transact(SearchWorkerIpcV2.ADMIT, request, response, 0)) return false
                response.readException()
                if (response.readInt() != SearchWorkerIpcV2.PENDING_CONFIRMATION) return false
                val pid = response.readInt()
                response.enforceNoDataAvail()
                if (pid <= 0 || pid == Process.myPid()) return false
                synchronized(lock) {
                    if (stopped || pendingBinder !== binder || workerPid != -1) false else {
                        workerPid = pid
                        true
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
                if (!binder.transact(code, data, reply, 0)) return false
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
