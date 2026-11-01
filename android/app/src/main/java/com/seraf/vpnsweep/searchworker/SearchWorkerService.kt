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
import java.util.concurrent.atomic.AtomicBoolean

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

private class SearchWorkerServiceDeadline : IOException("worker_deadline")
private class WorkerFrameInvalid : IOException("invalid_worker_frame")

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

internal object SearchWorkerServiceBounds {
    const val FRAME_HEADER_BYTES = 16
    const val MAX_JSON_LEAF_BYTES = 8 * 1024 * 1024
    const val MAX_OPEN_FRAME_BYTES = FRAME_HEADER_BYTES + MAX_JSON_LEAF_BYTES * 2
    const val MAX_FRAME_BYTES = MAX_OPEN_FRAME_BYTES
    const val MAX_CALL_FRAME_BYTES = FRAME_HEADER_BYTES + MAX_JSON_LEAF_BYTES
    const val RESULT_ENVELOPE_OVERHEAD_BYTES = 21
    const val MAX_RAW_REPLY_BYTES = MAX_JSON_LEAF_BYTES
    const val MAX_REPLY_BYTES = MAX_RAW_REPLY_BYTES + RESULT_ENVELOPE_OVERHEAD_BYTES
    const val MIN_REPLY_BYTES = RESULT_ENVELOPE_OVERHEAD_BYTES + 1

    fun supports(frameBound: Int, replyBound: Int): Boolean =
        frameBound in FRAME_HEADER_BYTES..MAX_FRAME_BYTES &&
            replyBound in MIN_REPLY_BYTES..MAX_REPLY_BYTES

    fun frameFits(length: Long, frameBound: Int): Boolean =
        length in FRAME_HEADER_BYTES.toLong()..minOf(frameBound, MAX_FRAME_BYTES).toLong()

    fun openFrameFits(length: Long, frameBound: Int): Boolean =
        frameFits(length, frameBound)

    fun callFrameFits(length: Long, frameBound: Int): Boolean =
        length in FRAME_HEADER_BYTES.toLong()..minOf(frameBound, MAX_CALL_FRAME_BYTES).toLong()

    fun jsonLeafFits(length: Int): Boolean = length in 1..MAX_JSON_LEAF_BYTES

    fun rawReplyFits(length: Long, replyBound: Int): Boolean {
        if (length !in 1..MAX_RAW_REPLY_BYTES.toLong() || replyBound !in MIN_REPLY_BYTES..MAX_REPLY_BYTES) {
            return false
        }
        val envelopedLength = try {
            Math.addExact(length, RESULT_ENVELOPE_OVERHEAD_BYTES.toLong())
        } catch (_: ArithmeticException) {
            return false
        }
        return envelopedLength <= replyBound.toLong()
    }
}

internal fun <P, R> callWithWorkerDeadline(
    deadline: Long,
    now: () -> Long,
    acquire: () -> P,
    invoke: (P, Long) -> R,
): R {
    if (deadline <= now()) throw SearchWorkerServiceDeadline()
    val target = acquire()
    val current = now()
    if (deadline <= current) throw SearchWorkerServiceDeadline()
    return invoke(target, deadline - current)
}

internal fun validWorkerFrameDescriptor(
    isRegularFile: Boolean,
    actualLength: Long,
    declaredLength: Long,
    provenFrameBound: Int,
): Boolean = isRegularFile && actualLength == declaredLength &&
    SearchWorkerServiceBounds.frameFits(declaredLength, provenFrameBound)

// The descriptor itself is authenticated and closed by the caller. These
// readers consume exactly the declared frame and never interpret its contents.
internal fun readWorkerOpenFrame(
    input: InputStream,
    length: Long,
    provenFrameBound: Int,
    now: () -> Long,
): WorkerOpenFrame {
    if (!SearchWorkerServiceBounds.openFrameFits(length, provenFrameBound) || length > Int.MAX_VALUE.toLong()) {
        throw WorkerFrameInvalid()
    }
    val source = DataInputStream(input)
    val deadline = source.readLong()
    val profileSize = source.readInt()
    val parameterSize = source.readInt()
    if (deadline <= now()) throw SearchWorkerServiceDeadline()
    if (!SearchWorkerServiceBounds.jsonLeafFits(profileSize) || !SearchWorkerServiceBounds.jsonLeafFits(parameterSize) ||
        SearchWorkerServiceBounds.FRAME_HEADER_BYTES + profileSize.toLong() + parameterSize.toLong() != length
    ) throw WorkerFrameInvalid()
    val profile = ByteArray(profileSize)
    val parameters = ByteArray(parameterSize)
    source.readFully(profile)
    source.readFully(parameters)
    if (source.read() != -1) throw WorkerFrameInvalid()
    if (deadline <= now()) throw SearchWorkerServiceDeadline()
    return WorkerOpenFrame(deadline, profile, parameters)
}

