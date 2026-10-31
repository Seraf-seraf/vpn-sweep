package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

internal class SearchEngineWorkerAdapterTest {
    private class Lease : SearchLeaseDuplicateV1 {
        var closes = 0
        var fail = false
        override fun close(): Unit { closes++; if (fail) throw IOException("synthetic") }
    }

    private class Reply(private val bytes: ByteArray, private val failClose: Boolean = false) : PrivateInputV1 {
        var streamCloses = 0
        var closes = 0
        override fun open(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun close(): Unit { streamCloses++; super.close() }
        }
        override fun close(): Unit { closes++; if (failClose) throw IOException("synthetic") }
    }

    private class Port : SearchWorkerPortV2 {
        var openResult: WorkerOpenV2 = WorkerOpenV2.Ready
        var openCount = 0
        var closeCount = AtomicInteger()
        var cancelCount = AtomicInteger()
        var result: WorkerReplyV1 = WorkerReplyV1.Rejected
        var openAction: (() -> Unit)? = null
        var executeAction: (() -> Unit)? = null
        var cancelAction: (() -> Unit)? = null
        var closeAction: (() -> Unit)? = null
        var openFrame = byteArrayOf()
        var callFrame = byteArrayOf()
        var lastOperation: WorkerOperationV1? = null
        override fun open(identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
            configuration: PrivateInputV1, startupMillis: Long, cleanupMillis: Long): WorkerOpenV2 {
            openCount++
            openFrame = configuration.open().use { it.readBytes() }
            openAction?.invoke()
            return openResult
        }
        override fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1 {
            lastOperation = operation
            callFrame = input.open().use { it.readBytes() }
            executeAction?.invoke()
            return result
        }
        override fun cancel(identity: WorkerIdentityV1): Unit { cancelCount.incrementAndGet(); cancelAction?.invoke() }
        override fun close(identity: WorkerIdentityV1): WorkerCloseV1 { closeCount.incrementAndGet(); closeAction?.invoke(); return WorkerCloseV1.CLEAN_AND_DEAD }
    }

