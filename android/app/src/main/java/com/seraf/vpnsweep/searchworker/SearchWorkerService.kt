package com.seraf.vpnsweep.searchworker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1

internal object SearchWorkerIpcV2 {
    const val DESCRIPTOR = "com.seraf.vpnsweep.searchworker.v2"
    const val ADMIT = IBinder.FIRST_CALL_TRANSACTION
    const val CONFIRM = IBinder.FIRST_CALL_TRANSACTION + 1
    const val START = IBinder.FIRST_CALL_TRANSACTION + 2
    const val STOP = IBinder.FIRST_CALL_TRANSACTION + 3
    const val PENDING_CONFIRMATION = 0
    const val REJECTED = 1
    const val RESOLVER_UNAVAILABLE = 2
    const val CONFIRMED = 3
}

internal class SearchWorkerService : Service() {
    @Volatile private var coordinatorPid = -1
    private val admission = WorkerAdmission {
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(SearchWorkerIpcV2.DESCRIPTOR)
                return true
            }
            if (Binder.getCallingUid() != Process.myUid() || reply == null) return false
            return when (code) {
                SearchWorkerIpcV2.ADMIT -> receiveAdmission(data, reply)
                SearchWorkerIpcV2.CONFIRM -> confirmAdmission(data, reply)
                SearchWorkerIpcV2.START -> rejectStart(data, reply)
                SearchWorkerIpcV2.STOP -> {
                    data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
                    data.enforceNoDataAvail()
                    admission.revoke()
                    reply.writeNoException()
                    reply.writeInt(SearchWorkerIpcV2.REJECTED)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint

    override fun onUnbind(intent: Intent?): Boolean {
        admission.revoke()
        return false
    }

    override fun onDestroy(): Unit {
        admission.revoke()
        super.onDestroy()
    }

    private fun receiveAdmission(data: Parcel, reply: Parcel): Boolean {
        val callerPid = Binder.getCallingPid()
        var received: AndroidReceivedLease? = null
        val accepted = try {
            data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
            val owner = data.readStrongBinder()
            val identity = WorkerIdentityV1(data.readLong(), data.readLong().toULong(), data.readInt(), data.readLong())
            val network = SearchNetworkV1(data.readLong(), data.readLong())
            val descriptor = data.readTypedObject(ParcelFileDescriptor.CREATOR)
            if (descriptor != null) received = AndroidReceivedLease(WorkerLeaseDuplicate(descriptor))
            data.enforceNoDataAvail()
            val lease = received
            if (owner == null || lease == null) {
                admission.reject(lease)
                false
            } else {
                admission.admit(identity, network, lease, BinderClient(owner))
            }
        } catch (_: Exception) {
            admission.reject(received)
            false
        }
        if (accepted) coordinatorPid = callerPid
        reply.writeNoException()
        reply.writeInt(if (accepted) SearchWorkerIpcV2.PENDING_CONFIRMATION else SearchWorkerIpcV2.REJECTED)
        return true
    }

    private fun confirmAdmission(data: Parcel, reply: Parcel): Boolean {
        val accepted = try {
            data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
            val identity = WorkerIdentityV1(data.readLong(), data.readLong().toULong(), data.readInt(), data.readLong())
            val network = SearchNetworkV1(data.readLong(), data.readLong())
            data.enforceNoDataAvail()
            Binder.getCallingPid() == coordinatorPid && admission.confirm(identity, network)
        } catch (_: Exception) {
            false
        }
        if (!accepted) admission.revoke()
        reply.writeNoException()
        reply.writeInt(if (accepted) SearchWorkerIpcV2.CONFIRMED else SearchWorkerIpcV2.REJECTED)
        return true
    }

    private fun rejectStart(data: Parcel, reply: Parcel): Boolean {
        val allowed = try {
            data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
            val identity = WorkerIdentityV1(data.readLong(), data.readLong().toULong(), data.readInt(), data.readLong())
            val network = SearchNetworkV1(data.readLong(), data.readLong())
            data.enforceNoDataAvail()
            admission.permits(identity, network)
        } catch (_: Exception) {
            false
        }
        reply.writeNoException()
        reply.writeInt(if (allowed) SearchWorkerIpcV2.RESOLVER_UNAVAILABLE else SearchWorkerIpcV2.REJECTED)
        return true
    }

    private inner class AndroidReceivedLease(private val duplicate: WorkerLeaseDuplicate) : ReceivedWorkerLease {
        override fun validate(): Boolean = duplicate.fileAndEnvironmentValid(this@SearchWorkerService)
        override fun close(): Unit = duplicate.close()
    }

    private class BinderClient(private val binder: IBinder) : ClientDeathHandle {
        private var recipient: IBinder.DeathRecipient? = null

        override fun link(onDeath: () -> Unit): Boolean = try {
            val receiver = IBinder.DeathRecipient { onDeath() }
            recipient = receiver
            binder.linkToDeath(receiver, 0)
            true
        } catch (_: RemoteException) {
            false
        }

        override fun isAlive(): Boolean = binder.isBinderAlive
    }
}
