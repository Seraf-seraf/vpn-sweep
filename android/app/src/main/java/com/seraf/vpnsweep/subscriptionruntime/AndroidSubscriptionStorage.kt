package com.seraf.vpnsweep.subscriptionruntime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionBatchStatusV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import java.io.File
import java.io.IOException

internal fun subscriptionStorage(context: Context, currentRevision: () -> ResultRevision?): SubscriptionStorage {
    val app = requireNotNull(context.applicationContext)
    return SubscriptionStorage(File(app.noBackupFilesDir, "subscription-snapshots"), AndroidSnapshotFiles(app.cacheDir), currentRevision)
}

private class AndroidSnapshotFiles(private val cache: File) : SubscriptionSnapshotFiles {
    override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit {
        val concrete = snapshot as? AndroidSubscriptionSnapshot ?: throw IOException("subscription_snapshot")
        val written = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { concrete.writeSnapshot(it.fd.toLong()) }
        if (written != file.length()) throw IOException("subscription_snapshot")
    }

    override fun read(file: File): SubscriptionSnapshotV1 {
        val native = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Mobile.readSubscriptionSnapshot(it.fd.toLong(), file.length())
        }
        try {
            val snapshot = AndroidSubscriptionSnapshot(cache, native)
            if (snapshot.status != SubscriptionBatchStatusV1.HAS_SUPPORTED) {
                snapshot.close()
                throw IOException("subscription_snapshot")
            }
            return snapshot
        } catch (failure: Exception) {
            native.close()
            throw failure
        }
    }

    override fun syncDirectory(directory: File): Unit {
        try {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("subscription_storage")
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: ErrnoException) {
            throw IOException("subscription_storage")
        }
    }
}