    private fun adapter(port: Port, lease: Lease, bound: Int? = 1024, cleanup: Long = 500,
        clock: () -> Long = System::nanoTime): SearchEngineWorkerAdapter =
        SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), cleanup, bound,
            { WorkerRoundClient(port, lease) }, {}, clock)

    private fun open(adapter: SearchEngineWorkerAdapter, generation: Long = 1): Long =
        adapter.open(42, 1, 1, generation, "profile".toByteArray(), "params".toByteArray(), 10_000, 5_000)

    @Test
    fun absentProvenBoundDeniesBeforeCreatingAnyWorker(): Unit {
        val creations = AtomicInteger()
        val adapter = SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), 500, null,
            { creations.incrementAndGet(); WorkerRoundClient(Port(), Lease()) }, {})
        assertEquals(Mobile.SearchWorkerCapabilityUnavailable, open(adapter))
        assertEquals(0, creations.get())
    }

    @Test
    fun typedCandidateAndRetryabilitySurviveCleanOpen(): Unit {
        val port = Port().apply { openResult = WorkerOpenV2.CandidateFailure(WorkerCandidateFailureV2.CONFIG_REJECTED, true) }
        val lease = Lease()
        val adapter = adapter(port, lease)
        assertEquals(Mobile.SearchWorkerCandidateConfigRejected or Mobile.SearchWorkerRetryableFlag, open(adapter))
        assertEquals(1, lease.closes)
        val frame = DataInputStream(ByteArrayInputStream(port.openFrame))
        assertTrue(frame.readLong() in 1L..10_000L)
        assertEquals(7, frame.readInt())
        assertEquals(6, frame.readInt())
        assertEquals("profileparams", frame.readBytes().decodeToString())
        assertEquals(Mobile.SearchWorkerBusy, open(adapter, 1))
    }

    @Test
    fun callUsesFramedPortAndClosesReplyBeforeSuccess(): Unit {
        val reply = Reply("{\"probe\":\"synthetic\"}".toByteArray())
        val port = Port().apply { result = WorkerReplyV1.Complete(reply) }
        val adapter = adapter(port, Lease())
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        assertEquals("{\"fault\":0,\"result\":{\"probe\":\"synthetic\"}}", adapter.call(1, Mobile.SearchWorkerCheck, "probe".toByteArray(), 1, 700).decodeToString())
        assertEquals(WorkerOperationV1.CHECK, port.lastOperation)
        val frame = DataInputStream(ByteArrayInputStream(port.callFrame))
        assertTrue(frame.readLong() in 1L..700L)
        assertEquals(1, frame.readInt())
        assertEquals(5, frame.readInt())
        assertEquals("probe", frame.readBytes().decodeToString())
        assertEquals(1, reply.streamCloses)
        assertEquals(1, reply.closes)
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
        assertEquals(1, port.closeCount.get())
    }

    @Test
    fun oversizedOrUnclosedReplyQuarantinesAndPreventsNextOpen(): Unit {
        for ((reply, expected) in listOf(Reply(ByteArray(33)) to Mobile.SearchWorkerUnavailable,
            Reply(byteArrayOf(1), failClose = true) to Mobile.SearchWorkerCleanupIncomplete)) {
            val port = Port().apply { result = WorkerReplyV1.Complete(reply) }
            val adapter = adapter(port, Lease(), bound = 32)
            assertEquals(Mobile.SearchWorkerReady, open(adapter))
            val response = adapter.call(1, Mobile.SearchWorkerUDP, byteArrayOf(1), 1, 700).decodeToString()
            assertEquals("{\"fault\":$expected}", response)
            assertEquals(1, reply.streamCloses)
            assertEquals(1, reply.closes)
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        }
    }

    @Test
    fun cancelDuringOpenIsStickyAndReadyCannotEscape(): Unit {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val port = Port().apply {
            openAction = { entered.countDown(); check(cancelled.await(1, TimeUnit.SECONDS)) }
            cancelAction = { cancelled.countDown() }
        }
        val adapter = adapter(port, Lease())
        val result = AtomicReference<Long>()
        val worker = Thread { result.set(open(adapter)) }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        adapter.cancel(1)
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(Mobile.SearchWorkerCancelled, result.get())
        assertEquals(1, port.cancelCount.get())
        assertEquals(1, port.closeCount.get())
    }

    @Test
    fun cleanupDeadlineIsSharedByCancelAndClose(): Unit {
        val now = AtomicLong()
        val releaseClose = CountDownLatch(1)
        val port = Port().apply {
            cancelAction = { now.set(TimeUnit.MILLISECONDS.toNanos(90)) }
            closeAction = { check(releaseClose.await(1, TimeUnit.SECONDS)) }
        }
        val adapter = adapter(port, Lease(), cleanup = 100, clock = now::get)
        try {
            assertEquals(Mobile.SearchWorkerReady, open(adapter))
            adapter.cancel(1)
            assertEquals(Mobile.SearchWorkerCleanupIncomplete, adapter.close(1, 100))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        } finally { releaseClose.countDown() }
    }

    @Test
    fun unknownOpenAndFailedLeaseCloseNeverReplay(): Unit {
        val port = Port()
        val lease = Lease().apply { fail = true }
        val adapter = adapter(port, lease)
        assertEquals(Mobile.SearchWorkerCleanupIncomplete, open(adapter))
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        assertEquals(1, port.openCount)
    }

    @Test
    fun unknownOpenDeliveryStaysQuarantinedDespiteLaterCleanup(): Unit {
        val port = Port().apply { openAction = { throw IOException("synthetic") } }
        val lease = Lease()
        val adapter = adapter(port, lease)
        assertEquals(Mobile.SearchWorkerDeliveryUnknown, open(adapter))
        assertEquals(1, lease.closes)
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        assertEquals(1, port.openCount)
    }

    @Test
    fun closeDuringUnreturnedCallCannotReleaseOrReadmit(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reply = Reply("{}".toByteArray())
        val port = Port().apply {
            result = WorkerReplyV1.Complete(reply)
            executeAction = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        }
        val adapter = adapter(port, Lease())
        try {
            assertEquals(Mobile.SearchWorkerReady, open(adapter))
            val response = AtomicReference<ByteArray>()
            val worker = Thread { response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 1, 700)) }
            worker.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(Mobile.SearchWorkerCleanupIncomplete, adapter.close(1, 500))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            release.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals("{\"fault\":${Mobile.SearchWorkerCleanupIncomplete}}", response.get().decodeToString())
            assertEquals(1, reply.streamCloses)
            assertEquals(1, reply.closes)
        } finally { release.countDown() }
    }

    @Test
    fun knownInterruptedCallKeepsItsReasonAndStillRequiresClose(): Unit {
        for ((reason, code) in listOf(WorkerInterruptedV2.CANCELLED to Mobile.SearchWorkerCancelled,
            WorkerInterruptedV2.DEADLINE to Mobile.SearchWorkerDeadline)) {
            val port = Port().apply { result = WorkerReplyV1.Interrupted(reason) }
            val adapter = adapter(port, Lease())
            assertEquals(Mobile.SearchWorkerReady, open(adapter))
            assertEquals("{\"fault\":$code}", adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 1, 700).decodeToString())
            assertEquals(Mobile.SearchWorkerReady, adapter.failure(1))
            assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
            assertEquals(1, port.closeCount.get())
        }
    }

    @Test
    fun unknownCancelDuringOpenAttemptsCleanupAndRemainsQuarantined(): Unit {
        val entered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val port = Port().apply {
            openAction = { entered.countDown(); check(releaseOpen.await(2, TimeUnit.SECONDS)) }
            cancelAction = { throw IOException("synthetic") }
            closeAction = { cleanupStarted.countDown() }
        }
        val adapter = adapter(port, Lease())
        val result = AtomicReference<Long>()
        val worker = Thread { result.set(open(adapter)) }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS))
            assertEquals(Mobile.SearchWorkerDeliveryUnknown, adapter.failure(1))
            releaseOpen.countDown()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(Mobile.SearchWorkerDeliveryUnknown, result.get())
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            assertEquals(1, port.closeCount.get())
        } finally { releaseOpen.countDown() }
    }

    @Test
    fun interruptedPortCallPreservesThreadInterruptAndQuarantines(): Unit {
        val port = Port().apply { openAction = { throw InterruptedException() } }
        val lease = Lease()
        val adapter = adapter(port, lease)
        val result = AtomicReference<Long>()
        val interruptPreserved = AtomicBoolean()
        val worker = Thread {
            result.set(open(adapter))
            interruptPreserved.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(Mobile.SearchWorkerDeliveryUnknown, result.get())
        assertTrue(interruptPreserved.get())
        assertEquals(1, lease.closes)
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
    }
}
