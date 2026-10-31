package com.seraf.vpnsweep.searchnetwork

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchworker.RoundOwner
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectedNetworkDnsTest {
    private val selected = SearchNetworkV1(identity = 7, version = 3)
    private val packet = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    private val nxdomain = byteArrayOf(0, 1, 0x81.toByte(), 0x83.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    @Test
    fun preservesNegativeDnsReplyAndUsesSelectedNetwork(): Unit = withExecutor { executor ->
        val fixture = Fixture()
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 2000) }
        assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
        assertEquals(7L, fixture.port.dispatchedNetwork)
        assertArrayEquals(packet, fixture.port.dispatchedPacket)
        fixture.port.answer(nxdomain)
        assertArrayEquals(nxdomain, result.get(1, TimeUnit.SECONDS))
        assertEquals(0, fixture.revocations.get())
        fixture.dns.close()
    }

    @Test
    fun unknownChangedVpnAndLostOwnerFailBeforeDispatch(): Unit {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.port.active = null },
            { it.port.active = 8 },
            { it.port.notVpn = false },
            { it.port.failCapabilities = true },
            { it.currentIdentity.set(selected.copy(version = 4)) },
            { it.ownerCurrent.set(false) },
            { it.ownerFailure.set(true) },
        )
        for (mutate in mutations) {
            val fixture = Fixture()
            mutate(fixture)
            expectIOException { fixture.dns.queryDns(packet, 2000) }
            assertEquals(0, fixture.port.starts.get())
            assertTrue(fixture.revoked.await(1, TimeUnit.SECONDS))
            assertEquals(1, fixture.revocations.get())
            expectIOException { fixture.dns.queryDns(packet, 2000) }
            assertEquals(0, fixture.port.starts.get())
        }
    }

    @Test
    fun changedDefaultEpochVpnOrOwnerRejectsAnInFlightAnswer(): Unit = withExecutor { executor ->
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.port.active = null },
            { it.port.active = 8 },
            { it.port.notVpn = false },
            { it.port.failCapabilities = true },
            { it.currentIdentity.set(selected.copy(version = 4)) },
            { it.ownerCurrent.set(false) },
            { it.ownerFailure.set(true) },
        )
        for (mutate in mutations) {
            val fixture = Fixture()
            val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 2000) }
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            mutate(fixture)
            fixture.port.answer(nxdomain)
            expectFutureIOException(result)
            assertTrue(fixture.revoked.await(1, TimeUnit.SECONDS))
            assertEquals(1, fixture.revocations.get())
            assertEquals(1, fixture.port.starts.get())
        }
    }

    @Test
    fun deadlineReleasesWaiterAndIgnoresLateCallbackWhenStartIsBlocked(): Unit = withExecutor { executor ->
        val fixture = Fixture()
        fixture.port.blockStart = true
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 100) }
        try {
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            assertTrue(expectFutureIOException(result) is SocketTimeoutException)
            assertTrue(fixture.port.cancelled.await(1, TimeUnit.SECONDS))
            fixture.port.active = 8
            fixture.port.answer(nxdomain)
            assertEquals(0, fixture.revocations.get())
            assertEquals(1, fixture.port.starts.get())
        } finally {
            fixture.port.releaseStart.countDown()
            fixture.dns.close()
        }
    }

    @Test
    fun revokeDoesNotWaitForBlockedStartOrCancellation(): Unit = withExecutor { executor ->
        val fixture = Fixture()
        fixture.port.blockStart = true
        fixture.port.blockCancel = true
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 5000) }
        try {
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            val revoked = executor.submit { fixture.dns.revoke() }
            revoked.get(1, TimeUnit.SECONDS)
            expectFutureIOException(result)
            assertTrue(fixture.port.cancelled.await(1, TimeUnit.SECONDS))
            fixture.port.answer(nxdomain)
            expectIOException { fixture.dns.queryDns(packet, 1000) }
            assertEquals(1, fixture.port.starts.get())
            assertTrue(fixture.revoked.await(1, TimeUnit.SECONDS))
            assertEquals(1, fixture.revocations.get())
        } finally {
            fixture.port.releaseStart.countDown()
            fixture.port.releaseCancel.countDown()
        }
    }

    @Test
    fun revokeDoesNotWaitForBlockedNotification(): Unit = withExecutor { executor ->
        val notificationEntered = CountDownLatch(1)
        val releaseNotification = CountDownLatch(1)
        val fixture = Fixture(onRevoke = {
            notificationEntered.countDown()
            releaseNotification.await()
        })
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 5000) }
        try {
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            executor.submit { fixture.dns.revoke() }.get(1, TimeUnit.SECONDS)
            assertTrue(notificationEntered.await(1, TimeUnit.SECONDS))
            expectFutureIOException(result)
            assertTrue(fixture.port.cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            releaseNotification.countDown()
        }
    }

    @Test
    fun closeWakesWaiterAndSuppressesLateAnswer(): Unit = withExecutor { executor ->
        val fixture = Fixture()
        fixture.port.blockStart = true
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 5000) }
        try {
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            executor.submit { fixture.dns.close() }.get(1, TimeUnit.SECONDS)
            expectFutureIOException(result)
            assertTrue(fixture.port.cancelled.await(1, TimeUnit.SECONDS))
            fixture.port.answer(nxdomain)
            expectIOException { fixture.dns.queryDns(packet, 1000) }
            assertEquals(0, fixture.revocations.get())
            assertEquals(1, fixture.port.starts.get())
        } finally {
            fixture.port.releaseStart.countDown()
        }
    }

    @Test
    fun revokeDoesNotWaitForBlockedOwnerCheck(): Unit = withExecutor { executor ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture(RoundOwner {
            entered.countDown()
            release.await()
            true
        })
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 5000) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            executor.submit { fixture.dns.revoke() }.get(1, TimeUnit.SECONDS)
            expectFutureIOException(result)
            assertEquals(0, fixture.port.starts.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun packetAndReplyBoundsAndNonPositiveBudgetAreEnforced(): Unit = withExecutor { executor ->
        val invalid = Fixture()
        expectIOException { invalid.dns.queryDns(byteArrayOf(), 1000) }
        expectIOException { invalid.dns.queryDns(ByteArray(65536), 1000) }
        assertTrue(expectIOException { invalid.dns.queryDns(packet, 0) } is SocketTimeoutException)
        assertTrue(expectIOException { invalid.dns.queryDns(packet, -1) } is SocketTimeoutException)
        assertEquals(0, invalid.port.starts.get())
        invalid.dns.close()

        for (size in listOf(1, 65535)) {
            val fixture = Fixture()
            val input = ByteArray(size) { 1 }
            val answer = ByteArray(size) { 2 }
            val result = executor.submit<ByteArray> { fixture.dns.queryDns(input, 2000) }
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            assertEquals(size, fixture.port.dispatchedPacket?.size)
            fixture.port.answer(answer)
            assertArrayEquals(answer, result.get(1, TimeUnit.SECONDS))
            fixture.dns.close()
        }

        for (answer in listOf(byteArrayOf(), ByteArray(65536))) {
            val fixture = Fixture()
            val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 2000) }
            assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
            fixture.port.answer(answer)
            expectFutureIOException(result)
            assertTrue(fixture.revoked.await(1, TimeUnit.SECONDS))
            assertEquals(1, fixture.revocations.get())
        }
    }

    @Test
    fun platformErrorRevokesWithoutFallback(): Unit = withExecutor { executor ->
        val fixture = Fixture()
        val result = executor.submit<ByteArray> { fixture.dns.queryDns(packet, 2000) }
        assertTrue(fixture.port.started.await(1, TimeUnit.SECONDS))
        fixture.port.answer(null)
        expectFutureIOException(result)
        assertTrue(fixture.revoked.await(1, TimeUnit.SECONDS))
        assertEquals(1, fixture.revocations.get())
        assertEquals(1, fixture.port.starts.get())
        expectIOException { fixture.dns.queryDns(packet, 1000) }
        assertEquals(1, fixture.port.starts.get())
    }

    private inner class Fixture(ownerOverride: RoundOwner? = null, onRevoke: (() -> Unit)? = null) {
        val currentIdentity = AtomicReference(selected)
        val ownerCurrent = AtomicBoolean(true)
        val ownerFailure = AtomicBoolean(false)
        val revocations = AtomicInteger()
        val revoked = CountDownLatch(1)
        val port = FakeNetwork()
        val dns = SelectedNetworkDns(
            selected,
            ownerOverride ?: RoundOwner {
                if (ownerFailure.get()) error("synthetic owner failure")
                it == currentIdentity.get() && ownerCurrent.get()
            },
            port,
        ) {
            revocations.incrementAndGet()
            revoked.countDown()
            onRevoke?.invoke()
        }
    }

    private class FakeNetwork : DnsNetwork<Long> {
        @Volatile var active: Long? = 7
        @Volatile var notVpn = true
        @Volatile var failCapabilities = false
        @Volatile var blockStart = false
        @Volatile var blockCancel = false
        @Volatile var dispatchedNetwork: Long? = null
        @Volatile var dispatchedPacket: ByteArray? = null
        val starts = AtomicInteger()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        private val callback = AtomicReference<((ByteArray?) -> Unit)?>(null)

        override fun activeNetwork(): Long? = active
        override fun identity(network: Long): Long = network
        override fun isNotVpn(network: Long): Boolean = !failCapabilities && notVpn
        override fun newRequest(): DnsRequest<Long> = object : DnsRequest<Long> {
            override fun start(network: Long, packet: ByteArray, callback: (ByteArray?) -> Unit): Unit {
                dispatchedNetwork = network
                dispatchedPacket = packet
                this@FakeNetwork.callback.set(callback)
                starts.incrementAndGet()
                started.countDown()
                if (blockStart) releaseStart.await()
            }

            override fun cancel(): Unit {
                cancelled.countDown()
                if (blockCancel) releaseCancel.await()
            }
        }

        fun answer(answer: ByteArray?): Unit {
            checkNotNull(callback.get()).invoke(answer)
        }
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
            fail("expected IOException")
        } catch (exception: IOException) {
            return exception
        }
        throw AssertionError("unreachable")
    }

    private fun expectFutureIOException(future: Future<ByteArray>): IOException {
        try {
            future.get(2, TimeUnit.SECONDS)
            fail("expected IOException")
        } catch (exception: ExecutionException) {
            assertTrue(exception.cause is IOException)
            return exception.cause as IOException
        }
        throw AssertionError("unreachable")
    }

    private fun withExecutor(block: (java.util.concurrent.ExecutorService) -> Unit): Unit {
        val executor = Executors.newCachedThreadPool()
        try {
            block(executor)
        } finally {
            executor.shutdownNow()
        }
    }
}
