package com.seraf.vpnsweep

import com.seraf.vpnsweep.diagnostics.DiagnosticsCoordinator
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import com.seraf.vpnsweep.vpn.VpnServiceLifecycle
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSessionTest {
    companion object {
        private const val TIMEOUT_SECONDS = 5L
    }

    private class ManualScheduler {
        private val tasks = ArrayDeque<() -> Unit>()
        fun schedule(action: () -> Unit): Unit = synchronized(tasks) { tasks.addLast(action) }
        fun size(): Int = synchronized(tasks) { tasks.size }
        fun drain(): Unit {
            while (true) {
                val action = synchronized(tasks) { tasks.pollFirst() } ?: return
                action()
            }
        }
    }

    @Test
    fun nativeStatusPreservesIdentityAndOnSubscription(): Unit = runBlocking {
        val prior = VpnStatus.state.value
        try {
            val first = VpnState(VpnPhase.RUNNING, "same")
            VpnStatus.update(first)
            VpnStatus.update(first.copy())
            assertSame(first, VpnStatus.state.value)
            var subscribed = false
            val received = VpnStatus.state.onSubscription { subscribed = true }.first()
            assertTrue(subscribed)
            assertSame(first, received)
        } finally {
            VpnStatus.update(prior)
        }
    }

    @Test
    fun delayedSnapshotAndOldCallbacksCannotAffectNewEqualSession(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val firstSession = Any()
        coordinator.reserve(firstSession)
        coordinator.running(firstSession)
        val first = requireNotNull(owner.begin())
        assertTrue(owner.report(first, "old"))
        val delayed = owner.currentState
        val firstVpn = VpnState(VpnPhase.RUNNING, "same")
        val secondVpn = firstVpn.copy()
        assertEquals(firstVpn, secondVpn)

        val secondSession = Any()
        coordinator.reserve(secondSession)
        coordinator.running(secondSession)
        val second = requireNotNull(owner.begin())
        coordinator.stopping(firstSession)
        assertFalse(owner.report(first, "late old"))
        owner.finish(first, successful = false)
        assertTrue(owner.current(delayed).testing)
        assertTrue(owner.current(delayed).results.isEmpty())
        work.drain()
        assertTrue(owner.current(delayed).testing)
        assertTrue(owner.report(second, "new"))
        assertEquals(listOf("new"), owner.current(delayed).results)
        owner.close()
        work.drain()
    }

    @Test
    fun recreationInvalidatesOldOwnerWithoutCancellingNewOwner(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val session = Any()
        val firstOwner = coordinator.newOwner()
        firstOwner.attach()
        coordinator.reserve(session)
        coordinator.running(session)
        val first = requireNotNull(firstOwner.begin())
        val firstJob = Job()
        assertTrue(firstOwner.attachJob(first, firstJob))
        assertTrue(firstOwner.report(first, "old activity"))
        val oldSnapshot = firstOwner.currentState
        firstOwner.close()
        val secondOwner = coordinator.newOwner()
        secondOwner.attach()
        val second = requireNotNull(secondOwner.begin())
        val secondJob = Job()
        assertTrue(secondOwner.attachJob(second, secondJob))
        val delayedSameRevision = oldSnapshot.copy(revision = secondOwner.currentState.revision)
        assertTrue(secondOwner.current(delayedSameRevision).results.isEmpty())
        work.drain()
        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isActive)
        assertFalse(firstOwner.report(first, "late"))
        firstOwner.finish(first, successful = false)
        assertTrue(secondOwner.currentState.testing)
        assertTrue(secondOwner.report(second, "new"))
        secondOwner.close()
        work.drain()
    }

    @Test
    fun stopBeforeJobAttachmentPreventsAdmission(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val run = requireNotNull(owner.begin())
        coordinator.stopping(session)
        val job = Job()
        assertFalse(owner.attachJob(run, job))
        assertFalse(owner.admit(run))
        assertFalse(owner.report(run, "late"))
        assertFalse(owner.currentState.testing)
        owner.close()
        work.drain()
        job.cancel()
    }

    @Test
    fun stopBetweenAttachmentAndAdmissionSkipsTheCheck(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val run = requireNotNull(owner.begin())
        val job = Job()
        assertTrue(owner.attachJob(run, job))
        assertTrue(owner.admit(run))
        var checksStarted = false
        val queuedStart = { if (owner.admit(run)) checksStarted = true }
        coordinator.stopping(session)
        queuedStart()
        assertFalse(checksStarted)
        work.drain()
        assertTrue(job.isCancelled)
        owner.close()
        work.drain()
    }

    @Test
    fun failedOrCancelledRunRejectsAlreadyQueuedCallbacks(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val run = requireNotNull(owner.begin())
        assertTrue(owner.report(run, "partial"))
        var accepted: Boolean? = null
        val queuedReport = { accepted = owner.report(run, "late") }
        owner.finish(run, successful = false)
        queuedReport()
        assertEquals(false, accepted)
        assertFalse(owner.currentState.testing)
        assertEquals(listOf("partial"), owner.currentState.results)
        owner.close()
        work.drain()
    }

    @Test
    fun successfulFinishAllowsQueuedReportsUntilRetry(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val first = requireNotNull(owner.begin())
        owner.finish(first, successful = true)
        assertTrue(owner.report(first, "queued"))
        val second = requireNotNull(owner.begin())
        assertFalse(owner.report(first, "old retry"))
        owner.finish(first, successful = false)
        assertTrue(owner.currentState.testing)
        assertTrue(owner.report(second, "new retry"))
        assertEquals(listOf("new retry"), owner.currentState.results)
        owner.close()
        work.drain()
    }

    @Test
    fun stoppingClearsCompletedResultsAndRejectsDelayedCallbacks(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val run = requireNotNull(owner.begin())
        assertTrue(owner.report(run, "completed result"))
        owner.finish(run, successful = true)
        val delayed = owner.currentState
        coordinator.stopping(session)
        assertTrue(owner.current(delayed).results.isEmpty())
        assertFalse(owner.current(delayed).testing)
        assertFalse(owner.report(run, "late result"))
        owner.finish(run, successful = false)
        work.drain()
        assertTrue(owner.current(delayed).results.isEmpty())
        owner.close()
        work.drain()
    }

    @Test
    fun runningRefreshDoesNotResetResultsOrTheActiveRun(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val run = requireNotNull(owner.begin())
        assertTrue(owner.report(run, "first"))
        val before = owner.currentState
        coordinator.running(session)
        assertSame(before, owner.currentState)
        assertTrue(owner.report(run, "second"))
        assertEquals(listOf("first", "second"), owner.currentState.results)
        owner.close()
        work.drain()
    }

    @Test
    fun duplicateReserveKeepsResultsAndNextReserveClearsThem(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val lifecycle = VpnServiceLifecycle()
        val registry = VpnRunRegistry()
        lifecycle.started(1)
        val epoch = requireNotNull(lifecycle.epoch())
        val firstSession = Any()
        val first = requireNotNull(coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(firstSession) }, stopping = { coordinator.stopping(firstSession) })
        })
        coordinator.running(firstSession)
        val old = requireNotNull(owner.begin())
        assertTrue(owner.report(old, "old"))
        val secondSession = Any()
        val duplicate = coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(secondSession) }, stopping = { coordinator.stopping(secondSession) })
        }
        assertNull(duplicate)
        assertEquals(listOf("old"), owner.currentState.results)
        registry.release(first) {}
        val second = coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(secondSession) }, stopping = { coordinator.stopping(secondSession) })
        }
        assertNotNull(second)
        assertTrue(owner.currentState.results.isEmpty())
        assertFalse(coordinator.isCurrent(firstSession))
        assertTrue(coordinator.isCurrent(secondSession))
        owner.close()
        work.drain()
    }

    @Test
    fun reserveDefersCancellationAndSignalsUntilBothLocksAreFree(): Unit {
        val scheduler = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(scheduler::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val lifecycle = VpnServiceLifecycle()
        val registry = VpnRunRegistry()
        lifecycle.started(1)
        val epoch = requireNotNull(lifecycle.epoch())
        val oldSession = Any()
        val old = requireNotNull(coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(oldSession) }, stopping = { coordinator.stopping(oldSession) })
        })
        coordinator.running(oldSession)
        val check = requireNotNull(owner.begin())
        val job = Job()
        assertTrue(owner.attachJob(check, job))
        scheduler.drain()
        registry.release(old) {}
        val locksAcquired = CountDownLatch(1)
        job.invokeOnCompletion {
            Thread {
                registry.guarded { lifecycle.current(); locksAcquired.countDown() }
            }.start()
            assertTrue(locksAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val newSession = Any()
        val worker = Thread {
            try {
                coordinator.withDeferredDelivery {
                    lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                        onReserved = {
                            coordinator.reserve(newSession)
                            entered.countDown()
                            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }, stopping = { coordinator.stopping(newSession) })
                }
            } finally {
                returned.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            coordinator.flush()
            assertEquals(0, scheduler.size())
            assertTrue(job.isActive)
        } finally {
            release.countDown()
            assertTrue(returned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        assertTrue(scheduler.size() > 0)
        scheduler.drain()
        assertTrue(job.isCancelled)
        assertEquals(0L, locksAcquired.count)
        worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        owner.close()
        scheduler.drain()
    }

    @Test
    fun stopDefersCancellationAndSignalsUntilRegistryIsFree(): Unit {
        val scheduler = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(scheduler::schedule)
        val owner = coordinator.newOwner()
        owner.attach()
        val lifecycle = VpnServiceLifecycle()
        val registry = VpnRunRegistry()
        lifecycle.started(1)
        val epoch = requireNotNull(lifecycle.epoch())
        val session = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val run = requireNotNull(coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(session) }, stopping = {
                    coordinator.stopping(session)
                    entered.countDown()
                    check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                })
        })
        coordinator.running(session)
        val check = requireNotNull(owner.begin())
        val job = Job()
        assertTrue(owner.attachJob(check, job))
        scheduler.drain()
        val locksAcquired = CountDownLatch(1)
        job.invokeOnCompletion {
            Thread { registry.guarded { lifecycle.current(); locksAcquired.countDown() } }.start()
            assertTrue(locksAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        val caller = Thread { try { run.stop() } finally { returned.countDown() } }
        caller.start()
        try {
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            coordinator.flush()
            assertEquals(0, scheduler.size())
            assertTrue(job.isActive)
        } finally {
            release.countDown()
            assertTrue(returned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
        assertTrue(scheduler.size() > 0)
        scheduler.drain()
        assertTrue(job.isCancelled)
        assertEquals(0L, locksAcquired.count)
        caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        owner.close()
        scheduler.drain()
    }

    @Test
    fun staleTerminalAfterNewReserveIsRejected(): Unit {
        val work = ManualScheduler()
        val coordinator = DiagnosticsCoordinator(work::schedule)
        val lifecycle = VpnServiceLifecycle()
        val registry = VpnRunRegistry()
        lifecycle.started(1)
        val epoch = requireNotNull(lifecycle.epoch())
        val first = Any()
        val second = Any()
        val old = requireNotNull(coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(first) }, stopping = { coordinator.stopping(first) })
        })
        assertTrue(coordinator.isCurrent(first))
        assertTrue(old.claimRelease())
        assertNotNull(coordinator.withDeferredDelivery {
            lifecycle.reserve(registry, epoch, deferDelivery = coordinator::withDeferredDelivery,
                onReserved = { coordinator.reserve(second) }, stopping = { coordinator.stopping(second) })
        })
        var oldPublished = false
        assertFalse(coordinator.publishIfCurrent(first) { oldPublished = true })
        assertFalse(oldPublished)
        assertTrue(coordinator.isCurrent(second))
        work.drain()
    }

    @Test
    fun stoppingDoesNotWaitForBlockingDiagnosticCancellation(): Unit {
        val coordinator = DiagnosticsCoordinator()
        val owner = coordinator.newOwner()
        owner.attach()
        val session = Any()
        coordinator.reserve(session)
        coordinator.running(session)
        val check = requireNotNull(owner.begin())
        val job = Job()
        assertTrue(owner.attachJob(check, job))
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val handlerFinished = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        job.invokeOnCompletion {
            handlerEntered.countDown()
            releaseHandler.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            handlerFinished.countDown()
        }
        val registry = VpnRunRegistry()
        val run = requireNotNull(registry.reserve(
            deferDelivery = coordinator::withDeferredDelivery,
            stopping = { coordinator.stopping(session) },
        ))
        val caller = Thread {
            run.stop()
            stopReturned.countDown()
        }
        caller.start()
        try {
            assertTrue(handlerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(stopReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(owner.currentState.testing)
        } finally {
            releaseHandler.countDown()
            caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            owner.close()
        }
        assertTrue(handlerFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }
}
