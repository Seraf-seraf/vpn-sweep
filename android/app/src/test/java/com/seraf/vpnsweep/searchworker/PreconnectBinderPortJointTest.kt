package com.seraf.vpnsweep.searchworker

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.searchprotocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PreconnectBinderPortJointTest {
    @Test fun innerTimeoutBeforeOuterStillStartsRecovery(): Unit = exercise(Order.INNER_FIRST)
    @Test fun outerTimeoutThenAdapterCancelStillDrainsOpen(): Unit = exercise(Order.OUTER_FIRST)
    @Test fun adapterCancelBeforeTimeoutDrainsBothWaits(): Unit = exercise(Order.CANCEL_FIRST)
    @Test fun cancelDeadlineBeforeFenceStaysUnknownAfterFence(): Unit = exercise(Order.CANCEL_DEADLINE_FIRST)
    @Test fun unexpectedLateWaitFailureRemainsUnknown(): Unit = exercise(Order.WAIT_FAIL)
    @Test fun failedStagedResourceCloseRemainsUnknown(): Unit = exercise(Order.CLOSE_FAIL)

    private enum class Order { INNER_FIRST, OUTER_FIRST, CANCEL_FIRST, CANCEL_DEADLINE_FIRST, WAIT_FAIL, CLOSE_FAIL }

    private fun exercise(order: Order): Unit {
        val clock = AtomicLong(1_000)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelWaiting = CountDownLatch(1)
        val allowTimeout = CountDownLatch(1)
        val recoveryStarted = CountDownLatch(1)
        val cancelDeadlineObserved = CountDownLatch(1)
        val deliverCancelDeadline = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val drainFinished = CountDownLatch(1)
        val openWait = AtomicReference<CountDownLatch>()
        val outcome = AtomicReference<Boolean?>()
        val recoveryStarts = AtomicInteger()
        val coordinator = FakeCoordinator(waiting, cancelWaiting, release, order == Order.WAIT_FAIL,
            order == Order.CANCEL_DEADLINE_FIRST, cancelDeadlineObserved, deliverCancelDeadline)
        val owner = FakeOwner()
        val identity = WorkerIdentityV1(1, 2uL, 1, 3)
        val network = SearchNetworkV1(4, 5)
        val drain = SearchWorkerLocalDrain(1)
        val duplicate = PreparedWorkerLeaseDuplicate(object : SearchLeaseDuplicateV1 { override fun close(): Unit = Unit }, {}, owner::quarantine)
        val port = SearchWorkerBinderPort(
            coordinator, owner, duplicate, identity, network, drain,
            onPreconnectTimeout = {
                recoveryStarts.incrementAndGet()
                coordinator.supervised = true
                drain.seal(1) { outcome.set(it != null); drainFinished.countDown() }.also { recoveryStarted.countDown() }
            },
            provenFrameBound = 1024, provenReplyBound = 1024,
            stageFrameOverride = { _, _ -> object : StagedWorkerFrame {
                override fun descriptor(): ParcelFileDescriptor = error("no Binder transaction expected")
                override fun close(): Unit { if (order == Order.CLOSE_FAIL) throw IOException("synthetic_close_failure") }
            } },
            nowMillis = clock::get,
            awaitBounded = { done, millis ->
                if (done === openWait.get() || openWait.compareAndSet(null, done)) {
                    check(waiting.await(3, TimeUnit.SECONDS))
                    if (order == Order.CANCEL_FIRST || order == Order.CANCEL_DEADLINE_FIRST) check(allowTimeout.await(3, TimeUnit.SECONDS))
                    clock.set(2_000)
                    if (order == Order.INNER_FIRST) {
                        release.countDown()
                        check(done.await(3, TimeUnit.SECONDS))
                        true
                    } else false
                } else done.await(millis, TimeUnit.MILLISECONDS)
            },
        )
        val forwarding = object : DeadlineWorkerPort {
            override fun openUntil(id: WorkerIdentityV1, net: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
                configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 =
                port.openUntil(id, net, lease, configuration, startupDeadlineElapsedMillis, cleanupMillis)
            override fun cancelUntil(id: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
                try { port.cancelUntil(id, cleanupDeadlineElapsedMillis) } finally { cancelled.countDown() }
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
            if (order == Order.CANCEL_FIRST || order == Order.CANCEL_DEADLINE_FIRST) {
                assertTrue(waiting.await(3, TimeUnit.SECONDS))
                adapter.cancel(3)
                assertTrue(cancelWaiting.await(3, TimeUnit.SECONDS))
                if (order == Order.CANCEL_DEADLINE_FIRST) assertTrue(cancelDeadlineObserved.await(3, TimeUnit.SECONDS))
                allowTimeout.countDown()
                assertTrue(recoveryStarted.await(3, TimeUnit.SECONDS))
            } else assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            if (order != Order.INNER_FIRST) assertNull(outcome.get())
            deliverCancelDeadline.countDown()
            release.countDown()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(drainFinished.await(3, TimeUnit.SECONDS))
            assertEquals(1, recoveryStarts.get())
            assertEquals(order != Order.WAIT_FAIL && order != Order.CLOSE_FAIL && order != Order.CANCEL_DEADLINE_FIRST, outcome.get())
            if (order == Order.CANCEL_DEADLINE_FIRST) {
                assertTrue(coordinator.quarantines.get() > 0)
                assertTrue(owner.quarantines.get() > 0)
            } else {
                assertEquals(0, coordinator.quarantines.get())
                assertEquals(0, owner.quarantines.get())
            }
        } finally { allowTimeout.countDown(); deliverCancelDeadline.countDown(); release.countDown() }
    }

    private class FakeCoordinator(private val waiting: CountDownLatch, private val cancelWaiting: CountDownLatch,
        private val release: CountDownLatch, private val failWait: Boolean, private val earlyCancelDeadline: Boolean,
        private val cancelDeadlineObserved: CountDownLatch, private val deliverCancelDeadline: CountDownLatch) : WorkerCoordinatorPort {
        val quarantines = AtomicInteger()
        private val waits = AtomicInteger()
        @Volatile var supervised = false
        override fun bind(): Boolean = true
        override fun awaitConfirmation(deadlineElapsedMillis: Long): WorkerConfirmationWait {
            val sequence = waits.incrementAndGet()
            if (sequence == 1) waiting.countDown() else cancelWaiting.countDown()
            if (sequence == 2 && earlyCancelDeadline) {
                val observed = WorkerConfirmationWait.Deadline
                cancelDeadlineObserved.countDown()
                check(deliverCancelDeadline.await(3, TimeUnit.SECONDS))
                return observed
            }
            check(release.await(3, TimeUnit.SECONDS))
            if (failWait) throw IOException("synthetic_wait_failure")
            return if (supervised) WorkerConfirmationWait.SupervisedStop else WorkerConfirmationWait.Deadline
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
