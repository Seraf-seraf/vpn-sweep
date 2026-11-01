package com.seraf.vpnsweep.searchworker

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.searchprotocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PreconnectBinderPortJointTest {
    @Test fun innerTimeoutBeforeOuterStillStartsRecovery(): Unit = exercise(innerFirst = true)
    @Test fun outerTimeoutThenAdapterCancelStillDrainsOpen(): Unit = exercise(innerFirst = false)

    private fun exercise(innerFirst: Boolean): Unit {
        val clock = AtomicLong(1_000)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val outcome = AtomicReference<Boolean?>()
        val recoveryStarts = AtomicInteger()
        val coordinator = FakeCoordinator(waiting, release)
        val owner = FakeOwner()
        val identity = WorkerIdentityV1(1, 2uL, 1, 3)
        val network = SearchNetworkV1(4, 5)
        val drain = SearchWorkerLocalDrain(1)
        val duplicate = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
        val port = SearchWorkerBinderPort(
            coordinator, owner, duplicate, identity, network, drain,
            onPreconnectTimeout = {
                recoveryStarts.incrementAndGet()
                drain.seal(1) { outcome.set(it != null); drainFinished.countDown() }
            },
            provenFrameBound = 1024, provenReplyBound = 1024,
            stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                override fun descriptor(): ParcelFileDescriptor = error("no Binder transaction expected")
                override fun close(): Unit = Unit
            } },
            nowMillis = clock::get,
            awaitBounded = { done, _ ->
                check(waiting.await(3, TimeUnit.SECONDS))
                clock.set(2_000)
                if (innerFirst) {
                    release.countDown()
                    check(done.await(3, TimeUnit.SECONDS))
                    true
                } else false
            },
        )
        val forwarding = object : DeadlineWorkerPort {
            override fun openUntil(id: WorkerIdentityV1, net: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
                configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 =
                port.openUntil(id, net, lease, configuration, startupDeadlineElapsedMillis, cleanupMillis)
            override fun cancelUntil(id: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
                port.cancelUntil(id, cleanupDeadlineElapsedMillis)
                cancelled.countDown()
            }
            override fun closeUntil(id: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 =
                port.closeUntil(id, cleanupDeadlineElapsedMillis)
            override fun execute(id: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 =
                port.execute(id, operation, input)
        }
        val adapter = SearchEngineWorkerAdapter(1, network, 5_000, 1024, { WorkerRoundClient(forwarding, duplicate) }, {},
            nowNanos = { clock.get() * 1_000_000 }, localDrain = drain)
        val worker = Thread {
            try { adapter.open(1, 2, 1, 3, byteArrayOf(1), byteArrayOf(2), 1_000, 1_000) }
            finally { finished.countDown() }
        }
        worker.start()
        try {
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            if (!innerFirst) assertNull(outcome.get())
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(drainFinished.await(3, TimeUnit.SECONDS))
            assertEquals(1, recoveryStarts.get())
            assertEquals(true, outcome.get())
            assertEquals(0, coordinator.quarantines.get())
            assertEquals(0, owner.quarantines.get())
        } finally { release.countDown() }
    }

    private class FakeCoordinator(private val waiting: CountDownLatch, private val release: CountDownLatch) : WorkerCoordinatorPort {
        val quarantines = AtomicInteger()
        override fun bind(): Boolean = true
        override fun awaitConfirmed(deadlineElapsedMillis: Long): IBinder? {
            waiting.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            return null
        }
        override fun confirmedBinder(): IBinder? = null
        override fun awaitRetired(deadlineElapsedMillis: Long): Boolean = false
        override fun quarantine(): Unit { quarantines.incrementAndGet() }
        override fun stop(): Unit = Unit
        override fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean = false
        override fun replaceSupervisorAttempt(attempt: Any): Boolean = false
        override fun supervisorDied(proof: SupervisorDeathProof): Boolean = false
        override fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean = false
        override fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean = false
    }

    private class FakeOwner : SearchOwnerLeaseControl {
        val quarantines = AtomicInteger()
        override fun reserveWorker(): Boolean = true
        override fun releaseUnstartedReservation(): Unit = Unit
        override fun mayLoadMobile(): Boolean = true
        override fun retireWorkerUntil(deadlineElapsedMillis: Long): Boolean = false
        override fun resumeWorkers(): Boolean = false
        override fun releaseUntil(deadlineElapsedMillis: Long): Boolean = false
        override fun duplicate(): SearchLeaseDuplicateV1? = null
        override fun anchorHeldForWorker(): Boolean = true
        override fun workerDeathConfirmed(): Unit = Unit
        override fun quarantine(): Unit { quarantines.incrementAndGet() }
    }
}
