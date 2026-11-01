package com.seraf.vpnsweep

import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class OwnerBusyAutoRecoveryTest {
    @Test fun newPendingRequestWhileOldCallbackFinishesIsNotLost(): Unit {
        val queue = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val pending = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val finishes = AtomicInteger()
        val recovery = OwnerBusyAutoRecovery(
            schedule = { queue.add(it); true },
            pending = pending::get,
            recover = { calls.incrementAndGet(); pending.set(false); SearchStartV3.ACCEPTED },
            beforeRelease = {
                if (finishes.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        recovery.request()
        assertEquals(1, queue.size)
        pending.set(false)
        val old = queue.removeAt(0)
        val worker = thread { old() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        pending.set(true)
        recovery.request()
        assertTrue(queue.isEmpty())
        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(1, queue.size)
        queue.removeAt(0)()
        assertEquals(1, calls.get())
        assertTrue(queue.isEmpty())
    }
}
