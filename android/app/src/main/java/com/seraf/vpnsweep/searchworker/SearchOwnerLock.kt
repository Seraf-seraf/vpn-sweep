package com.seraf.vpnsweep.searchworker

import android.content.Context
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1

internal sealed interface OwnerAcquisition {
    class Acquired(val lease: SearchOwnerLease) : OwnerAcquisition
    data object Busy : OwnerAcquisition
    data object Unavailable : OwnerAcquisition
}

internal object SearchOwnerLock {
    private const val BUSY = -2
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
        val fd = try {
            nativeAcquire(context.filesDir.absolutePath)
        } catch (_: Exception) {
            return OwnerAcquisition.Unavailable
        } catch (_: LinkageError) {
            return OwnerAcquisition.Unavailable
        }
        return when {
            fd >= 0 -> OwnerAcquisition.Acquired(SearchOwnerLease(fd))
            fd == BUSY -> OwnerAcquisition.Busy
            else -> OwnerAcquisition.Unavailable
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

internal class SearchOwnerLease internal constructor(private var fd: Int) : AutoCloseable {
    private var workerOutstanding = false

    @Synchronized
    fun duplicate(): WorkerLeaseDuplicate? {
        if (fd < 0 || workerOutstanding) return null
        val duplicate = SearchOwnerLock.duplicate(fd) ?: return null
        workerOutstanding = true
        return duplicate
    }

    @Synchronized
    fun mayLoadMobile(): Boolean = fd >= 0 && SearchOwnerLock.ownerMayLoad(fd)

    @Synchronized
    fun anchorHeldForWorker(): Boolean = workerOutstanding && fd >= 0 && SearchOwnerLock.ownerMayLoad(fd)

    @Synchronized
    fun workerDeathConfirmed(): Unit { workerOutstanding = false }

    @Synchronized
    override fun close(): Unit {
        if (fd < 0) return
        check(!workerOutstanding) { "worker_death_unconfirmed" }
        val original = fd
        fd = -1
        check(SearchOwnerLock.close(original)) { "owner_lock_close_failed" }
    }
}

internal class WorkerLeaseDuplicate internal constructor(
    val descriptor: ParcelFileDescriptor,
) : SearchLeaseDuplicateV1 {
    fun fileAndEnvironmentValid(context: Context): Boolean = try {
        SearchOwnerLock.workerFileAndEnvironmentValid(context, descriptor.fd)
    } catch (_: Exception) {
        false
    }

    override fun close(): Unit = descriptor.close()
}
