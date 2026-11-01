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

internal interface SearchOwnerLeaseControl : CoordinatorLease {
    fun reserveWorker(): Boolean
    fun releaseUnstartedReservation(): Unit
    fun mayLoadMobile(): Boolean
    fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean
    fun resumeWorkers(): Boolean
    fun releaseUntil(deadlineElapsedMillis: Long): Boolean
}

internal interface SearchOwnerLeaseProducerFinalizer {
    fun acceptRelease(): Boolean
}

internal class SearchOwnerLease internal constructor(
    private var fd: Int,
    private val admission: SearchOwnerAdmission = SearchOwnerAdmission(),
    private val token: Any = checkNotNull(admission.reserve()),
    private val closeFd: (Int) -> Boolean = { SearchOwnerLock.close(it) },
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable, SearchOwnerLeaseControl {
    private enum class State { OPEN, SEALED, RECOVERING, CLOSING, CLOSED_PENDING, RELEASED, QUARANTINED }
    private val workerOutstanding = AtomicBoolean()
    private val state = AtomicReference(State.OPEN)
    private val custodyLock = Any()
    private var custody: Any? = null
    private var activeOperations = 0
    private val callerClosing = Any()
    @Volatile private var workerRetired = CountDownLatch(0)
    private var duplicateIssued = false

    internal fun claimCustody(): SearchOwnerLeaseAccess? = synchronized(custodyLock) {
        if (state.get() != State.OPEN || custody != null || activeOperations != 0) return@synchronized null
        Any().also { custody = it }.let { SearchOwnerLeaseAccess(this, it) }
    }

    internal fun returnCustody(owner: SearchOwnerLeaseAccess): Boolean = synchronized(custodyLock) {
        if (!owner.owns(this) || custody !== owner.token || activeOperations != 0 || state.get() != State.OPEN) return@synchronized false
        custody = null
        true
    }

    internal fun heldInCustodyBy(owner: SearchOwnerLeaseAccess): Boolean = synchronized(custodyLock) {
        owner.owns(this) && custody === owner.token && state.get() == State.OPEN
    }

    override fun reserveWorker(): Boolean = withAccess(null, { false }) { reserveWorkerImpl() }

    internal fun reserveWorker(owner: SearchOwnerLeaseAccess): Boolean = withAccess(owner, { false }) { reserveWorkerImpl() }

    @Synchronized
    private fun reserveWorkerImpl(): Boolean {
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

    override fun duplicate(): WorkerLeaseDuplicate? = withAccess(null, { null }) { duplicateImpl() }

    internal fun duplicate(owner: SearchOwnerLeaseAccess): WorkerLeaseDuplicate? = withAccess(owner, { null }) { duplicateImpl() }

    @Synchronized
    private fun duplicateImpl(): WorkerLeaseDuplicate? {
        if (fd < 0 || !workerOutstanding.get() || duplicateIssued || state.get() != State.OPEN) return null
        val duplicate = SearchOwnerLock.duplicate(fd) ?: return null
        duplicateIssued = true
        return duplicate
    }

    @Synchronized
    override fun mayLoadMobile(): Boolean = state.get() == State.OPEN && fd >= 0 && SearchOwnerLock.ownerMayLoad(fd) && state.get() == State.OPEN

    override fun anchorHeldForWorker(): Boolean = observeAnchor() == WorkerAnchorObservation.HELD

    @Synchronized
    override fun observeAnchor(): WorkerAnchorObservation {
        val before = state.get()
        if (before == State.RECOVERING) return WorkerAnchorObservation.FENCED
        if ((before != State.OPEN && before != State.SEALED) || !workerOutstanding.get() || fd < 0) return WorkerAnchorObservation.UNKNOWN
        if (!SearchOwnerLock.ownerMayLoad(fd)) return WorkerAnchorObservation.UNKNOWN
        return when (state.get()) {
            State.RECOVERING -> WorkerAnchorObservation.FENCED
            State.OPEN, State.SEALED -> WorkerAnchorObservation.HELD
            else -> WorkerAnchorObservation.UNKNOWN
        }
    }

    private fun ownerHeld(): Boolean = state.get() == State.OPEN || state.get() == State.SEALED

    private fun reservationHeld(): Boolean = ownerHeld() || state.get() == State.RECOVERING

    override fun sealForRecovery(): Boolean = sealForRecovery(null)

    internal fun sealForRecovery(owner: SearchOwnerLeaseAccess?): Boolean = synchronized(custodyLock) {
        val authorized = if (owner == null) custody == null else owner.owns(this) && custody === owner.token
        authorized && activeOperations == 0 && workerOutstanding.get() && state.compareAndSet(State.OPEN, State.RECOVERING)
    }

    override fun workerDeathConfirmed(): Unit = withAccess(null, { throw IllegalStateException("owner_custody_transferred") }) {
        workerDeathConfirmedImpl()
    }

    internal fun workerDeathConfirmed(owner: SearchOwnerLeaseAccess): Unit = withAccess(owner, { throw IllegalStateException("owner_custody_unavailable") }) {
        workerDeathConfirmedImpl()
    }

    private fun workerDeathConfirmedImpl(): Unit = synchronized(this) {
        val completed = workerRetired
        check(reservationHeld() && workerOutstanding.compareAndSet(true, false)) { "worker_death_not_releasable" }
        completed.countDown()
    }

    override fun releaseUnstartedReservation(): Unit = withAccess(null, { throw IllegalStateException("owner_custody_transferred") }) {
        releaseUnstartedReservationImpl()
    }

    internal fun releaseUnstartedReservation(owner: SearchOwnerLeaseAccess): Unit = withAccess(owner, { throw IllegalStateException("owner_custody_unavailable") }) {
        releaseUnstartedReservationImpl()
    }

    @Synchronized
    private fun releaseUnstartedReservationImpl(): Unit {
        check(fd >= 0 && reservationHeld() && workerOutstanding.compareAndSet(true, false)) { "worker_reservation_not_releasable" }
        duplicateIssued = false
        workerRetired.countDown()
    }

    override fun quarantine(): Unit = withAccess(null, { throw IllegalStateException("owner_custody_transferred") }) { quarantineImpl() }

    internal fun quarantine(owner: SearchOwnerLeaseAccess): Unit = withAccess(owner, { throw IllegalStateException("owner_custody_unavailable") }) { quarantineImpl() }

    private fun quarantineImpl(): Unit {
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

    override fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean = withAccess(null, { false }) {
        retireWorkerUntilImpl(deadlineElapsedMillis)
    }

    internal fun retireWorkerUntil(owner: SearchOwnerLeaseAccess, deadlineElapsedMillis: Long): Boolean = withAccess(owner, { false }) {
        retireWorkerUntilImpl(deadlineElapsedMillis)
    }

    private fun retireWorkerUntilImpl(deadlineElapsedMillis: Long): Boolean {
        if (state.get() == State.RECOVERING) return false
        val initial = nowMillis()
        if (initial < 0 || deadlineElapsedMillis <= initial) {
            quarantineImpl()
            return false
        }
        synchronized(this) {
            if (state.get() == State.OPEN) state.compareAndSet(State.OPEN, State.SEALED)
        }
        while (workerOutstanding.get()) {
            if (state.get() != State.SEALED) return false
            val now = nowMillis()
            if (now < 0 || deadlineElapsedMillis <= now) {
                quarantineImpl()
                return false
            }
            val signalled = try { workerRetired.await(deadlineElapsedMillis - now, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!signalled) {
                quarantineImpl()
                return false
            }
        }
        val completed = nowMillis()
        if (completed < 0 || completed >= deadlineElapsedMillis || state.get() != State.SEALED) {
            quarantineImpl()
            return false
        }
        return true
    }

    @Synchronized
    override fun resumeWorkers(): Boolean = withAccess(null, { false }) { resumeWorkersImpl() }

    internal fun resumeWorkers(owner: SearchOwnerLeaseAccess): Boolean = withAccess(owner, { false }) { resumeWorkersImpl() }

    @Synchronized
    private fun resumeWorkersImpl(): Boolean = fd >= 0 && !workerOutstanding.get() &&
        (state.get() == State.OPEN || state.compareAndSet(State.SEALED, State.OPEN))

    override fun releaseUntil(deadlineElapsedMillis: Long): Boolean = withAccess(null, { false }) {
        releaseUntilImpl(deadlineElapsedMillis)
    }

    internal fun releaseUntil(owner: SearchOwnerLeaseAccess, deadlineElapsedMillis: Long): Boolean = withAccess(owner, { false }) {
        releaseUntilImpl(deadlineElapsedMillis)
    }

    private fun releaseUntilImpl(deadlineElapsedMillis: Long): Boolean {
        if (state.get() == State.RELEASED) return true
        if (!retireWorkerUntilImpl(deadlineElapsedMillis)) return false
        val beforeClose = nowMillis()
        if (beforeClose < 0 || deadlineElapsedMillis <= beforeClose) {
            quarantineImpl()
            return false
        }
        val original = synchronized(this) {
            if (workerOutstanding.get() || fd < 0 || !state.compareAndSet(State.SEALED, State.CLOSING)) null
            else fd.also { fd = -1 }
        } ?: run {
            quarantineImpl()
            return false
        }
        val closed = try { closeFd(original) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        val completed = nowMillis()
        if (closed && completed >= 0 && completed < deadlineElapsedMillis &&
            state.compareAndSet(State.CLOSING, State.CLOSED_PENDING)
        ) return true
        quarantineImpl()
        return false
    }

    // Only in-memory CAS operations: safe for the producer's final decision.
    fun acceptRelease(): Boolean = withAccess(null, { false }) { acceptReleaseImpl() }

    internal fun acceptRelease(owner: SearchOwnerLeaseAccess): Boolean = withAccess(owner, { false }) { acceptReleaseImpl() }

    private fun acceptReleaseImpl(): Boolean {
        if (!state.compareAndSet(State.CLOSED_PENDING, State.RELEASED)) return false
        if (admission.release(token)) return true
        state.set(State.QUARANTINED)
        admission.quarantine(token)
        return false
    }

    override fun close(): Unit {
        if (state.get() == State.RELEASED) return
        val acquired = synchronized(custodyLock) {
            if (custody != null || activeOperations != 0) false else {
                custody = callerClosing
                true
            }
        }
        check(acquired) { "owner_custody_transferred" }
        try {
            check(state.get() != State.QUARANTINED) { "owner_quarantined" }
            check(state.get() != State.CLOSED_PENDING) { "owner_acceptance_pending" }
            check(!workerOutstanding.get()) { "worker_death_unconfirmed" }
            check(releaseUntilImpl(Long.MAX_VALUE)) { "owner_lock_close_failed" }
            check(acceptReleaseImpl()) { "owner_lock_acceptance_failed" }
        } finally {
            synchronized(custodyLock) {
                if (state.get() == State.OPEN || state.get() == State.RELEASED) {
                    if (custody === callerClosing) custody = null
                }
            }
        }
    }

    private inline fun <T> withAccess(owner: SearchOwnerLeaseAccess?, denied: () -> T, action: () -> T): T {
        val acquired = synchronized(custodyLock) {
            val authorized = if (owner == null) custody == null else owner.owns(this) && custody === owner.token
            if (authorized) activeOperations++
            authorized
        }
        if (!acquired) return denied()
        try {
            return action()
        } finally {
            synchronized(custodyLock) { activeOperations-- }
        }
    }
}

internal class SearchOwnerLeaseAccess internal constructor(
    private val lease: SearchOwnerLease,
    internal val token: Any,
) : SearchOwnerLeaseProducerFinalizer {
    internal fun owns(candidate: SearchOwnerLease): Boolean = lease === candidate
    internal fun ownsCustody(): Boolean = lease.heldInCustodyBy(this)
    internal fun returnCustody(): Boolean = lease.returnCustody(this)
    internal fun sessionControl(): SearchOwnerLeaseControl = SearchOwnerLeaseSessionControl(this)

    internal fun duplicate(): WorkerLeaseDuplicate? = lease.duplicate(this)
    internal fun anchorHeldForWorker(): Boolean = lease.anchorHeldForWorker()
    internal fun observeAnchor(): WorkerAnchorObservation = lease.observeAnchor()
    internal fun workerDeathConfirmed(): Unit = lease.workerDeathConfirmed(this)
    internal fun sealForRecovery(): Boolean = lease.sealForRecovery(this)
    internal fun quarantine(): Unit = lease.quarantine(this)
    internal fun reserveWorker(): Boolean = lease.reserveWorker(this)
    internal fun releaseUnstartedReservation(): Unit = lease.releaseUnstartedReservation(this)
    internal fun mayLoadMobile(): Boolean = lease.mayLoadMobile()
    internal fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean = lease.retireWorkerUntil(this, deadlineElapsedMillis)
    internal fun resumeWorkers(): Boolean = lease.resumeWorkers(this)
    internal fun releaseUntil(deadlineElapsedMillis: Long): Boolean = lease.releaseUntil(this, deadlineElapsedMillis)
    override fun acceptRelease(): Boolean = lease.acceptRelease(this)
}

private class SearchOwnerLeaseSessionControl(
    private val access: SearchOwnerLeaseAccess,
) : SearchOwnerLeaseControl {
    override fun duplicate(): SearchLeaseDuplicateV1? = access.duplicate()
    override fun anchorHeldForWorker(): Boolean = access.anchorHeldForWorker()
    override fun observeAnchor(): WorkerAnchorObservation = access.observeAnchor()
    override fun sealForRecovery(): Boolean = access.sealForRecovery()
    override fun workerDeathConfirmed(): Unit = access.workerDeathConfirmed()
    override fun quarantine(): Unit = access.quarantine()
    override fun reserveWorker(): Boolean = access.reserveWorker()
    override fun releaseUnstartedReservation(): Unit = access.releaseUnstartedReservation()
    override fun mayLoadMobile(): Boolean = access.mayLoadMobile()
    override fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean = access.retireWorkerUntil(deadlineElapsedMillis)
    override fun resumeWorkers(): Boolean = access.resumeWorkers()
    override fun releaseUntil(deadlineElapsedMillis: Long): Boolean = access.releaseUntil(deadlineElapsedMillis)
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
