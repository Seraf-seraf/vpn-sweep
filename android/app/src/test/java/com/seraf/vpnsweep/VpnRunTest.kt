package com.seraf.vpnsweep

import com.seraf.vpnsweep.vpn.VpnCancellation
import com.seraf.vpnsweep.vpn.VpnRun
import com.seraf.vpnsweep.vpn.VpnRunFailure
import com.seraf.vpnsweep.vpn.VpnRunOperations
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import com.seraf.vpnsweep.vpn.VpnServiceLifecycle
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRunTest {
    @Test
    fun stopReturnsWhileCancellationHandlerIsBlocked(): Unit = runBlocking {
        val fixture = Fixture(blockCancellation = true)
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        val executor = Executors.newSingleThreadExecutor()
        val returned = CountDownLatch(1)
        try {
            assertTrue(fixture.handlerRegistered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val stop = executor.submit<Boolean> {
                val result = run.stop()
                returned.countDown()
                result
            }
            assertTrue(fixture.handlerEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("STOPPING", fixture.state.get())
            assertTrue("STOP waited for the cancellation handler", returned.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stop.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            fixture.handlerRelease.countDown()
            executor.shutdown()
            withTimeout(TIMEOUT_MS) { job.join() }
            executor.shutdownNow()
        }
        assertEquals("DISCONNECTED", fixture.state.get())
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals(1, fixture.events.count { it == "close" })
    }

    @Test
    fun stopBeforeAttachSchedulesCancellationOnlyOnce(): Unit = runBlocking {
        val scheduled = Collections.synchronizedList(mutableListOf<Job>())
        val fixture = Fixture(registry = VpnRunRegistry { scheduled.add(it) })
        val run = fixture.reserve()!!
        val dispatcher = QueuedDispatcher { repeat(32) { assertTrue(run.stop()) } }
        val job = run.launch(CoroutineScope(coroutineContext + dispatcher), fixture.operations)

        repeat(32) { assertTrue(run.stop()) }
        assertEquals(1, scheduled.size)
        scheduled.single().cancel()
        dispatcher.drain()
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(1, fixture.events.count { it == "stopping" })
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals("DISCONNECTED", fixture.state.get())
    }

    @Test
    fun repeatedStopWhileHandlerIsBlockedSchedulesOnce(): Unit = runBlocking {
        val scheduled = AtomicInteger()
        val fixture = Fixture(
            registry = VpnRunRegistry { job ->
                scheduled.incrementAndGet()
                VpnCancellation.schedule(job)
            },
            blockCancellation = true,
        )
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(fixture.handlerRegistered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            assertTrue(fixture.handlerEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val repeated = executor.submit { repeat(128) { assertTrue(run.stop()) } }
            repeated.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertEquals(1, scheduled.get())
            assertEquals("STOPPING", fixture.state.get())
        } finally {
            fixture.handlerRelease.countDown()
            executor.shutdownNow()
        }
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals(1, fixture.events.count { it == "close" })
        assertEquals("DISCONNECTED", fixture.state.get())
    }

    @Test
    fun serviceScopeCancellationDoesNotBlockCallerOnHandler(): Unit = runBlocking {
        val fixture = Fixture(blockCancellation = true)
        val run = fixture.reserve()!!
        val serviceJob = SupervisorJob()
        val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
        val job = run.launch(serviceScope, fixture.operations)
        val executor = Executors.newSingleThreadExecutor()
        val returned = CountDownLatch(1)
        try {
            assertTrue(fixture.handlerRegistered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val destroy = executor.submit {
                assertTrue(run.stop())
                VpnCancellation.scheduleScope(serviceScope)
                returned.countDown()
            }
            assertTrue(fixture.handlerEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("Service cancellation waited for the handler", returned.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            destroy.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertEquals("STOPPING", fixture.state.get())
        } finally {
            fixture.handlerRelease.countDown()
            executor.shutdownNow()
        }
        withTimeout(TIMEOUT_MS) { job.join(); serviceJob.join() }
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals(1, fixture.events.count { it == "close" })
        assertEquals("DISCONNECTED", fixture.state.get())
    }

    @Test
    fun stopBeforeJobAssignmentStillRunsCleanup(): Unit = runBlocking {
        val fixture = Fixture()
        val run = fixture.reserve()!!
        val dispatcher = QueuedDispatcher { assertTrue(run.stop()) }
        val job = run.launch(CoroutineScope(coroutineContext + dispatcher), fixture.operations)

        assertEquals("STOPPING", fixture.state.get())
        assertNull(fixture.reserve())
        dispatcher.drain()
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(listOf("stopping", "stop", "foreground", "stopSelf:1", "finished"), fixture.events)
        assertEquals("DISCONNECTED", fixture.state.get())
        assertNull(fixture.failure)
    }

    @Test
    fun stopDuringEachPreparationPhasePreventsActivation(): Unit = runBlocking {
        for ((index, phase) in preparation.withIndex()) {
            val fixture = Fixture()
            val barrier = fixture.block(phase)
            val run = fixture.reserve()!!
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)

            stopWhileBlocked(run, barrier)
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals(
                preparation.take(index + 1) + listOf("stopping") + preparation.drop(index + 1) +
                    listOf("stop", "foreground", "stopSelf:1", "finished"),
                fixture.events,
            )
            assertEquals("DISCONNECTED", fixture.state.get())
        }
    }

    @Test
    fun stopDuringUnderlyingNetworkPreventsEstablish(): Unit = runBlocking {
        val fixture = Fixture()
        val barrier = fixture.block("underlying")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)

        stopWhileBlocked(run, barrier)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(preparation + listOf("underlying", "stopping", "stop", "foreground", "stopSelf:1", "finished"), fixture.events)
    }

    @Test
    fun stopDuringEstablishClosesReturnedTunnelWithoutStartingCore(): Unit = runBlocking {
        val fixture = Fixture()
        val barrier = fixture.block("establish")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)

        stopWhileBlocked(run, barrier)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(
            preparation + listOf("underlying", "establish", "stopping", "stop", "close", "foreground", "stopSelf:1", "finished"),
            fixture.events,
        )
    }

    @Test
    fun stopKeepsStoppingThroughAdmittedStartAndAllCleanup(): Unit = runBlocking {
        val fixture = Fixture()
        val start = fixture.block("start")
        val stop = fixture.block("stop")
        val close = fixture.block("close")
        val foreground = fixture.block("foreground")
        val stopSelf = fixture.block("stopSelf")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(start.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            assertEquals("STOPPING", fixture.state.get())
            assertFalse(fixture.events.contains("mobile.start"))
            fixture.started(2)
            assertNull(fixture.reserve())
            fixture.started(3)
            start.proceed.countDown()

            assertTrue(stop.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(fixture.events.contains("mobile.start"))
            assertEquals("STOPPING", fixture.state.get())
            stop.proceed.countDown()
            assertTrue(close.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("STOPPING", fixture.state.get())
            close.proceed.countDown()
            assertTrue(foreground.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("STOPPING", fixture.state.get())
            foreground.proceed.countDown()
            assertTrue(stopSelf.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("STOPPING", fixture.state.get())
            val requested = CountDownLatch(1)
            val newStart = executor.submit<VpnRun?> {
                requested.countDown()
                runBlocking { fixture.started(4) }
                fixture.reserve()
            }
            assertTrue(requested.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertFalse(newStart.isDone)
            assertEquals("STOPPING", fixture.state.get())
            stopSelf.proceed.countDown()
            assertNotNull(newStart.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals("DISCONNECTED", fixture.state.get())
            assertEquals(
                preparation + listOf("underlying", "establish", "start", "stopping", "mobile.start", "stop", "close", "foreground", "stopSelf:3", "finished"),
                fixture.events,
            )
        } finally {
            start.proceed.countDown()
            stop.proceed.countDown()
            close.proceed.countDown()
            foreground.proceed.countDown()
            stopSelf.proceed.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun destroyBeforeIoCleanupSkipsStaleServiceCalls(): Unit = runBlocking {
        val fixture = Fixture()
        val start = fixture.block("start")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        try {
            assertTrue(start.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            fixture.destroy()
            assertEquals("STOPPING", fixture.state.get())
            start.proceed.countDown()
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals("DISCONNECTED", fixture.state.get())
            assertEquals(listOf("mobile.start", "stop", "close", "finished"), fixture.events.takeLast(4))
            val next = Fixture(fixture.registry)
            assertNotNull(next.reserve())
        } finally {
            start.proceed.countDown()
        }
    }

    @Test
    fun oldServiceDestroyDoesNotCancelRunInAnotherInstance(): Unit = runBlocking {
        val fixture = Fixture()
        val start = fixture.block("start")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        stopWhileBlocked(run, start)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals("DISCONNECTED", fixture.state.get())

        val next = Fixture(fixture.registry)
        val nextRun = next.reserve()!!
        val nextJob = nextRun.launch(CoroutineScope(coroutineContext + Dispatchers.IO), next.operations)
        assertTrue(next.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        fixture.destroy()
        assertFalse(run.stop())
        run.publishIfActive { next.state.set("old-running") }
        fixture.registry.release(run) { next.state.set("old-terminal") }
        assertTrue(nextRun.isActive())
        assertFalse(next.events.contains("stopping"))
        assertEquals("RUNNING", next.state.get())
        assertTrue(stopPromptly(nextRun))
        withTimeout(TIMEOUT_MS) { nextJob.join() }
    }

    @Test
    fun destroyOfReusedInstanceCancelsItsNewRun(): Unit = runBlocking {
        val fixture = Fixture()
        val first = fixture.reserve()!!
        val dispatcher = QueuedDispatcher { assertTrue(first.stop()) }
        val firstJob = first.launch(CoroutineScope(coroutineContext + dispatcher), fixture.operations)
        dispatcher.drain()
        withTimeout(TIMEOUT_MS) { firstJob.join() }

        fixture.started(2)
        val next = fixture.reserve()!!
        val nextJob = next.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        fixture.destroy()
        withTimeout(TIMEOUT_MS) { nextJob.join() }
        assertFalse(next.isActive())
        assertEquals(2, fixture.events.count { it == "stopping" })
        assertEquals("DISCONNECTED", fixture.state.get())
    }

    @Test
    fun stopBeforeStatusPublicationPreventsRunningAfterStop(): Unit = runBlocking {
        val fixture = Fixture()
        val barrier = fixture.block("monitor")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)

        stopWhileBlocked(run, barrier)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertFalse(fixture.events.contains("running"))
        assertEquals("DISCONNECTED", fixture.state.get())
    }

    @Test
    fun repeatedStopAndNewStartWaitForCleanup(): Unit = runBlocking {
        val fixture = Fixture()
        val barrier = fixture.block("stop")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(stopPromptly(run))
        try {
            assertTrue(barrier.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            assertEquals("STOPPING", fixture.state.get())
            assertNull(fixture.reserve())
        } finally {
            barrier.proceed.countDown()
        }
        withTimeout(TIMEOUT_MS) { job.join() }
        assertFalse(run.stop())
        assertEquals(1, fixture.events.count { it == "stopping" })
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals(1, fixture.events.count { it == "close" })
        fixture.started(2)
        assertNotNull(fixture.reserve())
    }

    @Test
    fun unsuccessfulStopSelfIsNotRetriedAfterNewStart(): Unit = runBlocking {
        val fixture = Fixture(stopSelfReturns = false)
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        fixture.started(2)
        assertTrue(stopPromptly(run))
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals("DISCONNECTED", fixture.state.get())
        assertEquals(1, fixture.events.count { it == "stopSelf:2" })

        val next = Fixture(fixture.registry)
        val nextRun = next.reserve()!!
        val nextJob = nextRun.launch(CoroutineScope(coroutineContext + Dispatchers.IO), next.operations)
        assertTrue(next.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(nextRun.isActive())
        assertEquals(1, fixture.events.count { it.startsWith("stopSelf:") })
        assertTrue(stopPromptly(nextRun))
        withTimeout(TIMEOUT_MS) { nextJob.join() }
    }

    @Test
    fun visibleTerminalAndRegistryReleaseAreSerialized(): Unit = runBlocking {
        val fixture = Fixture()
        val terminal = fixture.block("terminalPublished")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            assertTrue(terminal.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("DISCONNECTED", fixture.state.get())
            val next = Fixture(fixture.registry)
            val attempted = CountDownLatch(1)
            val future = executor.submit<VpnRun?> {
                attempted.countDown()
                runBlocking { next.started(2) }
                next.reserve()
            }
            assertTrue(attempted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertFalse(future.isDone)
            terminal.proceed.countDown()
            assertNotNull(future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            withTimeout(TIMEOUT_MS) { job.join() }
        } finally {
            terminal.proceed.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun operationErrorsKeepCauseAndCleanUpOwnedResources(): Unit = runBlocking {
        for (phase in listOf("read", "underlying", "establish", "start", "monitor")) {
            val fixture = Fixture(failAt = setOf(phase))
            val run = fixture.reserve()!!
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals(phase, (fixture.failure as VpnRunFailure.Operation).exception.message)
            assertEquals(1, fixture.events.count { it == "stop" })
            assertEquals(if (phase == "start" || phase == "monitor") 1 else 0, fixture.events.count { it == "close" })
            assertEquals("ERROR", fixture.state.get())
        }
    }

    @Test
    fun cleanupErrorsPreservePrecedenceAndStillFinishService(): Unit = runBlocking {
        for ((failAt, expected) in listOf(
            setOf("start", "stop") to VpnRunFailure.Stop,
            setOf("start", "close") to VpnRunFailure.Close,
            setOf("start", "stop", "close") to VpnRunFailure.Close,
            setOf("start", "foreground") to VpnRunFailure.ServiceCleanup,
        )) {
            val fixture = Fixture(failAt = failAt)
            val run = fixture.reserve()!!
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals(expected, fixture.failure)
            assertEquals(listOf("stop", "close", "foreground", "stopSelf:1", "finished"), fixture.events.takeLast(5))
            assertEquals("ERROR", fixture.state.get())
        }
    }

    @Test
    fun promotionFailureStillReleasesRunAndReportsError(): Unit = runBlocking {
        val fixture = Fixture()
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations) {
            error("promotion")
        }
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(listOf("stopping", "stop", "foreground", "stopSelf:1", "finished"), fixture.events)
        assertEquals(VpnRunFailure.Foreground, fixture.failure)
        assertEquals("ERROR", fixture.state.get())
        assertNotNull(fixture.reserve())
    }

    @Test
    fun statusPublicationFailureCannotSkipCleanup(): Unit = runBlocking {
        val fixture = Fixture(failAt = setOf("status"))
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(stopPromptly(run))
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(1, fixture.events.count { it == "stop" })
        assertEquals(1, fixture.events.count { it == "close" })
        assertEquals(1, fixture.events.count { it == "finished" })
        assertEquals(VpnRunFailure.Status, fixture.failure)
        assertEquals("ERROR", fixture.state.get())
    }

    @Test
    fun revokeInvalidatesPreparedEpochButAllowsFreshStart(): Unit = runBlocking {
        val fixture = Fixture()
        val staleEpoch = fixture.lifecycle.epoch()!!
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val revoke = executor.submit {
                fixture.lifecycle.beginRevoke()
                entered.countDown()
                try {
                    check(proceed.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                } finally {
                    fixture.lifecycle.endRevoke()
                }
            }
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertNull(fixture.lifecycle.epoch())
            assertNull(fixture.reserveAt(staleEpoch))
            proceed.countDown()
            revoke.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertNull(fixture.reserveAt(staleEpoch))
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }

        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        try {
            fixture.lifecycle.beginRevoke()?.stop()
        } finally {
            fixture.lifecycle.endRevoke()
        }
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals("DISCONNECTED", fixture.state.get())
        assertNotNull(fixture.reserve())
    }

    @Test
    fun revokeDuringServiceCleanupRetriesLatestStartIdBeforeTerminal(): Unit = runBlocking {
        val fixture = Fixture()
        val stopSelf = fixture.block("stopSelf")
        val run = fixture.reserve()!!
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), fixture.operations)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(fixture.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            fixture.started(2)
            assertTrue(stopPromptly(run))
            assertTrue(stopSelf.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val revoke = executor.submit {
                fixture.lifecycle.beginRevoke()?.stop()
                entered.countDown()
                try {
                    check(proceed.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                } finally {
                    fixture.lifecycle.endRevoke()
                }
            }
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            stopSelf.proceed.countDown()
            fixture.started(3)
            assertEquals("STOPPING", fixture.state.get())
            assertFalse(job.isCompleted)
            assertNull(fixture.reserve())
            proceed.countDown()
            revoke.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals("DISCONNECTED", fixture.state.get())
            assertEquals(listOf("stopSelf:2", "stopSelf:3"), fixture.events.filter { it.startsWith("stopSelf:") })
            assertEquals(1, fixture.events.count { it == "stop" })
            assertEquals(1, fixture.events.count { it == "close" })
            assertEquals(1, fixture.events.count { it == "finished" })
            assertNotNull(fixture.reserve())
        } finally {
            stopSelf.proceed.countDown()
            proceed.countDown()
            executor.shutdownNow()
        }
    }

    private fun stopWhileBlocked(run: VpnRun, barrier: Barrier): Unit {
        try {
            assertTrue(barrier.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
        } finally {
            barrier.proceed.countDown()
        }
    }

    private fun stopPromptly(run: VpnRun): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Boolean> { run.stop() }.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private class Barrier {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
    }

    private class QueuedDispatcher(private val onDispatch: () -> Unit) : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var holding = true
        override fun dispatch(context: CoroutineContext, block: Runnable): Unit {
            if (holding) {
                queue.add(block)
                onDispatch()
            } else {
                block.run()
            }
        }
        fun drain(): Unit {
            holding = false
            while (true) (queue.poll() ?: break).run()
        }
    }

    private class Fixture(
        val registry: VpnRunRegistry = VpnRunRegistry(),
        private val failAt: Set<String> = emptySet(),
        private val stopSelfReturns: Boolean = true,
        private val blockCancellation: Boolean = false,
    ) {
        val lifecycle = VpnServiceLifecycle()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val state = AtomicReference("CONNECTING")
        val running = CountDownLatch(1)
        val handlerRegistered = CountDownLatch(1)
        val handlerEntered = CountDownLatch(1)
        val handlerRelease = CountDownLatch(1)
        private val barriers = ConcurrentHashMap<String, Barrier>()
        @Volatile var failure: VpnRunFailure? = null

        init { lifecycle.started(1) }

        suspend fun started(id: Int): Unit = withContext(fakeMain) { lifecycle.started(id) }
        fun reserve(): VpnRun? {
            val epoch = lifecycle.epoch() ?: return null
            return reserveAt(epoch)
        }
        fun reserveAt(epoch: Long): VpnRun? = lifecycle.reserve(registry, epoch) {
            events.add("stopping")
            if ("status" in failAt) error("status")
            state.set("STOPPING")
        }
        suspend fun destroy(): Unit = withContext(fakeMain) { lifecycle.destroy()?.stop() }
        fun block(name: String): Barrier = Barrier().also { barriers[name] = it }

        private fun pause(name: String): Unit {
            barriers[name]?.let {
                it.entered.countDown()
                check(it.proceed.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "barrier timed out: $name" }
            }
        }
        private fun stage(name: String, event: String = name): Unit {
            events.add(event)
            pause(name)
            if (name in failAt) error(name)
        }

        val operations = object : VpnRunOperations<Unit, String> {
            override fun prepare(run: VpnRun): Unit { for (phase in preparation) stage(phase) }
            override fun setUnderlying(prepared: Unit): Unit = stage("underlying")
            override fun establish(prepared: Unit): String {
                stage("establish")
                return "tunnel"
            }
            override fun start(prepared: Unit, tunnel: String): Unit {
                stage("start")
                events.add("mobile.start")
            }
            override suspend fun monitor(prepared: Unit, run: VpnRun): Unit {
                stage("monitor")
                run.publishIfActive {
                    events.add("running")
                    state.set("RUNNING")
                }
                running.countDown()
                if (blockCancellation) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation {
                            handlerEntered.countDown()
                            check(handlerRelease.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        }
                        handlerRegistered.countDown()
                    }
                } else {
                    awaitCancellation()
                }
            }
            override fun stop(): Unit = stage("stop")
            override fun close(tunnel: String): Unit = stage("close")
            override suspend fun cleanupService(run: VpnRun, complete: (Exception?) -> Unit): Unit {
                lifecycle.cleanup(
                    run = run,
                    dispatcher = fakeMain,
                    stopForeground = { stage("foreground") },
                    stopSelf = { id -> stage("stopSelf", "stopSelf:$id"); stopSelfReturns },
                    complete = complete,
                )
            }
            override fun finished(failure: VpnRunFailure?): Unit {
                this@Fixture.failure = failure
                events.add("finished")
                state.set(if (failure == null) "DISCONNECTED" else "ERROR")
                pause("terminalPublished")
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val BARRIER_TIMEOUT_MS = 30_000L
        private val preparation = listOf("read", "digest", "validate")
        private val fakeMain = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        @AfterClass @JvmStatic fun closeDispatcher(): Unit { fakeMain.close() }
    }
}
