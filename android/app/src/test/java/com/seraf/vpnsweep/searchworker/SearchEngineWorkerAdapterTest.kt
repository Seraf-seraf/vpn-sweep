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
        var interrupt = false
        var action: (() -> Unit)? = null
        val closeDone = CountDownLatch(1)
        override fun close(): Unit {
            closes++
            try {
                action?.invoke()
                if (interrupt) throw InterruptedException()
                if (fail) throw IOException("synthetic")
            } finally { closeDone.countDown() }
        }
    }

    private class Reply(private val bytes: ByteArray, private val failClose: Boolean = false) : PrivateInputV1 {
        var streamCloses = 0
        var closes = 0
        override fun open(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun close(): Unit { streamCloses++; super.close() }
        }
        override fun close(): Unit { closes++; if (failClose) throw IOException("synthetic") }
    }

    private class Port : DeadlineWorkerPort {
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
        var startupDeadline = 0L
        var closeDeadline = 0L
        var cancelDeadline = 0L
        var lastOperation: WorkerOperationV1? = null
        override fun openUntil(identity: WorkerIdentityV1, network: SearchNetworkV1, lease: SearchLeaseDuplicateV1,
            configuration: PrivateInputV1, startupDeadlineElapsedMillis: Long, cleanupMillis: Long): WorkerOpenV2 {
            openCount++
            startupDeadline = startupDeadlineElapsedMillis
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
        override fun cancelUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): Unit {
            cancelDeadline = cleanupDeadlineElapsedMillis
            cancelCount.incrementAndGet()
            cancelAction?.invoke()
        }
        override fun closeUntil(identity: WorkerIdentityV1, cleanupDeadlineElapsedMillis: Long): WorkerCloseV1 {
            closeDeadline = cleanupDeadlineElapsedMillis
            closeCount.incrementAndGet()
            closeAction?.invoke()
            return WorkerCloseV1.CLEAN_AND_DEAD
        }
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
            { creations.incrementAndGet(); WorkerRoundClient(Port(), Lease()) }, {}, System::nanoTime)
        assertEquals(Mobile.SearchWorkerCapabilityUnavailable, open(adapter))
        assertEquals(0, creations.get())
    }

    @Test
    fun typedCandidateAndRetryabilitySurviveCleanOpen(): Unit {
        val port = Port().apply { openResult = WorkerOpenV2.CandidateFailure(WorkerCandidateFailureV2.CONFIG_REJECTED, true) }
        val lease = Lease()
        val now = AtomicLong(TimeUnit.MILLISECONDS.toNanos(42))
        val adapter = adapter(port, lease, clock = now::get)
        assertEquals(Mobile.SearchWorkerCandidateConfigRejected or Mobile.SearchWorkerRetryableFlag, open(adapter))
        assertEquals(1, lease.closes)
        val frame = DataInputStream(ByteArrayInputStream(port.openFrame))
        assertEquals(10_042L, frame.readLong())
        assertEquals(7, frame.readInt())
        assertEquals(6, frame.readInt())
        assertEquals("profileparams", frame.readBytes().decodeToString())
        assertEquals(Mobile.SearchWorkerBusy, open(adapter, 1))
    }

    @Test
    fun callUsesFramedPortAndClosesReplyBeforeSuccess(): Unit {
        val reply = Reply("{\"probe\":\"synthetic\"}".toByteArray())
        val port = Port().apply { result = WorkerReplyV1.Complete(reply) }
        val now = AtomicLong(TimeUnit.MILLISECONDS.toNanos(42))
        val adapter = adapter(port, Lease(), clock = now::get)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        assertEquals("{\"fault\":0,\"result\":{\"probe\":\"synthetic\"}}", adapter.call(1, Mobile.SearchWorkerCheck, "probe".toByteArray(), 0, 700).decodeToString())
        assertEquals(WorkerOperationV1.CHECK, port.lastOperation)
        val frame = DataInputStream(ByteArrayInputStream(port.callFrame))
        assertEquals(742L, frame.readLong())
        assertEquals(0, frame.readInt())
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
            val response = adapter.call(1, Mobile.SearchWorkerUDP, byteArrayOf(1), 0, 700).decodeToString()
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
            val worker = Thread { response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 700)) }
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
            assertEquals("{\"fault\":$code}", adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 700).decodeToString())
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
        assertTrue(lease.closeDone.await(1, TimeUnit.SECONDS))
        assertEquals(1, lease.closes)
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
    }

    @Test
    fun cancelBeforeOpenIsStickyAndDoesNotCreateWorker(): Unit {
        val creations = AtomicInteger()
        val adapter = SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), 500, 1024,
            { creations.incrementAndGet(); WorkerRoundClient(Port(), Lease()) }, {}, System::nanoTime)
        adapter.cancel(1)
        assertEquals(Mobile.SearchWorkerCancelled, open(adapter))
        assertEquals(0, creations.get())
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter))
        assertEquals(0, creations.get())
    }

    @Test
    fun cancelledOrExpiredStartupConsumesGenerationWithoutWorker(): Unit {
        for (cancelFirst in listOf(false, true)) {
            val creations = AtomicInteger()
            val adapter = SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), 500, 1024,
                { creations.incrementAndGet(); WorkerRoundClient(Port(), Lease()) }, {}, System::nanoTime)
            if (cancelFirst) adapter.cancel(1)
            val first = adapter.open(42, 1, 1, 1, byteArrayOf(1), byteArrayOf(2), 5_000, 0)
            assertEquals(if (cancelFirst) Mobile.SearchWorkerCancelled else Mobile.SearchWorkerDeadline, first)
            assertEquals(0, creations.get())
            assertEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            assertEquals(1, creations.get())
            assertEquals(Mobile.SearchWorkerReady, adapter.close(2, 500))
        }
    }

    @Test
    fun cancelRacingBeforeOpenReservationDoesNotBind(): Unit {
        val creations = AtomicInteger()
        val ready = CountDownLatch(1)
        val allowOpen = CountDownLatch(1)
        val result = AtomicReference<Long>()
        val adapter = SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), 500, 1024,
            { creations.incrementAndGet(); WorkerRoundClient(Port(), Lease()) }, {}, System::nanoTime)
        val caller = Thread {
            ready.countDown()
            check(allowOpen.await(1, TimeUnit.SECONDS))
            result.set(open(adapter))
        }
        caller.start()
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            allowOpen.countDown()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertEquals(Mobile.SearchWorkerCancelled, result.get())
            assertEquals(0, creations.get())
        } finally { allowOpen.countDown() }
    }

    @Test
    fun interruptedReplyOpenPreservesFlagAndClosesInput(): Unit {
        val entered = CountDownLatch(1)
        val closed = AtomicInteger()
        val closedSignal = CountDownLatch(1)
        val input = object : PrivateInputV1 {
            override fun open(): InputStream {
                entered.countDown()
                CountDownLatch(1).await()
                throw AssertionError("unexpected")
            }
            override fun close(): Unit { closed.incrementAndGet(); closedSignal.countDown() }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input) }
        val adapter = adapter(port, Lease())
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val preserved = AtomicBoolean()
        val response = AtomicReference<ByteArray>()
        val worker = Thread {
            response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 700))
            preserved.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(preserved.get())
        assertTrue(closedSignal.await(1, TimeUnit.SECONDS))
        assertEquals(1, closed.get())
        assertEquals("{\"fault\":${Mobile.SearchWorkerDeliveryUnknown}}", response.get().decodeToString())
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
    }

    @Test
    fun interruptedLeaseClosePreservesFlagAndQuarantines(): Unit {
        val lease = Lease().apply { interrupt = true }
        val adapter = adapter(Port(), lease)
        val result = AtomicReference<Long>()
        val preserved = AtomicBoolean()
        val worker = Thread {
            result.set(open(adapter))
            preserved.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(Mobile.SearchWorkerCleanupIncomplete, result.get())
        assertTrue(preserved.get())
        assertEquals(1, lease.closes)
        assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
    }

    @Test
    fun blockedReplyReadAfterCancelDoesNotHoldCallerOrPermitNextRound(): Unit {
        val readEntered = CountDownLatch(1)
        val readRelease = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = object : InputStream() {
                override fun read(): Int { readEntered.countDown(); awaitIgnoringInterrupt(readRelease); return -1 }
            }
            override fun close(): Unit { inputClosed.countDown() }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input) }
        val adapter = adapter(port, Lease(), cleanup = 100)
        val response = AtomicReference<ByteArray>()
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val caller = Thread {
            response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000))
            callerReturned.countDown()
        }
        caller.start()
        try {
            assertTrue(readEntered.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue("cancel did not bound the caller", callerReturned.await(1, TimeUnit.SECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        } finally {
            readRelease.countDown()
            caller.join(2000)
        }
        assertTrue(inputClosed.await(1, TimeUnit.SECONDS))
        assertTrue(response.get().decodeToString() in setOf("{\"fault\":${Mobile.SearchWorkerDeliveryUnknown}}",
            "{\"fault\":${Mobile.SearchWorkerCleanupIncomplete}}", "{\"fault\":${Mobile.SearchWorkerUnavailable}}"))
    }

    @Test
    fun blockedSenderLeaseCloseCannotHoldOpenOrReadmit(): Unit {
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val lease = Lease().apply { action = { closeEntered.countDown(); awaitIgnoringInterrupt(closeRelease) } }
        val adapter = adapter(Port(), lease, cleanup = 100)
        val outcome = AtomicReference<Long>()
        val caller = Thread {
            outcome.set(adapter.open(42, 1, 1, 1, byteArrayOf(1), byteArrayOf(2), 5_000, 100))
            returned.countDown()
        }
        caller.start()
        try {
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            assertTrue("lease close did not respect startup/cleanup deadline", returned.await(1, TimeUnit.SECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, outcome.get())
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        } finally {
            closeRelease.countDown()
            caller.join(2000)
        }
        assertEquals(1, lease.closes)
    }

    @Test
    fun blockedReplyCloseStaysQuarantinedUntilItsOwnerFinishes(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = object : ByteArrayInputStream("{}".toByteArray()) {
                override fun close(): Unit { entered.countDown(); awaitIgnoringInterrupt(release); super.close() }
            }
            override fun close(): Unit { inputClosed.countDown() }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input) }
        val adapter = adapter(port, Lease(), cleanup = 100)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val caller = Thread { adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000); returned.countDown() }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            assertEquals(1L, inputClosed.count)
        } finally {
            release.countDown()
            caller.join(2000)
        }
        assertTrue(inputClosed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun candidateOutcomeWaitsForInFlightCancelDisposition(): Unit {
        val openEntered = CountDownLatch(1)
        val openRelease = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val port = Port().apply {
            openResult = WorkerOpenV2.CandidateFailure(WorkerCandidateFailureV2.CONFIG_REJECTED, false)
            openAction = { openEntered.countDown(); check(openRelease.await(2, TimeUnit.SECONDS)) }
            cancelAction = { cancelEntered.countDown(); check(cancelRelease.await(2, TimeUnit.SECONDS)); throw IOException("synthetic") }
        }
        val adapter = adapter(port, Lease(), cleanup = 1_000)
        val result = AtomicReference<Long>()
        val first = Thread { result.set(open(adapter)); firstReturned.countDown() }
        first.start()
        try {
            assertTrue(openEntered.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue(cancelEntered.await(1, TimeUnit.SECONDS))
            openRelease.countDown()
            assertFalse(firstReturned.await(100, TimeUnit.MILLISECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            cancelRelease.countDown()
            assertTrue(firstReturned.await(1, TimeUnit.SECONDS))
            assertEquals(Mobile.SearchWorkerDeliveryUnknown, result.get())
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        } finally {
            openRelease.countDown()
            cancelRelease.countDown()
            first.join(2000)
        }
    }

    @Test
    fun cancelAfterKnownOutcomeCannotDispatchDuringRetirement(): Unit {
        val leaseEntered = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val finishing = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val afterClose = AtomicInteger()
        val clock = {
            if (closed.get() && afterClose.incrementAndGet() == 2) {
                finishing.countDown()
                check(releaseFinish.await(2, TimeUnit.SECONDS))
            }
            System.nanoTime()
        }
        val lease = Lease().apply { action = { leaseEntered.countDown(); check(releaseLease.await(2, TimeUnit.SECONDS)); closed.set(true) } }
        val port = Port().apply { openResult = WorkerOpenV2.CandidateFailure(WorkerCandidateFailureV2.CONFIG_REJECTED, false) }
        val adapter = adapter(port, lease, clock = clock)
        val result = AtomicReference<Long>()
        val caller = Thread { result.set(open(adapter)) }
        caller.start()
        try {
            assertTrue(leaseEntered.await(1, TimeUnit.SECONDS))
            val waitLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (caller.state != Thread.State.TIMED_WAITING && System.nanoTime() < waitLimit) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, caller.state)
            releaseLease.countDown()
            assertTrue(finishing.await(1, TimeUnit.SECONDS))
            val cancelReturned = CountDownLatch(1)
            val canceller = Thread { adapter.cancel(1); cancelReturned.countDown() }
            canceller.start()
            assertTrue("cancel blocked on retirement lock", cancelReturned.await(1, TimeUnit.SECONDS))
            assertEquals(0, port.cancelCount.get())
            releaseFinish.countDown()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertEquals(Mobile.SearchWorkerCancelled, result.get())
            assertEquals(0, port.cancelCount.get())
        } finally {
            releaseLease.countDown()
            releaseFinish.countDown()
            caller.join(2000)
        }
    }

    @Test
    fun inputBoundaryInterruptsPreserveFlagAndCloseDisposition(): Unit {
        for (boundary in listOf("open", "read", "stream-close", "input-close")) {
            val inputCloses = AtomicInteger()
            val input = object : PrivateInputV1 {
                override fun open(): InputStream {
                    if (boundary == "open") throw InterruptedException()
                    return object : InputStream() {
                        override fun read(): Int {
                            if (boundary == "read") throw InterruptedException()
                            return -1
                        }
                        override fun close(): Unit {
                            if (boundary == "stream-close") throw InterruptedException()
                        }
                    }
                }
                override fun close(): Unit {
                    inputCloses.incrementAndGet()
                    if (boundary == "input-close") throw InterruptedException()
                }
            }
            val adapter = adapter(Port().apply { result = WorkerReplyV1.Complete(input) }, Lease())
            assertEquals(Mobile.SearchWorkerReady, open(adapter))
            val response = AtomicReference<String>()
            val preserved = AtomicBoolean()
            val caller = Thread {
                response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 700).decodeToString())
                preserved.set(Thread.currentThread().isInterrupted)
            }
            caller.start()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertTrue(boundary, preserved.get())
            assertEquals(1, inputCloses.get())
            val code = if (boundary == "stream-close" || boundary == "input-close")
                Mobile.SearchWorkerCleanupIncomplete else Mobile.SearchWorkerUnavailable
            assertEquals("{\"fault\":$code}", response.get())
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        }
    }

    @Test
    fun expiredBudgetCannotReturnReadyOrProbeSuccess(): Unit {
        val now = AtomicLong()
        val openPort = Port().apply { openAction = { now.set(TimeUnit.MILLISECONDS.toNanos(200)) } }
        val openAdapter = adapter(openPort, Lease(), cleanup = 1_000, clock = now::get)
        val openResult = openAdapter.open(42, 1, 1, 1, byteArrayOf(1), byteArrayOf(2), 5_000, 100)
        assertNotEquals(Mobile.SearchWorkerReady, openResult)

        now.set(0)
        val callPort = Port().apply {
            result = WorkerReplyV1.Complete(Reply("{}".toByteArray()))
            executeAction = { now.set(TimeUnit.MILLISECONDS.toNanos(200)) }
        }
        val callAdapter = adapter(callPort, Lease(), cleanup = 1_000, clock = now::get)
        assertEquals(Mobile.SearchWorkerReady, open(callAdapter))
        val reply = callAdapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 100).decodeToString()
        assertNotEquals("{\"fault\":0,\"result\":{}}", reply)
        assertNotEquals(Mobile.SearchWorkerReady, open(callAdapter, 2))
    }

    @Test
    fun closeCannotPassAfterReplyCloseBeforeCallDisposition(): Unit {
        val replyClosing = CountDownLatch(1)
        val releaseReplyClose = CountDownLatch(1)
        val callerAtDisposition = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        val replyClosed = AtomicBoolean(false)
        val trapped = AtomicBoolean(false)
        val clock = {
            if (replyClosed.get() && trapped.compareAndSet(false, true)) {
                callerAtDisposition.countDown()
                check(releaseCaller.await(2, TimeUnit.SECONDS))
            }
            System.nanoTime()
        }
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = ByteArrayInputStream("{}".toByteArray())
            override fun close(): Unit {
                replyClosing.countDown()
                check(releaseReplyClose.await(2, TimeUnit.SECONDS))
                replyClosed.set(true)
            }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input) }
        val adapter = adapter(port, Lease(), cleanup = 500, clock = clock)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val response = AtomicReference<String>()
        val caller = Thread { response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 2_000).decodeToString()) }
        caller.start()
        try {
            assertTrue(replyClosing.await(1, TimeUnit.SECONDS))
            val waitLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (caller.state != Thread.State.TIMED_WAITING && System.nanoTime() < waitLimit) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, caller.state)
            releaseReplyClose.countDown()
            assertTrue(callerAtDisposition.await(1, TimeUnit.SECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            releaseCaller.countDown()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertNotEquals("{\"fault\":0,\"result\":{}}", response.get())
        } finally {
            releaseReplyClose.countDown()
            releaseCaller.countDown()
            caller.join(2000)
        }
    }

    @Test
    fun cooperativeCancelledReadHasKnownDispositionAndNeedsWorkerClose(): Unit {
        val entered = CountDownLatch(1)
        val streamClosed = AtomicInteger()
        val inputClosed = AtomicInteger()
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = object : InputStream() {
                override fun read(): Int { entered.countDown(); CountDownLatch(1).await(); return -1 }
                override fun close(): Unit { streamClosed.incrementAndGet() }
            }
            override fun close(): Unit { inputClosed.incrementAndGet() }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input) }
        val adapter = adapter(port, Lease())
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val response = AtomicReference<String>()
        val caller = Thread { response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000).decodeToString()) }
        caller.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        adapter.cancel(1)
        caller.join(2000)
        assertFalse(caller.isAlive)
        assertEquals("{\"fault\":${Mobile.SearchWorkerCancelled}}", response.get())
        assertEquals(1, streamClosed.get())
        assertEquals(1, inputClosed.get())
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
        assertEquals(1, port.closeCount.get())
        assertEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        assertEquals(Mobile.SearchWorkerReady, adapter.close(2, 500))
    }

    @Test
    fun oneBasedIdentityMatchesZeroBasedProbeRounds(): Unit {
        val port = Port().apply { result = WorkerReplyV1.Complete(Reply("{}".toByteArray())) }
        val adapter = adapter(port, Lease())
        assertEquals(Mobile.SearchWorkerReady, adapter.open(42, 1, 1, 1, byteArrayOf(1), byteArrayOf(2), 5_000, 3_000))
        assertEquals("{\"fault\":0,\"result\":{}}", adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(3), 0, 700).decodeToString())
        val first = DataInputStream(ByteArrayInputStream(port.callFrame))
        first.readLong()
        assertEquals(0, first.readInt())
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
        assertEquals(Mobile.SearchWorkerReady, adapter.open(42, 1, 2, 2, byteArrayOf(1), byteArrayOf(2), 5_000, 3_000))
        assertEquals("{\"fault\":0,\"result\":{}}", adapter.call(2, Mobile.SearchWorkerUDP, byteArrayOf(3), 1, 700).decodeToString())
        val second = DataInputStream(ByteArrayInputStream(port.callFrame))
        second.readLong()
        assertEquals(1, second.readInt())
        assertEquals(Mobile.SearchWorkerReady, adapter.close(2, 500))
    }

    @Test
    fun cancelledReadThatCompletesDiscardsBytesAndStillClosesWorker(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val streamClosed = AtomicInteger()
        val inputClosed = AtomicInteger()
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = object : ByteArrayInputStream("{}".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    awaitIgnoringInterrupt(release)
                    return super.read(bytes, offset, length)
                }
                override fun close(): Unit { streamClosed.incrementAndGet(); super.close() }
            }
            override fun close(): Unit { inputClosed.incrementAndGet() }
        }
        val port = Port().apply { result = WorkerReplyV1.Complete(input); cancelAction = { cancelled.countDown() } }
        val adapter = adapter(port, Lease(), cleanup = 1_000)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val response = AtomicReference<String>()
        val caller = Thread { response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000).decodeToString()) }
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            release.countDown()
            caller.join(2000)
            assertFalse(caller.isAlive)
            assertEquals("{\"fault\":${Mobile.SearchWorkerCancelled}}", response.get())
            assertEquals(1, streamClosed.get())
            assertEquals(1, inputClosed.get())
            assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 1_000))
            assertEquals(1, port.closeCount.get())
        } finally { release.countDown(); caller.join(2000) }
    }

    @Test
    fun deadlinesUseCallbackStartBeforeRoundCreationAndTransport(): Unit {
        val now = AtomicLong(TimeUnit.MILLISECONDS.toNanos(7_000))
        val port = Port().apply { result = WorkerReplyV1.Complete(Reply("{}".toByteArray())) }
        val adapter = SearchEngineWorkerAdapter(42, SearchNetworkV1(11, 3), 500, 1024,
            { now.set(TimeUnit.MILLISECONDS.toNanos(7_100)); WorkerRoundClient(port, Lease()) }, {}, now::get)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        assertEquals(12_000L, port.startupDeadline)
        assertEquals(17_000L, DataInputStream(ByteArrayInputStream(port.openFrame)).readLong())
        now.set(TimeUnit.MILLISECONDS.toNanos(8_000))
        port.executeAction = { now.set(TimeUnit.MILLISECONDS.toNanos(8_100)) }
        assertEquals("{\"fault\":0,\"result\":{}}", adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 700).decodeToString())
        assertEquals(8_700L, DataInputStream(ByteArrayInputStream(port.callFrame)).readLong())
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
        assertEquals(8_600L, port.closeDeadline)
    }

    @Test
    fun cleanupDeadlineIsNotRestartedAfterCancel(): Unit {
        val now = AtomicLong(TimeUnit.MILLISECONDS.toNanos(7_000))
        val cancelled = CountDownLatch(1)
        val port = Port().apply {
            cancelAction = { now.set(TimeUnit.MILLISECONDS.toNanos(7_400)); cancelled.countDown() }
        }
        val adapter = adapter(port, Lease(), cleanup = 500, clock = now::get)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        adapter.cancel(1)
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 500))
        assertEquals(7_500L, port.cancelDeadline)
        assertEquals(7_500L, port.closeDeadline)
    }

    @Test
    fun completedReplyWinsBeforeLaterCancelAndWorkerStillCloses(): Unit {
        val cancelled = CountDownLatch(1)
        val reply = Reply("{}".toByteArray())
        val port = Port().apply { result = WorkerReplyV1.Complete(reply); cancelAction = { cancelled.countDown() } }
        val adapter = adapter(port, Lease(), cleanup = 1_000)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        assertEquals("{\"fault\":0,\"result\":{}}", adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000).decodeToString())
        adapter.cancel(1)
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, reply.streamCloses)
        assertEquals(1, reply.closes)
        assertEquals(Mobile.SearchWorkerReady, adapter.close(1, 1_000))
        assertEquals(1, port.closeCount.get())
    }

    @Test
    fun completedReplyWaitsForUnknownCancelAndQuarantines(): Unit {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val streamCloses = AtomicInteger()
        val inputCloses = AtomicInteger()
        val input = object : PrivateInputV1 {
            override fun open(): InputStream = object : ByteArrayInputStream("{}".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    readEntered.countDown()
                    awaitIgnoringInterrupt(releaseRead)
                    return super.read(bytes, offset, length)
                }
                override fun close(): Unit { streamCloses.incrementAndGet(); super.close() }
            }
            override fun close(): Unit { inputCloses.incrementAndGet(); inputClosed.countDown() }
        }
        val port = Port().apply {
            result = WorkerReplyV1.Complete(input)
            cancelAction = { cancelEntered.countDown(); check(releaseCancel.await(2, TimeUnit.SECONDS)); throw IOException("synthetic") }
        }
        val adapter = adapter(port, Lease(), cleanup = 1_500)
        assertEquals(Mobile.SearchWorkerReady, open(adapter))
        val response = AtomicReference<String>()
        val caller = Thread {
            response.set(adapter.call(1, Mobile.SearchWorkerCheck, byteArrayOf(1), 0, 5_000).decodeToString())
            callerReturned.countDown()
        }
        caller.start()
        try {
            assertTrue(readEntered.await(2, TimeUnit.SECONDS))
            adapter.cancel(1)
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
            releaseRead.countDown()
            assertTrue(inputClosed.await(2, TimeUnit.SECONDS))
            assertFalse(callerReturned.await(100, TimeUnit.MILLISECONDS))
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
            releaseCancel.countDown()
            assertTrue(callerReturned.await(2, TimeUnit.SECONDS))
            assertEquals("{\"fault\":${Mobile.SearchWorkerDeliveryUnknown}}", response.get())
            assertEquals(1, streamCloses.get())
            assertEquals(1, inputCloses.get())
            assertNotEquals(Mobile.SearchWorkerReady, open(adapter, 2))
        } finally { releaseRead.countDown(); releaseCancel.countDown(); caller.join(3000) }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch): Unit {
        var interrupted = false
        while (true) {
            try {
                check(latch.await(2, TimeUnit.SECONDS))
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
