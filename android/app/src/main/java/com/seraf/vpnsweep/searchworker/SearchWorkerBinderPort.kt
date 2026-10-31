package com.seraf.vpnsweep.searchworker

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerCloseV1
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import com.seraf.vpnsweep.searchprotocol.WorkerInterruptedV2
import com.seraf.vpnsweep.searchprotocol.WorkerOpenV2
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun createSearchWorkerRound(
    context: Context,
    ownerLease: SearchOwnerLease,
    owner: NetworkEpochOwner,
    identity: WorkerIdentityV1,
    network: SearchNetworkV1,
    provenFrameBound: Int?,
    provenReplyBound: Int?,
): WorkerRoundClient? {
    if (provenFrameBound == null || provenFrameBound < FRAME_HEADER_BYTES || provenReplyBound == null || provenReplyBound < 1) return null
    val coordinator = SearchWorkerCoordinator(context, ownerLease, identity, network, owner)
    val duplicate = coordinator.prepare() ?: return null
    return WorkerRoundClient(
        SearchWorkerBinderPort(coordinator, ownerLease, duplicate, identity, network, provenFrameBound, provenReplyBound),
        duplicate,
    )
}

private const val FRAME_HEADER_BYTES = 16
private const val FRAME_BUFFER_BYTES = 8 * 1024
private const val MAX_CLEANUP_MILLIS = 60_000L