internal fun readWorkerCallFrame(
    input: InputStream,
    length: Long,
    provenFrameBound: Int,
    expectedWorkerRound: Int,
    now: () -> Long,
): WorkerCallFrame {
    if (!SearchWorkerServiceBounds.callFrameFits(length, provenFrameBound) || length > Int.MAX_VALUE.toLong()) {
        throw WorkerFrameInvalid()
    }
    val source = DataInputStream(input)
    val deadline = source.readLong()
    val round = source.readInt()
    val requestSize = source.readInt()
    if (deadline <= now()) throw SearchWorkerServiceDeadline()
    if (expectedWorkerRound < 1 || round != expectedWorkerRound - 1 || !SearchWorkerServiceBounds.jsonLeafFits(requestSize) ||
        SearchWorkerServiceBounds.FRAME_HEADER_BYTES + requestSize.toLong() != length
    ) throw WorkerFrameInvalid()
    val request = ByteArray(requestSize)
    source.readFully(request)
    if (source.read() != -1) throw WorkerFrameInvalid()
    if (deadline <= now()) throw SearchWorkerServiceDeadline()
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
    private var provenFrameBound = 0
    private var provenReplyBound = 0
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
                SearchWorkerIpcV2.OPEN_FD -> openRound(data, reply)
                SearchWorkerIpcV2.CALL_FD -> callRound(data, reply)
                SearchWorkerIpcV2.CANCEL_ROUND -> cancelRound(data, reply)
                SearchWorkerIpcV2.CLOSE_ROUND -> closeRound(data, reply)
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
            val frameBound = data.readInt()
            val replyBound = data.readInt()
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
            } else if (!SearchWorkerBounds.supports(frameBound, replyBound) ||
                !SearchWorkerServiceBounds.supports(frameBound, replyBound)
            ) {
                input.close()
                SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
            } else if (cleanupMillis !in 1..60_000) {
                input.close()
                Mobile.SearchWorkerUnavailable.toInt()
            } else if (!SearchWorkerServiceBounds.openFrameFits(length, frameBound)) {
                input.close()
                SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
            } else if (remaining(startupDeadline) == 0L) {
                input.close()
                SearchWorkerStatus.DEADLINE.toInt()
            } else {
                val reserved = synchronized(roundLock) {
                    if (openReserved || closing || cancelled) false else {
                        openReserved = true
                        opening = true
                        provenFrameBound = frameBound
                        provenReplyBound = replyBound
                        reservedOpen = true
                        true
                    }
                }
                if (!reserved) {
                    input.close()
                    synchronized(roundLock) {
                        if (cancelled) SearchWorkerStatus.CANCELLED.toInt() else Mobile.SearchWorkerBusy.toInt()
                    }
                } else {
                    val frame = consumeFrame(input, length, frameBound) {
                        readWorkerOpenFrame(it, length, frameBound, SystemClock::elapsedRealtime)
                    }
                    try {
                        startEmbedded(identity, network, frame, startupDeadline, cleanupMillis)
                    } finally {
                        frame.profile.fill(0)
                        frame.parameters.fill(0)
                    }
                }
            }
        } catch (_: SearchWorkerServiceDeadline) {
            SearchWorkerStatus.DEADLINE.toInt()
        } catch (_: WorkerFrameInvalid) {
            SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
        } catch (_: java.io.EOFException) {
            SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
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
        if (remaining(startupDeadline) == 0L || remaining(frame.attemptDeadline) == 0L) return SearchWorkerStatus.DEADLINE.toInt()
        val owner = coordinatorBinder ?: return Mobile.SearchWorkerOwnerLost.toInt()
        val manager = getSystemService(ConnectivityManager::class.java) ?: return SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
        val selectedNetwork = manager.activeNetwork ?: return Mobile.SearchWorkerOwnerLost.toInt()
        val policy = WorkerNetworkPolicy(identity, network, owner, manager, selectedNetwork)
        if (!policy.current() || !permitted(identity, network)) {
            policy.close()
            return Mobile.SearchWorkerOwnerLost.toInt()
        }
        if (remaining(startupDeadline) == 0L || remaining(frame.attemptDeadline) == 0L) {
            policy.close()
            return SearchWorkerStatus.DEADLINE.toInt()
        }
        synchronized(roundLock) {
            if (cancelled || closing) {
                policy.close()
                return SearchWorkerStatus.CANCELLED.toInt()
            }
            networkPolicy = policy
        }
        // This is the first gomobile call: lease, both Binder registrations and
        // the worker environment have already been checked by admission.
        try {
            Mobile.initializeSearch(policy)
        } catch (_: Exception) {
            policy.close()
            return Mobile.SearchWorkerDeliveryUnknown.toInt()
        } catch (_: LinkageError) {
            policy.close()
            return Mobile.SearchWorkerDeliveryUnknown.toInt()
        }
        val attemptMillis = remaining(frame.attemptDeadline)
        if (attemptMillis == 0L || remaining(startupDeadline) == 0L) return SearchWorkerStatus.DEADLINE.toInt()
        val embedded = Mobile.newSearchEmbeddedWorker(attemptMillis)
        val cancelNow = synchronized(roundLock) {
            worker = embedded
            cancelled || closing
        }
        if (cancelNow) embedded.cancel()
        val startupMillis = remaining(startupDeadline)
        if (startupMillis == 0L) {
            embedded.cancel()
            return if (closeEmbedded(embedded, cleanupMillis)) SearchWorkerStatus.DEADLINE.toInt()
            else Mobile.SearchWorkerCleanupIncomplete.toInt()
        }
        val status = try {
            val value = embedded.openStatus(frame.profile, frame.parameters, startupMillis, cleanupMillis)
            if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else Mobile.SearchWorkerDeliveryUnknown.toInt()
        } catch (_: Exception) {
            Mobile.SearchWorkerDeliveryUnknown.toInt()
        } catch (_: LinkageError) {
            Mobile.SearchWorkerDeliveryUnknown.toInt()
        }
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
            if (expired) SearchWorkerStatus.DEADLINE.toInt() else SearchWorkerStatus.CANCELLED.toInt()
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
                    val limits = synchronized(roundLock) { provenFrameBound to provenReplyBound }
                    if (!SearchWorkerBounds.supports(limits.first, limits.second) ||
                        !SearchWorkerServiceBounds.supports(limits.first, limits.second) ||
                        !SearchWorkerServiceBounds.callFrameFits(length, limits.first)
                    ) {
                        input.close()
                        SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
                    } else {
                        val frame = consumeFrame(input, length, limits.first) {
                            readWorkerCallFrame(it, length, limits.first, identity.round, SystemClock::elapsedRealtime)
                        }
                        frameConsumed = true
                        callDeadline = frame.deadline
                        try {
                            val budgetMillis = remaining(frame.deadline)
                            if (budgetMillis == 0L) SearchWorkerStatus.DEADLINE.toInt() else {
                                if (!validSearchWorkerOperation(operation.toLong())) throw IOException("worker_operation_invalid")
                                operationStarted = true
                                val bytes = callWithWorkerDeadline(frame.deadline, SystemClock::elapsedRealtime, embedded::probes) { probes, budget ->
                                    when (operation.toLong()) {
                                        Mobile.SearchWorkerCheck -> probes.check(frame.request, frame.round.toLong(), budget)
                                        Mobile.SearchWorkerUDP -> probes.udp(frame.request, frame.round.toLong(), budget)
                                        Mobile.SearchWorkerMedia -> probes.media(frame.request, frame.round.toLong(), budget)
                                        else -> throw IOException("worker_operation_invalid")
                                    }
                                }
                                if (bytes == null || !SearchWorkerServiceBounds.rawReplyFits(bytes.size.toLong(), limits.second)) {
                                    bytes?.fill(0)
                                    throw IOException("worker_reply_invalid")
                                }
                                result = bytes
                                synchronized(roundLock) {
                                    if (cancelled || closing) SearchWorkerStatus.CANCELLED.toInt()
                                    else if (remaining(frame.deadline) == 0L) SearchWorkerStatus.DEADLINE.toInt()
                                    else Mobile.SearchWorkerReady.toInt()
                                }
                            }
                        } finally {
                            frame.request.fill(0)
                        }
                    }
                }
            }
        } catch (_: SearchWorkerServiceDeadline) {
            SearchWorkerStatus.DEADLINE.toInt()
        } catch (_: WorkerFrameInvalid) {
            SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
        } catch (_: java.io.EOFException) {
            SearchWorkerStatus.CAPABILITY_UNAVAILABLE.toInt()
        } catch (_: Exception) {
            synchronized(roundLock) {
                if (frameConsumed && operationStarted && cancelled) SearchWorkerStatus.CANCELLED.toInt()
                else if (frameConsumed && operationStarted && remaining(callDeadline) == 0L) SearchWorkerStatus.DEADLINE.toInt()
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
        val resources = synchronized(roundLock) {
            cancelled = true
            worker to networkPolicy
        }
        try {
            resources.second?.close()
            resources.first?.cancel()
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
            val resolverClosed = try { resources.second?.close(); true } catch (_: Exception) { false }
            val workerClosed = resources.first?.let { closeEmbedded(it, cleanupMillis) } ?: true
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
        provenFrameBound: Int,
        read: (InputStream) -> T,
    ): T {
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        var value: T? = null
        var failure: Throwable? = null
        try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!validWorkerFrameDescriptor(
                    isRegularFile = OsConstants.S_ISREG(stat.st_mode),
                    actualLength = stat.st_size,
                    declaredLength = length,
                    provenFrameBound = provenFrameBound,
                )
            ) throw WorkerFrameInvalid()
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
        private val selectedPlatformNetwork: Network,
    ) : AndroidNetwork, AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val resolver: SelectedNetworkDns<Network> = selectedNetworkDns(
            manager, selected, RoundOwner { it == selected && current() }, { admission.revoke() },
        )

        override fun current(): Boolean {
            val current = try {
                !closed.get() && admission.permits(identity, selected) && ownerCurrent() &&
                    manager.activeNetwork == selectedPlatformNetwork &&
                    selectedPlatformNetwork.networkHandle == selected.identity &&
                    manager.getNetworkCapabilities(selectedPlatformNetwork)?.let { capabilities ->
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true && manager.activeNetwork == selectedPlatformNetwork && ownerCurrent() &&
                    admission.permits(identity, selected)
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
            if (!current && !closed.get()) resolver.revoke()
            return current
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
            return authorizeWorkerSocket(
                fd = fd,
                role = role,
                network = network,
                address = address,
                isCurrent = ::current,
                isLoopbackEndpoint = ::loopbackEndpoint,
                bindSelectedNetwork = { fd ->
                    ParcelFileDescriptor.fromFd(fd).use { selectedPlatformNetwork.bindSocket(it.fileDescriptor) }
                },
            )
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

        override fun close(): Unit {
            if (closed.compareAndSet(false, true)) resolver.close()
        }
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

internal fun authorizeWorkerSocket(
    fd: Long,
    role: Long,
    network: String,
    address: String,
    isCurrent: () -> Boolean,
    isLoopbackEndpoint: (String) -> Boolean,
    bindSelectedNetwork: (Int) -> Unit,
): Boolean {
    if (fd !in 0..Int.MAX_VALUE.toLong()) return false
    return try {
        if (!isCurrent()) return false
        if (!supportedWorkerSocketNetwork(network)) return false
        val allowed = when (role) {
            Mobile.SearchRoleXrayDial -> true
            Mobile.SearchRoleXrayListener, Mobile.SearchRoleSOCKSControl,
            Mobile.SearchRoleSOCKSRelay, Mobile.SearchRoleSOCKSReservation -> isLoopbackEndpoint(address)
            else -> false
        }
        if (!allowed) return false
        if (role == Mobile.SearchRoleXrayDial) bindSelectedNetwork(fd.toInt())
        isCurrent()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }
}

private fun supportedWorkerSocketNetwork(network: String): Boolean = when (network) {
    "tcp", "tcp4", "tcp6", "udp", "udp4", "udp6" -> true
    else -> false
}

private fun validSearchWorkerOperation(operation: Long): Boolean = when (operation) {
    Mobile.SearchWorkerCheck, Mobile.SearchWorkerUDP, Mobile.SearchWorkerMedia -> true
    else -> false
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
