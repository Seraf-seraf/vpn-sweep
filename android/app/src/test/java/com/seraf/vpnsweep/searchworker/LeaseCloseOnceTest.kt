package com.seraf.vpnsweep.searchworker

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

internal class LeaseCloseOnceTest {
    @Test
    fun concurrentCallersCloseOneOwnerOnlyOnce(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val firstError = AtomicReference<Throwable>()
        val secondError = AtomicReference<Throwable>()
        val close = LeaseCloseOnce {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val first = Thread { try { close.close() } catch (t: Throwable) { firstError.set(t) } }
        val second = Thread {
            secondStarted.countDown()
            try { close.close() } catch (t: Throwable) { secondError.set(t) }
        }
        first.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            release.countDown()
            first.join(3000)
            second.join(3000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertNull(firstError.get())
            assertNull(secondError.get())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            first.join(3000)
            if (second.state != Thread.State.NEW) second.join(3000)
        }
    }

    @Test
    fun unknownCloseIsStickyAndIsNeverRetried(): Unit {
        val calls = AtomicInteger()
        val close = LeaseCloseOnce { calls.incrementAndGet(); throw IOException("synthetic") }
        repeat(2) {
            try { close.close(); fail("expected unknown close") } catch (_: IOException) { }
        }
        assertEquals(1, calls.get())
    }
}
