package com.seraf.vpnsweep

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.Callable
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ReadinessCheckCoordinatorTest {
    @Test fun rebootRequestKeepsStartupModeBlocked(): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val finished = CountDownLatch(1)
        val mode = StartupRecoveryMode(true)
        try {
            val gate = ReadinessCheckCoordinator(executor, { SearchReadiness.REBOOT_REQUIRED }, { finished.countDown() })
            assertTrue(gate.request(synchronous = { !mode.blocked() }))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(mode.blocked())
        } finally {
            executor.shutdownNow()
        }
    }
    @Test fun failedRecoveryKeepsStartupModeBlocked(): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val finished = CountDownLatch(1)
        val mode = StartupRecoveryMode(true)
        try {
            val gate = ReadinessCheckCoordinator(executor, { SearchReadiness.RECOVERY_UNAVAILABLE }, { finished.countDown() })
            assertTrue(gate.request(synchronous = { !mode.blocked() }))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(mode.blocked())
        } finally {
            executor.shutdownNow()
        }
    }
    @Test fun ordinaryReadinessRemainsSynchronousAndKeepsPendingVpnTicket(): Unit {
        val tickets = VpnStartGateV1()
        val identity = VpnStartIdentityV1(1, 1, "1", "2", "00000000-0000-0000-0000-000000000021", "00000000-0000-0000-0000-000000000022")
        val ticket = checkNotNull(tickets.issue(identity))
        val mode = StartupRecoveryMode(false)
        val caller = Thread.currentThread()
        val readiness = ReadinessCheckCoordinator(java.util.concurrent.Executor { error("unexpected asynchronous dispatch") }, {
            assertSame(caller, Thread.currentThread())
            SearchReadiness.READY
        }, {})
        assertTrue(readiness.request(synchronous = { !mode.blocked() }))
        assertEquals(SearchReadiness.READY, readiness.state.value.result)
        assertNotNull(tickets.consume(ticket.token, identity))
    }
    @Test fun admittedVpnChecksActualIdentityAfterConcurrentReadiness(): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val monitor = Any()
        val blocked = AtomicBoolean(false)
        val identity = VpnStartIdentityV1(1, 1, "1", "2", "00000000-0000-0000-0000-000000000021", "00000000-0000-0000-0000-000000000022")
        val run = checkNotNull(VpnRunRegistry().reserve(stopping = {}))
        val admission = VpnStartAdmissionV1(identity, byteArrayOf(1), run, { true }, {
            currentVpnIdentityMatches(monitor, identity, blocked::get) { identity }
        }, {}, {})
        try {
            val readiness = ReadinessCheckCoordinator(executor, {
                synchronized(monitor) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    SearchReadiness.BUSY
                }
            }, {})
            val caller = FutureTask(Callable { readiness.request(synchronous = { true }) })
            thread { caller.run() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val task = FutureTask(Callable { admission.isCurrent() })
            val waiter = thread { task.run() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiter.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, waiter.state)
            release.countDown()
            assertTrue(caller.get(5, TimeUnit.SECONDS))
            assertTrue(task.get(5, TimeUnit.SECONDS))
            blocked.set(true)
            assertFalse(admission.isCurrent())
        } finally {
            release.countDown()
            admission.close()
            executor.shutdownNow()
        }
    }
    @Test fun finishAndNextRequestPublishDistinctSequencesAtomically(): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val finishEntered = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val finishes = AtomicInteger()
        val checks = AtomicInteger()
        try {
            val gate = ReadinessCheckCoordinator(
                executor,
                check = { if (checks.incrementAndGet() == 1) SearchReadiness.BUSY else SearchReadiness.READY },
                onComplete = {},
                beforeFinishPublish = {
                    if (finishes.incrementAndGet() == 1) {
                        finishEntered.countDown()
                        check(finishRelease.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            assertTrue(gate.request())
            assertTrue(finishEntered.await(5, TimeUnit.SECONDS))
            var accepted = false
            val requester = thread {
                secondStarted.countDown()
                accepted = gate.request()
                secondDone.countDown()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (requester.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, requester.state)
            finishRelease.countDown()
            assertTrue(secondDone.await(5, TimeUnit.SECONDS))
            assertTrue(accepted)
            while (gate.state.value.sequence < 2 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(2L, gate.state.value.sequence)
            assertEquals(SearchReadiness.READY, gate.state.value.result)
        } finally {
            finishRelease.countDown()
            executor.shutdownNow()
        }
    }
    @Test fun singleFlightAndNonblockingReadersSurviveObserverRecreation(): Unit {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val mode = StartupRecoveryMode(true)
            val monitor = Any()
            val gate = ReadinessCheckCoordinator(executor, {
                synchronized(monitor) {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                mode.complete()
                SearchReadiness.READY
            }, { completed.countDown() })
            assertTrue(gate.request(synchronous = { !mode.blocked() }))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(gate.state.value.inProgress)
            assertFalse(gate.request())
            assertTrue(mode.blocked())
            val newObserver = gate.state
            assertTrue(newObserver.value.inProgress)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (newObserver.value.inProgress && System.nanoTime() < limit) Thread.yield()
            assertFalse(newObserver.value.inProgress)
            assertEquals(SearchReadiness.READY, newObserver.value.result)
            assertEquals(1, calls.get())
            assertFalse(mode.blocked())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
