package com.seraf.vpnsweep.searchworker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1

internal class SearchWorkerCoordinator(
    context: Context,
    private val lease: SearchOwnerLease,
    identity: WorkerIdentityV1,
    network: SearchNetworkV1,
    owner: RoundOwner,
) {
    private val appContext = context.applicationContext
    private val admission = CoordinatorAdmission(lease, identity, network, owner)
    private val ownerBinder = Binder()
    private val lock = Any()
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

    fun bind(): Boolean {
        synchronized(lock) {
            if (bindCalled || stopped) return false
            bindCalled = true
            if (!lease.reserveWorker()) {
                stopped = true
                bindResult = false
                return false
            }
            reservationOwned = true
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

    fun stop(): Unit {
        val ownsReservation = synchronized(lock) {
            stopped = true
            if (!bindCalled) bindResult = false
            reservationOwned
        }
        if (!ownsReservation) return
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
            Thread({ admission.connect(AndroidRemote(binder)) }, "search-worker-admission").start()
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
            val descriptor = (duplicate as? WorkerLeaseDuplicate)?.descriptor ?: return false
            return transact(SearchWorkerIpcV2.ADMIT, SearchWorkerIpcV2.PENDING_CONFIRMATION) { data ->
                data.writeStrongBinder(ownerBinder)
                data.writeIdentity(identity, network)
                data.writeTypedObject(descriptor, 0)
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
