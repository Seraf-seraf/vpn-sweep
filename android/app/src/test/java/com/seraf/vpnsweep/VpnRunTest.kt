package com.seraf.vpnsweep

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRunTest {
    @Test
    fun stopBeforeJobAssignmentStillRunsCleanup(): Unit = runBlocking {
        val registry = VpnRunRegistry()
        val run = registry.reserve()!!
        val operations = FakeOperations()
        val dispatcher = QueuedDispatcher { assertTrue(run.stop()) }
        val job = run.launch(CoroutineScope(coroutineContext + dispatcher), operations)

        assertNull(registry.reserve())
        dispatcher.drain()
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(listOf("stopping", "stop", "finished"), operations.events)
        assertNull(operations.failure)
        assertNotNull(registry.reserve())
    }

    @Test
    fun stopDuringEachPreparationPhasePreventsActivation(): Unit = runBlocking {
        for (phase in preparation) {
            val registry = VpnRunRegistry()
            val run = registry.reserve()!!
            val operations = FakeOperations(blockAt = phase)
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

            stopWhileBlocked(run, operations)
            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals(preparation + listOf("stopping", "stop", "finished"), operations.events)
            assertNull(operations.failure)
            assertNotNull(registry.reserve())
        }
    }

    @Test
    fun stopDuringUnderlyingNetworkPreventsEstablish(): Unit = runBlocking {
        val run = VpnRunRegistry().reserve()!!
        val operations = FakeOperations(blockAt = "underlying")
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

        stopWhileBlocked(run, operations)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(preparation + listOf("underlying", "stopping", "stop", "finished"), operations.events)
    }

    @Test
    fun stopDuringEstablishClosesReturnedTunnelWithoutStartingCore(): Unit = runBlocking {
        val run = VpnRunRegistry().reserve()!!
        val operations = FakeOperations(blockAt = "establish")
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

        stopWhileBlocked(run, operations)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(
            preparation + listOf("underlying", "establish", "stopping", "stop", "close", "finished"),
            operations.events,
        )
        assertNull(operations.failure)
    }

    @Test
    fun stopDuringCoreStartWaitsForReturnAndNeverPublishesRunning(): Unit = runBlocking {
        val run = VpnRunRegistry().reserve()!!
        val operations = FakeOperations(blockAt = "start")
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

        stopWhileBlocked(run, operations)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(
            preparation + listOf("underlying", "establish", "start", "stopping", "stop", "close", "finished"),
            operations.events,
        )
        assertNull(operations.failure)
    }

    @Test
    fun failedCoreStartAfterStopStillCleansUp(): Unit = runBlocking {
        val registry = VpnRunRegistry()
        val run = registry.reserve()!!
        val operations = FakeOperations(blockAt = "start", failAt = setOf("start"))
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

        stopWhileBlocked(run, operations)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertEquals(listOf("stopping", "stop", "close", "finished"), operations.events.takeLast(4))
        assertEquals("start", (operations.failure as VpnRunFailure.Operation).exception.message)
        assertNotNull(registry.reserve())
    }

    @Test
    fun stopBeforeStatusPublicationPreventsRunningAfterStop(): Unit = runBlocking {
        val run = VpnRunRegistry().reserve()!!
        val operations = FakeOperations(blockAt = "monitor")
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

        stopWhileBlocked(run, operations)
        withTimeout(TIMEOUT_MS) { job.join() }
        assertFalse(operations.events.contains("running"))
        assertEquals(listOf("stopping", "stop", "close", "finished"), operations.events.takeLast(4))
    }

    @Test
    fun repeatedStopAndNewStartWaitForCleanup(): Unit = runBlocking {
        val registry = VpnRunRegistry()
        val run = registry.reserve()!!
        val operations = FakeOperations(blockAt = "stop")
        val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)
        assertTrue(operations.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(stopPromptly(run))
        try {
            assertTrue(operations.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
            assertNull(registry.reserve())
        } finally {
            operations.proceed.countDown()
        }
        withTimeout(TIMEOUT_MS) { job.join() }
        assertFalse(run.stop())
        assertEquals(1, operations.events.count { it == "stop" })
        assertEquals(listOf("stopping", "stop", "close", "finished"), operations.events.takeLast(4))
        val nextRun = registry.reserve()!!
        val nextOperations = FakeOperations()
        val nextJob = nextRun.launch(CoroutineScope(coroutineContext + Dispatchers.IO), nextOperations)
        assertTrue(nextOperations.running.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(stopPromptly(nextRun))
        withTimeout(TIMEOUT_MS) { nextJob.join() }
        assertEquals(listOf("stopping", "stop", "close", "finished"), nextOperations.events.takeLast(4))
    }

    @Test
    fun operationErrorsKeepCauseAndCleanUpOwnedResources(): Unit = runBlocking {
        for (phase in listOf("read", "underlying", "establish", "start", "monitor")) {
            val registry = VpnRunRegistry()
            val run = registry.reserve()!!
            val operations = FakeOperations(failAt = setOf(phase))
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

            withTimeout(TIMEOUT_MS) { job.join() }
            val failure = operations.failure as VpnRunFailure.Operation
            assertEquals(phase, failure.exception.message)
            assertEquals(1, operations.events.count { it == "stop" })
            assertEquals(if (phase == "start" || phase == "monitor") 1 else 0, operations.events.count { it == "close" })
            assertEquals("finished", operations.events.last())
            assertNotNull(registry.reserve())
        }
    }

    @Test
    fun cleanupContinuesAfterErrorsAndCloseFailureTakesPrecedence(): Unit = runBlocking {
        for ((failAt, expected) in listOf(
            setOf("start", "stop") to VpnRunFailure.Stop,
            setOf("start", "close") to VpnRunFailure.Close,
            setOf("start", "stop", "close") to VpnRunFailure.Close,
        )) {
            val registry = VpnRunRegistry()
            val run = registry.reserve()!!
            val operations = FakeOperations(failAt = failAt)
            val job = run.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)

            withTimeout(TIMEOUT_MS) { job.join() }
            assertEquals(listOf("stopping", "stop", "close", "finished"), operations.events.takeLast(4))
            assertEquals(expected, operations.failure)
            assertNotNull(registry.reserve())
        }
    }

    private fun stopWhileBlocked(run: VpnRun, operations: FakeOperations): Unit {
        try {
            assertTrue(operations.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(stopPromptly(run))
        } finally {
            operations.proceed.countDown()
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

    private class QueuedDispatcher(private val onDispatch: () -> Unit) : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable): Unit {
            queue.add(block)
            onDispatch()
        }

        fun drain(): Unit {
            while (true) (queue.poll() ?: break).run()
        }
    }

    private class FakeOperations(
        private val blockAt: String? = null,
        private val failAt: Set<String> = emptySet(),
    ) : VpnRunOperations<Unit, String> {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val running = CountDownLatch(1)
        @Volatile var failure: VpnRunFailure? = null

        private fun stage(name: String): Unit {
            events.add(name)
            if (blockAt == name) {
                entered.countDown()
                check(proceed.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "barrier timed out: $name" }
            }
            if (name in failAt) error(name)
        }

        override fun prepare(run: VpnRun): Unit {
            for (phase in preparation) stage(phase)
        }
        override fun setUnderlying(prepared: Unit): Unit = stage("underlying")
        override fun establish(prepared: Unit): String {
            stage("establish")
            return "tunnel"
        }
        override fun start(prepared: Unit, tunnel: String): Unit = stage("start")
        override suspend fun monitor(prepared: Unit, run: VpnRun): Unit {
            stage("monitor")
            run.publishIfActive { events.add("running") }
            running.countDown()
            awaitCancellation()
        }
        override fun stopping(): Unit = stage("stopping")
        override fun stop(): Unit = stage("stop")
        override fun close(tunnel: String): Unit = stage("close")
        override fun finished(failure: VpnRunFailure?): Unit {
            this.failure = failure
            stage("finished")
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val BARRIER_TIMEOUT_MS = 30_000L
        private val preparation = listOf("read", "digest", "validate")
    }
}