internal class SearchWorkerBinderPort(
    private val coordinator: SearchWorkerCoordinator,
    private val ownerLease: SearchOwnerLease,
    private val duplicate: PreparedWorkerLeaseDuplicate,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val provenFrameBound: Int,
    private val provenReplyBound: Int,
) : DeadlineWorkerPort {
    private enum class State { NEW, OPENING, READY, CLOSING, RETIRED, QUARANTINED }
    private val lock = Any()
    private var state = State.NEW
    private var cancelled = false
    private var cancelPending = false
    private var cancelConfirmed = false
    private var bindingStarted = false
    private var inFlight = false
    private var pendingReply: ReceivedFdInput? = null

    override fun openUntil(
        identity: WorkerIdentityV1,
        network: SearchNetworkV1,
        lease: SearchLeaseDuplicateV1,
        configuration: PrivateInputV1,
        startupDeadlineElapsedMillis: Long,
        cleanupMillis: Long,
    ): WorkerOpenV2 {
        val frame = configuration as? FrameInput ?: return WorkerOpenV2.Fatal(WorkerFatalV2.CAPABILITY_UNAVAILABLE)
        if (identity != this.identity || network != this.network || lease !== duplicate ||
            cleanupMillis !in 1..MAX_CLEANUP_MILLIS || !validFrame(frame)
        ) return WorkerOpenV2.Fatal(WorkerFatalV2.CAPABILITY_UNAVAILABLE)
        val deadline = minOf(startupDeadlineElapsedMillis, frame.deadlineElapsedMillis)
        val reserved = synchronized(lock) {
            if (state != State.NEW || cancelled) false else {
                state = State.OPENING
                true
            }
        }
        if (!reserved) return WorkerOpenV2.Fatal(WorkerFatalV2.BUSY)
        val status = bounded(deadline, {}) {
            val descriptor = stageFrame(frame, deadline)
            try {
                val mayBind = synchronized(lock) {
                    if (state != State.OPENING || cancelled) false else {
                        bindingStarted = true
                        true
                    }
                }
                if (!mayBind || !coordinator.bind()) throw IOException("worker_admission_unknown")
                val binder = coordinator.awaitConfirmed(deadline) ?: throw IOException("worker_admission_unknown")
                requireTime(deadline)
                val code = statusTransaction(binder, SearchWorkerIpcV2.OPEN_FD) { request ->
                    request.writeWorkerIdentity(identity, network)
                    request.writeLong(startupDeadlineElapsedMillis)
                    request.writeLong(cleanupMillis)
                    request.writeLong(frame.length)
                    request.writeTypedObject(descriptor, 0)
                }
                requireTime(deadline)
                if (coordinator.confirmedBinder() !== binder) throw IOException("worker_owner_unknown")
                code
            } finally {
                descriptor.close()
            }
        } ?: return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        if (status.toLong() != Mobile.SearchWorkerReady) {
            failClosed()
            return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        }
        val ready = synchronized(lock) {
            if (state != State.OPENING || cancelled) false else {
                state = State.READY
                true
            }
        }
        if (!ready) {
            failClosed()
            return WorkerOpenV2.Fatal(WorkerFatalV2.UNKNOWN_DELIVERY)
        }
        return WorkerOpenV2.Ready
    }

    override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 {
        val frame = input as? FrameInput ?: return WorkerReplyV1.Quarantined.also { failClosed() }
        if (identity != this.identity || !validFrame(frame)) return WorkerReplyV1.Quarantined.also { failClosed() }
        val reserved = synchronized(lock) {
            if (state != State.READY || inFlight || pendingReply != null || cancelled) false else {
                inFlight = true
                true
            }
        }
        if (!reserved) return WorkerReplyV1.Quarantined.also { failClosed() }
        val reply = bounded(frame.deadlineElapsedMillis, ::discardReply) {
            val descriptor = stageFrame(frame, frame.deadlineElapsedMillis)
            var result: WorkerReplyV1? = null
            try {
                val binder = coordinator.confirmedBinder() ?: throw IOException("worker_owner_unknown")
                requireTime(frame.deadlineElapsedMillis)
                val produced = callTransaction(binder, operation, frame, descriptor)
                result = produced
                if (coordinator.confirmedBinder() !== binder) {
                    discardReply(produced)
                    result = null
                    throw IOException("worker_owner_unknown")
                }
                produced
            } finally {
                try {
                    descriptor.close()
                } catch (failure: Throwable) {
                    result?.let(::discardReply)
                    throw failure
                }
            }
        } ?: return WorkerReplyV1.Quarantined
        val accepted = synchronized(lock) {
            if (state != State.READY || reply == WorkerReplyV1.Quarantined) {
                state = State.QUARANTINED
                ownerLease.quarantine()
                false
            } else {
                inFlight = false
                true
            }
        }
        if (!accepted) {
            discardReplyAsync(reply)
            failClosed()
            return WorkerReplyV1.Quarantined
        }
        return reply
    }

    override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
        if (identity != this.identity) {
            failClosed()
            throw IOException("worker_identity_unknown")
        }
        var conflict = false
        val send = synchronized(lock) {
            cancelled = true
            if (cancelPending) {
                conflict = true
                false
            } else if (cancelConfirmed) {
                false
            } else {
                val needed = when (state) {
                    State.NEW, State.CLOSING, State.RETIRED -> false
                    State.OPENING -> bindingStarted
                    State.READY -> true
                    State.QUARANTINED -> throw IOException("worker_delivery_unknown")
                }
                if (needed) cancelPending = true else cancelConfirmed = true
                needed
            }
        }
        if (conflict) {
            failClosed()
            throw IOException("worker_delivery_unknown")
        }
        if (!send) return
        val confirmed = bounded(cleanupDeadlineElapsedMillis, {}) {
            val binder = coordinator.awaitConfirmed(cleanupDeadlineElapsedMillis) ?: throw IOException("worker_owner_unknown")
            requireTime(cleanupDeadlineElapsedMillis)
            val status = statusTransaction(binder, SearchWorkerIpcV2.CANCEL_ROUND) { it.writeWorkerIdentity(identity, network) }
            requireTime(cleanupDeadlineElapsedMillis)
            status.toLong() == Mobile.SearchWorkerReady
        }
        if (confirmed != true) {
            failClosed()
            throw IOException("worker_delivery_unknown")
        }
        val accepted = synchronized(lock) {
            if (state == State.QUARANTINED) false else {
                cancelPending = false
                cancelConfirmed = true
                true
            }
        }
        if (!accepted) throw IOException("worker_delivery_unknown")
    }

    override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 {
        if (identity != this.identity) return WorkerCloseV1.QUARANTINED.also { failClosed() }
        val disposition = synchronized(lock) {
            when {
                state == State.RETIRED -> 1
                state != State.READY || inFlight || pendingReply != null || cancelPending -> 0
                else -> {
                    state = State.CLOSING
                    2
                }
            }
        }
        if (disposition == 1) return WorkerCloseV1.CLEAN_AND_DEAD
        if (disposition != 2) return WorkerCloseV1.QUARANTINED.also { failClosed() }
        val retired = bounded(cleanupDeadlineElapsedMillis, {}) {
            val binder = coordinator.confirmedBinder() ?: throw IOException("worker_owner_unknown")
            requireTime(cleanupDeadlineElapsedMillis)
            val status = statusTransaction(binder, SearchWorkerIpcV2.CLOSE_ROUND) {
                it.writeWorkerIdentity(identity, network)
                it.writeLong(cleanupDeadlineElapsedMillis)
            }
            if (status.toLong() != Mobile.SearchWorkerReady) throw IOException("worker_cleanup_unknown")
            requireTime(cleanupDeadlineElapsedMillis)
            coordinator.stop()
            if (!coordinator.awaitRetired(cleanupDeadlineElapsedMillis)) throw IOException("worker_death_unknown")
            requireTime(cleanupDeadlineElapsedMillis)
            true
        }
        val completed = synchronized(lock) {
            if (retired != true || state != State.CLOSING) false else {
                state = State.RETIRED
                true
            }
        }
        if (!completed) return WorkerCloseV1.QUARANTINED.also { failClosed() }
        return WorkerCloseV1.CLEAN_AND_DEAD
    }

    private fun validFrame(frame: FrameInput): Boolean =
        provenFrameBound >= FRAME_HEADER_BYTES && provenReplyBound > 0 && frame.length in FRAME_HEADER_BYTES.toLong()..provenFrameBound.toLong() &&
            frame.deadlineElapsedMillis > 0

    private fun stageFrame(frame: FrameInput, deadline: Long): ParcelFileDescriptor {
        requireTime(deadline)
        val raw = Os.memfd_create("search-frame", OsConstants.MFD_CLOEXEC)
        var duplicate: ParcelFileDescriptor? = null
        var failure: Throwable? = null
        try {
            val buffer = ByteArray(FRAME_BUFFER_BYTES)
            try {
                frame.open().use { input ->
                    var count = 0L
                    while (count < frame.length) {
                        requireTime(deadline)
                        val wanted = minOf(buffer.size.toLong(), frame.length - count).toInt()
                        val read = input.read(buffer, 0, wanted)
                        if (read <= 0 || read > wanted) throw IOException("worker_frame_invalid")
                        var offset = 0
                        while (offset < read) {
                            requireTime(deadline)
                            val written = Os.write(raw, buffer, offset, read - offset)
                            if (written <= 0 || written > read - offset) throw IOException("worker_frame_invalid")
                            offset += written
                        }
                        count += read
                    }
                    if (input.read() != -1) throw IOException("worker_frame_invalid")
                }
            } finally { buffer.fill(0) }
            val stat = Os.fstat(raw)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != frame.length) throw IOException("worker_frame_invalid")
            Os.lseek(raw, 0, OsConstants.SEEK_SET)
            requireTime(deadline)
            duplicate = ParcelFileDescriptor.dup(raw)
        } catch (t: Throwable) {
            failure = t
        }
        try {
            Os.close(raw)
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            failure = IOException("worker_fd_close_unknown")
        }
        if (failure != null) {
            try { duplicate?.close() } catch (_: Exception) { }
            throw failure
        }
        return duplicate ?: throw IOException("worker_fd_unknown")
    }

    private fun callTransaction(
        binder: IBinder,
        operation: WorkerOperationV1,
        frame: FrameInput,
        descriptor: ParcelFileDescriptor,
    ): WorkerReplyV1 {
        var received: ParcelFileDescriptor? = null
        try {
            val status = transaction(binder, SearchWorkerIpcV2.CALL_FD, { request ->
                request.writeWorkerIdentity(identity, network)
                request.writeInt(operationNumber(operation))
                request.writeLong(frame.length)
                request.writeTypedObject(descriptor, 0)
            }) { response ->
                val code = response.readInt()
                if (code.toLong() == Mobile.SearchWorkerReady) {
                    val length = response.readLong()
                    received = response.readTypedObject(ParcelFileDescriptor.CREATOR)
                    if (length !in 1..provenReplyBound.toLong() || received == null) throw IOException("worker_reply_invalid")
                    val stat = Os.fstat(received!!.fileDescriptor)
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != length) throw IOException("worker_reply_invalid")
                    Os.lseek(received!!.fileDescriptor, 0, OsConstants.SEEK_SET)
                }
                response.enforceNoDataAvail()
                code
            }
            val result = when (status.toLong()) {
                Mobile.SearchWorkerReady -> {
                    val input = ReceivedFdInput(received ?: throw IOException("worker_reply_invalid"), ::replyDisposed)
                    received = null
                    val accepted = synchronized(lock) {
                        if (state != State.READY || !inFlight || pendingReply != null) false else {
                            pendingReply = input
                            true
                        }
                    }
                    if (!accepted) {
                        input.close()
                        throw IOException("worker_reply_unknown")
                    }
                    WorkerReplyV1.Complete(input)
                }
                else -> nonReadyWorkerReply(status.toLong())
            }
            received = null
            return result
        } finally {
            received?.close()
        }
    }

    private fun statusTransaction(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int =
        transaction(binder, code, write) { response -> response.readInt().also { response.enforceNoDataAvail() } }

    private fun <T> transaction(binder: IBinder, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(SearchWorkerIpcV2.DESCRIPTOR)
            write(request)
            if (!binder.transact(code, request, response, 0)) throw IOException("worker_delivery_unknown")
            response.readException()
            return read(response)
        } finally {
            try { request.recycle() } finally { response.recycle() }
        }
    }

    private fun <T : Any> bounded(deadline: Long, discard: (T) -> Unit, action: () -> T): T? {
        if (remaining(deadline) <= 0) {
            failClosed()
            return null
        }
        val done = CountDownLatch(1)
        val resultLock = Any()
        var abandoned = false
        var completed = false
        var result: T? = null
        try {
            Thread({
                val value = try { action() } catch (_: Exception) { null } catch (_: LinkageError) { null }
                val discardNow = synchronized(resultLock) {
                    if (abandoned) value else {
                        result = value
                        completed = true
                        null
                    }
                }
                if (discardNow != null) try { discard(discardNow) } catch (_: Exception) { }
                done.countDown()
            }, "search-worker-ipc").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            failClosed()
            return null
        } catch (_: LinkageError) {
            failClosed()
            return null
        }
        val waited = try {
            val remaining = remaining(deadline)
            remaining > 0 && done.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val timely = waited && remaining(deadline) > 0
        var discarded: T? = null
        val accepted = synchronized(resultLock) {
            if (timely && completed && result != null) result.also { result = null } else {
                abandoned = true
                discarded = result
                result = null
                null
            }
        }
        if (accepted == null) {
            failClosed()
            discarded?.let { value ->
                try { Thread({ try { discard(value) } catch (_: Exception) { } }, "search-worker-discard").apply { isDaemon = true }.start() }
                catch (_: Exception) { }
            }
        }
        return accepted
    }

    private fun discardReply(reply: WorkerReplyV1): Unit {
        if (reply is WorkerReplyV1.Complete) reply.result.close()
    }

    private fun replyDisposed(input: ReceivedFdInput, confirmed: Boolean): Unit {
        val unknown = synchronized(lock) {
            if (!confirmed || pendingReply !== input) {
                state = State.QUARANTINED
                ownerLease.quarantine()
                true
            } else {
                pendingReply = null
                false
            }
        }
        if (unknown) coordinator.quarantine()
    }

    private fun discardReplyAsync(reply: WorkerReplyV1): Unit {
        try { Thread({ try { discardReply(reply) } catch (_: Exception) { } }, "search-worker-discard").apply { isDaemon = true }.start() }
        catch (_: Exception) { }
    }

    private fun failClosed(): Unit {
        synchronized(lock) {
            state = State.QUARANTINED
            ownerLease.quarantine()
        }
        coordinator.quarantine()
    }
}

