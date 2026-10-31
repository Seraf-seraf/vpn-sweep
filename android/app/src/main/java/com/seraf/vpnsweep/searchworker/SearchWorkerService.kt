package com.seraf.vpnsweep.searchworker

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.seraf.vpnsweep.core.mobile.AndroidNetwork
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.SearchEmbeddedWorker
import com.seraf.vpnsweep.searchnetwork.SelectedNetworkDns
import com.seraf.vpnsweep.searchnetwork.selectedNetworkDns
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

internal object SearchWorkerIpcV2 {
    const val DESCRIPTOR = "com.seraf.vpnsweep.searchworker.v2"
    const val ADMIT = IBinder.FIRST_CALL_TRANSACTION
    const val CONFIRM = IBinder.FIRST_CALL_TRANSACTION + 1
    const val START = IBinder.FIRST_CALL_TRANSACTION + 2
    const val STOP = IBinder.FIRST_CALL_TRANSACTION + 3
    const val OPEN_FD = IBinder.FIRST_CALL_TRANSACTION + 4
    const val CALL_FD = IBinder.FIRST_CALL_TRANSACTION + 5
    const val CANCEL_ROUND = IBinder.FIRST_CALL_TRANSACTION + 6
    const val CLOSE_ROUND = IBinder.FIRST_CALL_TRANSACTION + 7
    const val PENDING_CONFIRMATION = 0
    const val REJECTED = 1
    const val RESOLVER_UNAVAILABLE = 2
    const val CONFIRMED = 3
}

internal object SearchOwnerIpcV1 {
    const val DESCRIPTOR = "com.seraf.vpnsweep.searchowner.v1"
    const val CURRENT = IBinder.FIRST_CALL_TRANSACTION
}

private const val MAX_PROFILE_BYTES = 8 * 1024 * 1024
private const val FRAME_HEADER_BYTES = 16L
private const val SEARCH_EXECUTION_WIRED = false
private class WorkerFrameDeadline : IOException("worker_deadline")

internal class WorkerOpenFrame(
    val attemptDeadline: Long,
    val profile: ByteArray,
    val parameters: ByteArray,
)

internal class WorkerCallFrame(
    val deadline: Long,
    val round: Int,
    val request: ByteArray,
)

internal fun <P, R> callWithWorkerDeadline(
    deadline: Long,
    now: () -> Long,
    acquire: () -> P,
    invoke: (P, Long) -> R,
): R {
    if (deadline <= now()) throw WorkerFrameDeadline()
    val target = acquire()
    val current = now()
    if (deadline <= current) throw WorkerFrameDeadline()
    return invoke(target, deadline - current)
}

// The descriptor itself is authenticated and closed by the caller. These
// readers consume exactly the declared frame and never interpret its contents.
internal fun readWorkerOpenFrame(input: InputStream, length: Long, now: () -> Long): WorkerOpenFrame {
    if (length < FRAME_HEADER_BYTES || length > Int.MAX_VALUE.toLong()) throw IOException("invalid_worker_frame")
    val source = DataInputStream(input)
    val deadline = source.readLong()
    val profileSize = source.readInt()
    val parameterSize = source.readInt()
    if (deadline <= now()) throw WorkerFrameDeadline()
    if (profileSize !in 1..MAX_PROFILE_BYTES || parameterSize < 1 ||
        FRAME_HEADER_BYTES + profileSize.toLong() + parameterSize.toLong() != length
    ) throw IOException("invalid_worker_frame")
    val profile = ByteArray(profileSize)
    val parameters = ByteArray(parameterSize)
    source.readFully(profile)
    source.readFully(parameters)
    if (deadline <= now()) throw WorkerFrameDeadline()
    return WorkerOpenFrame(deadline, profile, parameters)
}

internal fun readWorkerCallFrame(input: InputStream, length: Long, expectedWorkerRound: Int, now: () -> Long): WorkerCallFrame {
    if (length < FRAME_HEADER_BYTES || length > Int.MAX_VALUE.toLong()) throw IOException("invalid_worker_frame")
    val source = DataInputStream(input)
    val deadline = source.readLong()
    val round = source.readInt()
    val requestSize = source.readInt()
    if (deadline <= now()) throw WorkerFrameDeadline()
    if (expectedWorkerRound < 1 || round != expectedWorkerRound - 1 || requestSize < 1 ||
        FRAME_HEADER_BYTES + requestSize.toLong() != length
    ) throw IOException("invalid_worker_frame")
    val request = ByteArray(requestSize)
    source.readFully(request)
    if (deadline <= now()) throw WorkerFrameDeadline()
    return WorkerCallFrame(deadline, round, request)
}

