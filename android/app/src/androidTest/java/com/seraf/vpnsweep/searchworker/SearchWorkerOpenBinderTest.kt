package com.seraf.vpnsweep.searchworker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchWorkerOpenBinderTest {
    @Test
    fun openFrameIsDecodedBeforeOwnershipRejection(): Unit {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val remote = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?): Unit {
                remote.set(service)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?): Unit = Unit
        }
        val frame = File.createTempFile("worker-frame-", ".bin", context.cacheDir)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var bound = false
        try {
            frame.writeBytes(ByteArray(16))
            bound = context.bindService(Intent(context, SearchWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            assertFalse(remote.get() is android.os.Binder)
            ParcelFileDescriptor.open(frame, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                request.writeInterfaceToken(SearchWorkerIpcV2.DESCRIPTOR)
                request.writeWorkerOpen(
                    WorkerIdentityV1(1, 2uL, 1, 3), SearchNetworkV1(4, 5),
                    1024, 1024, SystemClock.elapsedRealtime() + 10_000, 1_000, 16, descriptor,
                )
                assertTrue(remote.get().transact(SearchWorkerIpcV2.OPEN_FD, request, reply, 0))
                reply.readException()
                // The message is parsed but admission was intentionally not granted.
                assertEquals(Mobile.SearchWorkerOwnerLost.toInt(), reply.readInt())
                reply.enforceNoDataAvail()
            }
        } finally {
            request.recycle()
            reply.recycle()
            if (bound) context.unbindService(connection)
            frame.delete()
        }
    }
}
