package com.seraf.vpnsweep.searchworker

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.*
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class PostConnectCancelCloseJointTest {
    @Test fun successfulProbeCancelDoesNotStartWholeSessionRecovery(): Unit {
        val cancels = CountDownLatch(1)
        val recoveries = AtomicInteger()
        val owner = FakeOwner()
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
        } as IBinder
        val network = SearchNetworkV1(11, 3)
        val drain = SearchWorkerLocalDrain(42)
        val adapter = SearchEngineWorkerAdapter(42, network, 5_000, 1024, { identity ->
            val duplicate = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
            val port = SearchWorkerBinderPort(FakeCoordinator(binder), owner, duplicate, identity, network, drain,
                { recoveries.incrementAndGet(); false }, 1024, 1024,
                stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                    override fun descriptor(): ParcelFileDescriptor = error("fake transaction has no FD")
                    override fun close(): Unit = Unit
                } }, statusTransactionOverride = { _, code, _ ->
                    if (code == SearchWorkerIpcV2.CANCEL_ROUND) cancels.countDown()
                    Mobile.SearchWorkerReady.toInt()
                }, nowMillis = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
            WorkerRoundClient(port, duplicate)
        }, {}, System::nanoTime, localDrain = drain)
        assertEquals(Mobile.SearchWorkerReady, adapter.open(42, 1, 1, 3, byteArrayOf(1), byteArrayOf(2), 5_000, 5_000))
        adapter.cancel(3)
        assertTrue(cancels.await(3, TimeUnit.SECONDS))
        assertEquals(Mobile.SearchWorkerReady, adapter.close(3, 5_000))
        assertEquals(0, recoveries.get())
        assertEquals(Mobile.SearchWorkerReady, adapter.open(42, 1, 2, 4, byteArrayOf(1), byteArrayOf(2), 5_000, 5_000))
    }

    @Test fun failedCallWaitsForConfirmedCancelBeforeClose(): Unit {
        for (cancelSucceeds in listOf(true, false)) {
            val cancelEntered = CountDownLatch(1)
            val releaseCancel = CountDownLatch(1)
            val waiting = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val closeFailed = CountDownLatch(1)
            val cancelDeadline = AtomicLong()
            val closeDeadline = AtomicLong()
            val owner = FakeOwner()
            val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
                when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
            } as IBinder
            val coordinator = FakeCoordinator(binder)
            val identity = WorkerIdentityV1(42, 1uL, 1, 1)
            val network = SearchNetworkV1(11, 3)
            val drain = SearchWorkerLocalDrain(42)
            val duplicate = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
            val actual = SearchWorkerBinderPort(coordinator, owner, duplicate, identity, network, drain, { false }, 1024, 1024,
                stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                    override fun descriptor(): ParcelFileDescriptor = error("fake transaction has no FD")
                    override fun close(): Unit = Unit
                } },
                statusTransactionOverride = { _, code, _ ->
                    when (code) {
                        SearchWorkerIpcV2.CANCEL_ROUND -> {
                            cancelEntered.countDown()
                            check(releaseCancel.await(3, TimeUnit.SECONDS))
                            if (!cancelSucceeds) throw IOException("synthetic_cancel_failure")
                        }
                        SearchWorkerIpcV2.CLOSE_ROUND -> closeEntered.countDown()
                    }
                    Mobile.SearchWorkerReady.toInt()
                }, nowMillis = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })
            val forwarding = object : DeadlineWorkerPort {
                override fun openUntil(identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
                    configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 =
                    actual.openUntil(identity, network, lease, configuration, startupDeadlineElapsedMillis, cleanupMillis)
                override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
                    cancelDeadline.set(cleanupDeadlineElapsedMillis)
                    actual.cancelUntil(identity, cleanupDeadlineElapsedMillis)
                }
                override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 {
                    closeDeadline.set(cleanupDeadlineElapsedMillis)
                    return actual.closeUntil(identity, cleanupDeadlineElapsedMillis)
                }
                override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 =
                    WorkerReplyV1.Complete(object : PrivateInputV1 {
                        override fun open(): InputStream = object : InputStream() {
                            override fun read(): Int = throw IOException("synthetic_reply_failure")
                        }
                        override fun close(): Unit = Unit
                    })
            }
            val adapter = SearchEngineWorkerAdapter(42, network, 5_000, 1024, { WorkerRoundClient(forwarding, duplicate) }, {},
                System::nanoTime, reportOperationFailure = { if (it == WorkerOperationFailure.CLOSE) closeFailed.countDown() },
                localDrain = drain, awaitCleanupCancel = { latch, nanos -> waiting.countDown(); latch.await(nanos, TimeUnit.NANOSECONDS) })
            try {
                assertEquals(Mobile.SearchWorkerReady, adapter.open(42, 1, 1, 1, byteArrayOf(1), byteArrayOf(2), 10_000, 5_000))
                adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000)
                assertTrue(cancelEntered.await(3, TimeUnit.SECONDS))
                assertTrue(waiting.await(3, TimeUnit.SECONDS))
                assertEquals(1L, closeEntered.count)
                releaseCancel.countDown()
                if (cancelSucceeds) {
                    assertTrue(closeEntered.await(3, TimeUnit.SECONDS))
                    assertEquals(cancelDeadline.get(), closeDeadline.get())
                    assertEquals(0, coordinator.quarantines.get())
                } else {
                    assertTrue(closeFailed.await(3, TimeUnit.SECONDS))
                    assertEquals(1L, closeEntered.count)
                    assertTrue(coordinator.quarantines.get() > 0)
                }
            } finally { releaseCancel.countDown() }
        }
    }

    private class FakeCoordinator(private val binder: IBinder) : WorkerCoordinatorPort {
        val quarantines = AtomicInteger()
        override fun bind(): Boolean = true
        override fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait = WorkerConfirmationWait.Confirmed(binder)
        override fun confirmedBinder(): IBinder? = binder
        override fun awaitRetired(deadlineElapsedMillis: Long): Boolean = true
        override fun quarantine(): Unit { quarantines.incrementAndGet() }
        override fun stop(): Unit = Unit
        override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean = false
        override fun replaceSupervisorAttempt(attempt: Any): Boolean = false
        override fun supervisorDied(proof: SupervisorDeathProof): Boolean = false
        override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = false
        override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = false
    }

    private class FakeOwner : SearchOwnerLeaseControl {
        override fun reserveWorker(): Boolean = true
        override fun releaseUnstartedReservation(): Unit = Unit
        override fun mayLoadMobile(): Boolean = true
        override fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean = false
        override fun resumeWorkers(): Boolean = false
        override fun releaseUntil(deadlineElapsedMillis: Long): Boolean = false
        override fun duplicate(): SearchLeaseDuplicateV1? = null
        override fun anchorHeldForWorker(): Boolean = true
        override fun workerDeathConfirmed(): Unit = Unit
        override fun quarantine(): Unit = Unit
    }
}