private fun remaining(deadline: Long): Long {
    val now = SystemClock.elapsedRealtime()
    return if (now < 0 || deadline <= now) 0 else deadline - now
}

private fun requireTime(deadline: Long): Unit {
    if (remaining(deadline) <= 0) throw IOException("worker_deadline")
}

internal fun operationNumber(operation: WorkerOperationV1): Int = when (operation) {
    WorkerOperationV1.CHECK -> Mobile.SearchWorkerCheck.toInt()
    WorkerOperationV1.UDP -> Mobile.SearchWorkerUDP.toInt()
    WorkerOperationV1.MEDIA -> Mobile.SearchWorkerMedia.toInt()
}

internal fun nonReadyWorkerReply(status: Long): WorkerReplyV1 = when (status) {
    Mobile.SearchWorkerCancelled -> WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED)
    Mobile.SearchWorkerDeadline -> WorkerReplyV1.Interrupted(WorkerInterruptedV2.DEADLINE)
    else -> WorkerReplyV1.Quarantined
}

private fun Parcel.writeWorkerIdentity(identity: WorkerIdentityV1, network: SearchNetworkV1): Unit {
    writeLong(identity.sessionGeneration)
    writeLong(identity.attempt.toLong())
    writeInt(identity.round)
    writeLong(identity.workerGeneration)
    writeLong(network.identity)
    writeLong(network.version)
}

private class ReceivedFdInput(
    private val descriptor: ParcelFileDescriptor,
    private val disposed: (ReceivedFdInput, Boolean) -> Unit,
) : PrivateInputV1 {
    private val lock = Any()
    private var stream: InputStream? = null
    private var closed = false
    private val closeOnce = LeaseCloseOnce {
        val owned = synchronized(lock) {
            closed = true
            stream
        }
        var confirmed = false
        try {
            if (owned == null) descriptor.close() else owned.close()
            confirmed = true
        } finally {
            disposed(this, confirmed)
        }
    }

    override fun open(): InputStream = synchronized(lock) {
        if (closed || stream != null) throw IOException("worker_reply_unavailable")
        val opened = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        stream = opened
        object : FilterInputStream(opened) {
            override fun close(): Unit = closeOnce.close()
        }
    }

    override fun close(): Unit = closeOnce.close()
}
