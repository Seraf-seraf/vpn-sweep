package com.seraf.vpnsweep.searchworker

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface OwnerAcquisition {
    class Acquired(val lease: SearchOwnerLease) : OwnerAcquisition
    data object Busy : OwnerAcquisition
    data object Unavailable : OwnerAcquisition
}

internal object SearchOwnerLock {
    private const val BUSY = -2
    private val admission = SearchOwnerAdmission()
    private val available = try {
        System.loadLibrary("searchownerlock")
        true
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun acquire(context: Context): OwnerAcquisition {
        if (!available) return OwnerAcquisition.Unavailable
        val token = admission.reserve() ?: return if (admission.isQuarantined()) OwnerAcquisition.Unavailable else OwnerAcquisition.Busy
        val fd = try {
            nativeAcquire(context.filesDir.absolutePath)
        } catch (_: Exception) {
            admission.quarantine(token)
            return OwnerAcquisition.Unavailable
        } catch (_: LinkageError) {
            admission.quarantine(token)
            return OwnerAcquisition.Unavailable
        }
        return when {
            fd >= 0 -> OwnerAcquisition.Acquired(SearchOwnerLease(fd, admission, token))
            fd == BUSY -> {
                admission.release(token)
                OwnerAcquisition.Busy
            }
            else -> {
                admission.quarantine(token)
                OwnerAcquisition.Unavailable
            }
        }
    }

    internal fun duplicate(fd: Int): WorkerLeaseDuplicate? {
        val copy = try {
            nativeDuplicate(fd)
        } catch (_: LinkageError) {
            return null
        }
        if (copy < 0) return null
        return try {
            WorkerLeaseDuplicate(ParcelFileDescriptor.adoptFd(copy))
        } catch (_: Exception) {
            nativeDiscardDuplicate(copy)
            null
        }
    }

    internal fun close(fd: Int): Boolean = nativeClose(fd) == 0

    internal fun ownerMayLoad(fd: Int): Boolean = try {
        nativeOwnerMayLoad(fd)
    } catch (_: LinkageError) {
        false
    }

    internal fun workerFileAndEnvironmentValid(context: Context, fd: Int): Boolean = try {
        available && nativeWorkerFileAndEnvironmentValid(context.filesDir.absolutePath, fd)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private external fun nativeAcquire(directory: String): Int
    private external fun nativeDuplicate(fd: Int): Int
    private external fun nativeClose(fd: Int): Int
    private external fun nativeDiscardDuplicate(fd: Int): Int
    private external fun nativeOwnerMayLoad(fd: Int): Boolean
    private external fun nativeWorkerFileAndEnvironmentValid(directory: String, fd: Int): Boolean
}

// The process token remains held until the exact lease is closed successfully.
internal class SearchOwnerAdmission {
    private val owner = AtomicReference<Any?>(null)
    private val poisoned = Any()

    fun reserve(): Any? {
        val token = Any()
        return if (owner.compareAndSet(null, token)) token else null
    }

    fun release(token: Any): Boolean = owner.compareAndSet(token, null)
    fun quarantine(token: Any): Unit { owner.compareAndSet(token, poisoned) }
    fun isQuarantined(): Boolean = owner.get() === poisoned
}

internal class SearchOwnerLease internal constructor(
    private var fd: Int,
    private val admission: SearchOwnerAdmission = SearchOwnerAdmission(),
    private val token: Any = checkNotNull(admission.reserve()),
    private val closeFd: (Int) -> Boolean = { SearchOwnerLock.close(it) },
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable, CoordinatorLease {
    private enum class State { OPEN, SEALED, CLOSING, RELEASED, QUARANTINED }
    private val workerOutstanding = AtomicBoolean()
    private val state = AtomicReference(State.OPEN)
    @Volatile private var workerRetired = CountDownLatch(0)
    private var duplicateIssued = false

    @Synchronized
    fun reserveWorker(): Boolean {
        if (fd < 0 || workerOutstanding.get() || state.get() != State.OPEN) return false
        workerRetired = CountDownLatch(1)
        workerOutstanding.set(true)
        duplicateIssued = false
        if (state.get() != State.OPEN) {
            workerRetired.countDown()
            return false
        }
        return true
    }

    @Synchronized
    override fun duplicate(): WorkerLeaseDuplicate? {
        if (fd < 0 || !workerOutstanding.get() || duplicateIssued || state.get() != State.OPEN) return null
        val duplicate = SearchOwnerLock.duplicate(fd) ?: return null
        duplicateIssued = true
        return duplicate
    }

    @Synchronized
    fun mayLoadMobile(): Boolean = state.get() == State.OPEN && fd >= 0 && SearchOwnerLock.ownerMayLoad(fd) && state.get() == State.OPEN

    @Synchronized
    override fun anchorHeldForWorker(): Boolean = ownerHeld() && workerOutstanding.get() && fd >= 0 &&
        SearchOwnerLock.ownerMayLoad(fd) && ownerHeld()

    private fun ownerHeld(): Boolean = state.get() == State.OPEN || state.get() == State.SEALED

    override fun workerDeathConfirmed(): Unit {
        val completed = workerRetired
        check(ownerHeld() && workerOutstanding.compareAndSet(true, false)) { "worker_death_not_releasable" }
        completed.countDown()
    }

    @Synchronized
    fun releaseUnstartedReservation(): Unit {
        check(fd >= 0 && ownerHeld() && workerOutstanding.compareAndSet(true, false)) { "worker_reservation_not_releasable" }
        duplicateIssued = false
        workerRetired.countDown()
    }

    override fun quarantine(): Unit {
        while (true) {
            val previous = state.get()
            if (previous == State.QUARANTINED || previous == State.RELEASED) return
            if (state.compareAndSet(previous, State.QUARANTINED)) {
                admission.quarantine(token)
                workerRetired.countDown()
                return
            }
        }
    }

    fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean {
        val initial = nowMillis()
        if (initial < 0 || deadlineElapsedMillis <= initial) {
            quarantine()
            return false
        }
        synchronized(this) {
            if (state.get() == State.OPEN) state.compareAndSet(State.OPEN, State.SEALED)
        }
        while (workerOutstanding.get()) {
            if (state.get() != State.SEALED) return false
            val now = nowMillis()
            if (now < 0 || deadlineElapsedMillis <= now) {
                quarantine()
                return false
            }
            val signalled = try { workerRetired.await(deadlineElapsedMillis - now, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!signalled) {
                quarantine()
                return false
            }
        }
        val completed = nowMillis()
        if (completed < 0 || completed >= deadlineElapsedMillis || state.get() != State.SEALED) {
            quarantine()
            return false
        }
        return true
    }

    @Synchronized
    fun resumeWorkers(): Boolean = fd >= 0 && !workerOutstanding.get() &&
        (state.get() == State.OPEN || state.compareAndSet(State.SEALED, State.OPEN))

    fun releaseUntil(deadlineElapsedMillis: Long): Boolean {
        if (state.get() == State.RELEASED) return true
        if (!retireWorkerUntil(deadlineElapsedMillis)) return false
        val beforeClose = nowMillis()
        if (beforeClose < 0 || deadlineElapsedMillis <= beforeClose) {
            quarantine()
            return false
        }
        val original = synchronized(this) {
            if (workerOutstanding.get() || fd < 0 || !state.compareAndSet(State.SEALED, State.CLOSING)) null
            else fd.also { fd = -1 }
        } ?: run {
            quarantine()
            return false
        }
        val closed = try { closeFd(original) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        val completed = nowMillis()
        if (closed && completed >= 0 && completed < deadlineElapsedMillis && state.compareAndSet(State.CLOSING, State.RELEASED)) {
            if (admission.release(token)) return true
            state.set(State.QUARANTINED)
            admission.quarantine(token)
            return false
        }
        quarantine()
        return false
    }

    override fun close(): Unit {
        if (state.get() == State.RELEASED) return
        check(state.get() != State.QUARANTINED) { "owner_quarantined" }
        check(!workerOutstanding.get()) { "worker_death_unconfirmed" }
        check(releaseUntil(Long.MAX_VALUE)) { "owner_lock_close_failed" }
    }
}

internal class WorkerLeaseDuplicate internal constructor(
    val descriptor: ParcelFileDescriptor,
) : SearchLeaseDuplicateV1 {
    private val closeOnce = LeaseCloseOnce { descriptor.close() }

    fun fileAndEnvironmentValid(context: Context): Boolean = try {
        SearchOwnerLock.workerFileAndEnvironmentValid(context, descriptor.fd)
    } catch (_: Exception) {
        false
    }

    override fun close(): Unit = closeOnce.close()
}