internal class SearchWorkerService : Service() {
    @Volatile private var coordinatorPid = -1
    @Volatile private var coordinatorBinder: IBinder? = null
    private val roundLock = Any()
    private var openReserved = false
    private var opening = false
    private var ready = false
    private var inFlight = false
    private var closing = false
    private var cancelled = false
    private var roundUnknown = false
    private var worker: SearchEmbeddedWorker? = null
    private var networkPolicy: WorkerNetworkPolicy? = null
    private val admission = WorkerAdmission {
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
                SearchWorkerIpcV2.OPEN_FD -> if (SEARCH_EXECUTION_WIRED) openRound(data, reply) else false
                SearchWorkerIpcV2.CALL_FD -> if (SEARCH_EXECUTION_WIRED) callRound(data, reply) else false
                SearchWorkerIpcV2.CANCEL_ROUND -> if (SEARCH_EXECUTION_WIRED) cancelRound(data, reply) else false
                SearchWorkerIpcV2.CLOSE_ROUND -> if (SEARCH_EXECUTION_WIRED) closeRound(data, reply) else false
                SearchWorkerIpcV2.STOP -> {
                    data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
                    data.enforceNoDataAvail()
                    if (Binder.getCallingPid() == coordinatorPid && coordinatorPid > 0) admission.revoke()
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
            if (owner == null || owner is Binder || lease == null || callerPid <= 0 || callerPid == Process.myPid()) {
                admission.reject(lease)
                false
            } else {
                val accepted = admission.admit(identity, network, lease, BinderClient(owner))
                if (accepted) coordinatorBinder = owner
                accepted
            }
        } catch (_: Exception) {
            admission.reject(received)
            false
        }
        if (accepted) coordinatorPid = callerPid
        reply.writeNoException()
        reply.writeInt(if (accepted) SearchWorkerIpcV2.PENDING_CONFIRMATION else SearchWorkerIpcV2.REJECTED)
        if (accepted) reply.writeInt(Process.myPid())
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
            Binder.getCallingPid() == coordinatorPid && admission.permits(identity, network)
        } catch (_: Exception) {
            false
        }
        reply.writeNoException()
        reply.writeInt(if (allowed) SearchWorkerIpcV2.RESOLVER_UNAVAILABLE else SearchWorkerIpcV2.REJECTED)
        return true
    }

    private fun openRound(data: Parcel, reply: Parcel): Boolean {
        var descriptor: ParcelFileDescriptor? = null
        var reservedOpen = false
        val status = try {
            data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
            val identity = data.readWorkerIdentity()
            val network = data.readWorkerNetwork()
            val startupDeadline = data.readLong()
            val cleanupMillis = data.readLong()
            val length = data.readLong()
            descriptor = data.readTypedObject(ParcelFileDescriptor.CREATOR)
            data.enforceNoDataAvail()
            val input = descriptor ?: throw IOException("worker_frame_missing")
            descriptor = null
            if (!permitted(identity, network)) {
                input.close()
                Mobile.SearchWorkerOwnerLost.toInt()
            } else if (cleanupMillis !in 1..60_000) {
                input.close()
                Mobile.SearchWorkerUnavailable.toInt()
            } else if (remaining(startupDeadline) == 0L) {
                input.close()
                Mobile.SearchWorkerDeadline.toInt()
            } else {
                val reserved = synchronized(roundLock) {
                    if (openReserved || closing || cancelled) false else {
                        openReserved = true
                        opening = true
                        reservedOpen = true
                        true
                    }
                }
                if (!reserved) {
                    input.close()
                    synchronized(roundLock) {
                        if (cancelled) Mobile.SearchWorkerCancelled.toInt() else Mobile.SearchWorkerBusy.toInt()
                    }
                } else {
                    val frame = consumeFrame(input, length) { readWorkerOpenFrame(it, length, SystemClock::elapsedRealtime) }
                    try {
                        startEmbedded(identity, network, frame, startupDeadline, cleanupMillis)
                    } finally {
                        frame.profile.fill(0)
                        frame.parameters.fill(0)
                    }
                }
            }
        } catch (_: WorkerFrameDeadline) {
            Mobile.SearchWorkerDeadline.toInt()
        } catch (_: Exception) {
            Mobile.SearchWorkerDeliveryUnknown.toInt()
        } catch (_: LinkageError) {
            Mobile.SearchWorkerDeliveryUnknown.toInt()
        } finally {
            try { descriptor?.close() } catch (_: Exception) { admission.revoke() }
        }
        synchronized(roundLock) {
            if (status == Mobile.SearchWorkerDeliveryUnknown.toInt() || status == Mobile.SearchWorkerCleanupIncomplete.toInt()) {
                roundUnknown = true
            }
            if (reservedOpen) opening = false
        }
        reply.writeNoException()
        reply.writeInt(status)
        return true
    }

    private fun startEmbedded(
        identity: WorkerIdentityV1,
        network: SearchNetworkV1,
        frame: WorkerOpenFrame,
        startupDeadline: Long,
        cleanupMillis: Long,
    ): Int {
        if (remaining(startupDeadline) == 0L || remaining(frame.attemptDeadline) == 0L) return Mobile.SearchWorkerDeadline.toInt()
        val owner = coordinatorBinder ?: return Mobile.SearchWorkerOwnerLost.toInt()
        val manager = getSystemService(ConnectivityManager::class.java) ?: return Mobile.SearchWorkerCapabilityUnavailable.toInt()
        val policy = WorkerNetworkPolicy(identity, network, owner, manager)
        if (!policy.current() || !permitted(identity, network)) {
            policy.close()
            return Mobile.SearchWorkerOwnerLost.toInt()
        }
        if (remaining(startupDeadline) == 0L || remaining(frame.attemptDeadline) == 0L) {
            policy.close()
            return Mobile.SearchWorkerDeadline.toInt()
        }
        synchronized(roundLock) {
            if (cancelled || closing) {
                policy.close()
                return Mobile.SearchWorkerCancelled.toInt()
            }
            networkPolicy = policy
        }
        // This is the first gomobile call: lease, both Binder registrations and
        // the worker environment have already been checked by admission.
        Mobile.initializeSearch(policy)
        val attemptMillis = remaining(frame.attemptDeadline)
        if (attemptMillis == 0L || remaining(startupDeadline) == 0L) return Mobile.SearchWorkerDeadline.toInt()
        val embedded = Mobile.newSearchEmbeddedWorker(attemptMillis)
        val cancelNow = synchronized(roundLock) {
            worker = embedded
            cancelled || closing
        }
        if (cancelNow) embedded.cancel()
        val startupMillis = remaining(startupDeadline)
        if (startupMillis == 0L) {
            embedded.cancel()
            return if (closeEmbedded(embedded, cleanupMillis)) Mobile.SearchWorkerDeadline.toInt()
            else Mobile.SearchWorkerCleanupIncomplete.toInt()
        }
        val status = checkedOpenStatus(embedded.openStatus(frame.profile, frame.parameters, startupMillis, cleanupMillis))
        if (status != Mobile.SearchWorkerReady.toInt()) return status
        val expired = remaining(startupDeadline) == 0L || remaining(frame.attemptDeadline) == 0L
        val accept = synchronized(roundLock) {
            if (cancelled || closing || expired) false else {
                ready = true
                true
            }
        }
        if (accept) return status
        embedded.cancel()
        return if (closeEmbedded(embedded, cleanupMillis)) {
            if (expired) Mobile.SearchWorkerDeadline.toInt() else Mobile.SearchWorkerCancelled.toInt()
        }
        else Mobile.SearchWorkerCleanupIncomplete.toInt()
    }

    private fun callRound(data: Parcel, reply: Parcel): Boolean {
        var descriptor: ParcelFileDescriptor? = null
        var acquired = false
        var frameConsumed = false
        var operationStarted = false
        var callDeadline = 0L
        var result: ByteArray? = null
        val status = try {
            data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
            val identity = data.readWorkerIdentity()
            val network = data.readWorkerNetwork()
            val operation = data.readInt()
            val length = data.readLong()
            descriptor = data.readTypedObject(ParcelFileDescriptor.CREATOR)
            data.enforceNoDataAvail()
            val input = descriptor ?: throw IOException("worker_frame_missing")
            descriptor = null
            if (!permitted(identity, network)) {
                input.close()
                Mobile.SearchWorkerOwnerLost.toInt()
            } else {
                val embedded = synchronized(roundLock) {
                    if (!ready || inFlight || opening || closing || cancelled) null else {
                        inFlight = true
                        acquired = true
                        worker
                    }
                }
                if (embedded == null) {
                    input.close()
                    Mobile.SearchWorkerUnavailable.toInt()
                } else {
                    val frame = consumeFrame(input, length) { readWorkerCallFrame(it, length, identity.round, SystemClock::elapsedRealtime) }
                    frameConsumed = true
                    callDeadline = frame.deadline
                    try {
                        val budgetMillis = remaining(frame.deadline)
                        if (budgetMillis == 0L) Mobile.SearchWorkerDeadline.toInt() else {
                            if (operation.toLong() !in listOf(Mobile.SearchWorkerCheck, Mobile.SearchWorkerUDP, Mobile.SearchWorkerMedia)) {
                                throw IOException("worker_operation_invalid")
                            }
                            operationStarted = true
                            val bytes = callWithWorkerDeadline(frame.deadline, SystemClock::elapsedRealtime, embedded::probes) { probes, budget ->
                                when (operation.toLong()) {
                                    Mobile.SearchWorkerCheck -> probes.check(frame.request, frame.round.toLong(), budget)
                                    Mobile.SearchWorkerUDP -> probes.udp(frame.request, frame.round.toLong(), budget)
                                    Mobile.SearchWorkerMedia -> probes.media(frame.request, frame.round.toLong(), budget)
                                    else -> throw IOException("worker_operation_invalid")
                                }
                            }
                            if (bytes == null || bytes.isEmpty()) throw IOException("worker_reply_invalid")
                            result = bytes
                            synchronized(roundLock) {
                                if (cancelled || closing) Mobile.SearchWorkerCancelled.toInt()
                                else if (remaining(frame.deadline) == 0L) Mobile.SearchWorkerDeadline.toInt()
                                else Mobile.SearchWorkerReady.toInt()
                            }
                        }
                    } finally {
                        frame.request.fill(0)
                    }
                }
            }
        } catch (_: WorkerFrameDeadline) {
            Mobile.SearchWorkerDeadline.toInt()
        } catch (_: Exception) {
            synchronized(roundLock) {
                if (frameConsumed && operationStarted && cancelled) Mobile.SearchWorkerCancelled.toInt()
                else if (frameConsumed && operationStarted && remaining(callDeadline) == 0L) Mobile.SearchWorkerDeadline.toInt()
                else Mobile.SearchWorkerDeliveryUnknown.toInt()
            }
        } catch (_: LinkageError) {
            Mobile.SearchWorkerDeliveryUnknown.toInt()
        } finally {
            try { descriptor?.close() } catch (_: Exception) { admission.revoke() }
        }
        if (status == Mobile.SearchWorkerDeliveryUnknown.toInt() || status == Mobile.SearchWorkerCleanupIncomplete.toInt()) {
            synchronized(roundLock) { roundUnknown = true }
        }
        try {
            val bytes = result
            if (status == Mobile.SearchWorkerReady.toInt() && bytes != null) {
                val outgoing = replyDescriptor(bytes)
                try {
                    reply.writeNoException()
                    reply.writeInt(status)
                    reply.writeLong(bytes.size.toLong())
                    reply.writeTypedObject(outgoing, 0)
                } finally {
                    outgoing.close()
                }
            } else {
                reply.writeNoException()
                reply.writeInt(status)
            }
        } catch (_: Exception) {
            admission.revoke()
            return false
        } catch (_: LinkageError) {
            admission.revoke()
            return false
        } finally {
            result?.fill(0)
            if (acquired) synchronized(roundLock) { inFlight = false }
        }
        return true
    }

    private fun cancelRound(data: Parcel, reply: Parcel): Boolean {
        data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
        val identity = data.readWorkerIdentity()
        val network = data.readWorkerNetwork()
        data.enforceNoDataAvail()
        if (!permitted(identity, network)) return writeStatus(reply, Mobile.SearchWorkerOwnerLost.toInt())
        val current = synchronized(roundLock) { cancelled = true; worker }
        try {
            current?.cancel()
        } catch (_: Exception) {
            admission.revoke()
            return false
        } catch (_: LinkageError) {
            admission.revoke()
            return false
        }
        return writeStatus(reply, Mobile.SearchWorkerReady.toInt())
    }

    private fun closeRound(data: Parcel, reply: Parcel): Boolean {
        data.enforceInterface(SearchWorkerIpcV2.DESCRIPTOR)
        val identity = data.readWorkerIdentity()
        val network = data.readWorkerNetwork()
        val cleanupDeadline = data.readLong()
        data.enforceNoDataAvail()
        if (!permitted(identity, network)) return writeStatus(reply, Mobile.SearchWorkerOwnerLost.toInt())
        val resources = synchronized(roundLock) {
            closing = true
            ready = false
            if (opening || inFlight || roundUnknown) null else worker to networkPolicy
        }
        val cleanupMillis = remaining(cleanupDeadline)
        val closed = if (resources == null || cleanupMillis !in 1..60_000) false else {
            val workerClosed = resources.first?.let { closeEmbedded(it, cleanupMillis) } ?: true
            val resolverClosed = try { resources.second?.close(); true } catch (_: Exception) { false }
            workerClosed && resolverClosed && remaining(cleanupDeadline) > 0L
        }
        return writeStatus(reply, if (closed) Mobile.SearchWorkerReady.toInt() else Mobile.SearchWorkerCleanupIncomplete.toInt())
    }

    private fun closeEmbedded(embedded: SearchEmbeddedWorker, millis: Long): Boolean = try {
        embedded.close(millis)
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun permitted(identity: WorkerIdentityV1, network: SearchNetworkV1): Boolean =
        Binder.getCallingPid() == coordinatorPid && coordinatorPid > 0 && admission.permits(identity, network)

    private fun writeStatus(reply: Parcel, status: Int): Boolean {
        reply.writeNoException()
        reply.writeInt(status)
        return true
    }

    private fun <T : Any> consumeFrame(
        descriptor: ParcelFileDescriptor,
        length: Long,
        read: (InputStream) -> T,
    ): T {
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        var value: T? = null
        var failure: Throwable? = null
        try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != length) throw IOException("invalid_worker_frame")
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            value = read(input)
        } catch (cause: Throwable) {
            failure = cause
        }
        try {
            input.close()
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
            throw IOException("worker_fd_close_unknown")
        }
        if (failure != null) throw failure
        return value ?: throw IOException("invalid_worker_frame")
    }

    private fun replyDescriptor(bytes: ByteArray): ParcelFileDescriptor {
        val raw = Os.memfd_create("search-reply", OsConstants.MFD_CLOEXEC)
        var output: ParcelFileDescriptor? = null
        var failure: Throwable? = null
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(raw, bytes, offset, bytes.size - offset)
                if (written <= 0) throw IOException("worker_reply_unavailable")
                offset += written
            }
            Os.lseek(raw, 0, OsConstants.SEEK_SET)
            output = ParcelFileDescriptor.dup(raw)
        } catch (cause: Throwable) {
            failure = cause
        }
        try {
            Os.close(raw)
        } catch (cause: Throwable) {
            if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
            failure = IOException("worker_fd_close_unknown")
        }
        if (failure != null) {
            try { output?.close() } catch (_: Exception) { }
            throw failure
        }
        return output ?: throw IOException("worker_reply_unavailable")
    }

    private inner class WorkerNetworkPolicy(
        private val identity: WorkerIdentityV1,
        private val selected: SearchNetworkV1,
        private val owner: IBinder,
        private val manager: ConnectivityManager,
    ) : AndroidNetwork, AutoCloseable {
        private val resolver: SelectedNetworkDns<Network> = selectedNetworkDns(
            manager, selected, RoundOwner { it == selected && current() }, { admission.revoke() },
        )

        override fun current(): Boolean = try {
            if (!admission.permits(identity, selected) || !ownerCurrent()) false else {
                val first = manager.activeNetwork
                val capabilities = first?.let(manager::getNetworkCapabilities)
                first != null && first.networkHandle == selected.identity && capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    manager.activeNetwork == first && ownerCurrent() && admission.permits(identity, selected)
            }
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

        override fun queryDNS(packet: ByteArray, remainingMillis: Long): ByteArray {
            val started = SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) throw SocketTimeoutException("search_dns_deadline")
            val deadline = if (remainingMillis > Long.MAX_VALUE - started) Long.MAX_VALUE else started + remainingMillis
            if (!current()) throw IOException("search_network_unavailable")
            val budget = remaining(deadline)
            if (budget == 0L) throw SocketTimeoutException("search_dns_deadline")
            val result = resolver.queryDns(packet, budget)
            if (!current()) throw IOException("search_network_unavailable")
            if (remaining(deadline) == 0L) throw SocketTimeoutException("search_dns_deadline")
            return result
        }

        override fun allowSocket(fd: Long, role: Long, network: String, address: String): Boolean {
            if (fd !in 0..Int.MAX_VALUE.toLong() || !current()) return false
            val permittedRole = when (role) {
                Mobile.SearchRoleXrayDial -> true
                Mobile.SearchRoleXrayListener, Mobile.SearchRoleSOCKSControl,
                Mobile.SearchRoleSOCKSRelay, Mobile.SearchRoleSOCKSReservation -> loopbackEndpoint(address)
                else -> false
            }
            return permittedRole && current()
        }

        private fun ownerCurrent(): Boolean {
            if (!owner.isBinderAlive) return false
            val data = Parcel.obtain()
            val response = Parcel.obtain()
            return try {
                data.writeInterfaceToken(SearchOwnerIpcV1.DESCRIPTOR)
                data.writeWorkerIdentity(identity, selected)
                if (!owner.transact(SearchOwnerIpcV1.CURRENT, data, response, 0)) return false
                response.readException()
                val current = response.readInt() == 1
                response.enforceNoDataAvail()
                current && owner.isBinderAlive
            } finally {
                data.recycle()
                response.recycle()
            }
        }

        override fun close(): Unit = resolver.close()
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

private fun Parcel.readWorkerIdentity(): WorkerIdentityV1 =
    WorkerIdentityV1(readLong(), readLong().toULong(), readInt(), readLong())

private fun Parcel.readWorkerNetwork(): SearchNetworkV1 = SearchNetworkV1(readLong(), readLong())

private fun Parcel.writeWorkerIdentity(identity: WorkerIdentityV1, network: SearchNetworkV1): Unit {
    writeLong(identity.sessionGeneration)
    writeLong(identity.attempt.toLong())
    writeInt(identity.round)
    writeLong(identity.workerGeneration)
    writeLong(network.identity)
    writeLong(network.version)
}

private fun remaining(deadline: Long): Long {
    val now = SystemClock.elapsedRealtime()
    return if (deadline <= now) 0 else deadline - now
}

private fun checkedOpenStatus(value: Long): Int {
    if (value in Mobile.SearchWorkerReady..Mobile.SearchWorkerDeadline) return value.toInt()
    val retryable = Mobile.SearchWorkerRetryableFlag
    if (value in (retryable + Mobile.SearchWorkerCandidateConfigRejected)..
        (retryable + Mobile.SearchWorkerCandidateExitedBeforeReady)
    ) return value.toInt()
    return Mobile.SearchWorkerDeliveryUnknown.toInt()
}

private fun loopbackEndpoint(address: String): Boolean {
    return try {
        val host = if (address.startsWith("[")) {
            val end = address.indexOf(']')
            if (end <= 1 || end + 1 >= address.length || address[end + 1] != ':') return false
            address.substring(1, end)
        } else {
            val end = address.lastIndexOf(':')
            if (end <= 0) return false
            address.substring(0, end)
        }
        InetAddresses.parseNumericAddress(host).isLoopbackAddress
    } catch (_: IllegalArgumentException) {
        false
    }
}
