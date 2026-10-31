package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NetworkEpochOwnerTest {
    private class Source {
        var listener: ((DefaultNetworkEvent) -> Unit)? = null
        var snapshot: DefaultNetworkSnapshot? = DefaultNetworkSnapshot(11, true)
        var registered = false
        var closes = 0
        var closeFails = false
        var snapshots = 0
        var onSnapshot: (() -> Unit)? = null

        fun register(callback: (DefaultNetworkEvent) -> Unit): AutoCloseable {
            registered = true
            listener = callback
            return AutoCloseable {
                closes++
                if (closeFails) throw IOException("synthetic")
            }
        }

        fun read(): DefaultNetworkSnapshot? {
            check(registered)
            snapshots++
            onSnapshot?.invoke()
            return snapshot
        }

        fun event(event: DefaultNetworkEvent): Unit { listener!!(event) }
    }

    @Test
    fun registersBeforeSnapshotAndChecksRealSnapshotOutsideCachedOwner(): Unit {
        val source = Source()
        var leaseChecks = 0
        val opened = NetworkEpochOwner.open(source::register, source::read, { leaseChecks++; true }, {}) as NetworkOwnerOpen.Ready
        assertEquals(2, leaseChecks)
        assertEquals(1, source.snapshots)
        assertTrue(opened.owner.isCurrent(opened.network))
        assertEquals(1, source.snapshots)
        assertTrue(opened.owner.verifyOnCallback(opened.network))
        assertEquals(2, source.snapshots)
        assertEquals(4, leaseChecks)
        assertFalse(opened.owner.verifyOnCallback(opened.network.copy(version = opened.network.version + 1)))
        assertTrue(opened.owner.close())
        assertFalse(opened.owner.isCurrent(opened.network))
        assertTrue(opened.owner.close())
        assertEquals(1, source.closes)
    }

    @Test
    fun networkChangeThenReturnDoesNotReviveEpoch(): Unit {
        val first = Source()
        val a = NetworkEpochOwner.open(first::register, first::read, { true }, {}) as NetworkOwnerOpen.Ready
        first.event(DefaultNetworkEvent.Available(22))
        assertFalse(a.owner.isCurrent(a.network))
        first.event(DefaultNetworkEvent.Available(11))
        assertFalse(a.owner.isCurrent(a.network))
        val second = Source().apply { snapshot = DefaultNetworkSnapshot(22, true) }
        val b = NetworkEpochOwner.open(second::register, second::read, { true }, {}) as NetworkOwnerOpen.Ready
        val third = Source()
        val anotherA = NetworkEpochOwner.open(third::register, third::read, { true }, {}) as NetworkOwnerOpen.Ready
        assertTrue(a.network.version < b.network.version && b.network.version < anotherA.network.version)
        assertEquals(a.network.identity, anotherA.network.identity)
        assertFalse(a.owner.isCurrent(anotherA.network))
        assertTrue(a.owner.close())
        assertTrue(b.owner.close())
        assertTrue(anotherA.owner.close())
    }

    @Test
    fun liveSnapshotAndLeaseChangesRevokeWithoutFallback(): Unit {
        val source = Source()
        val stopped = CountDownLatch(1)
        var lease = true
        val opened = NetworkEpochOwner.open(source::register, source::read, { lease }, { stopped.countDown() }) as NetworkOwnerOpen.Ready
        source.snapshot = DefaultNetworkSnapshot(11, false)
        assertFalse(opened.owner.verifyOnCallback(opened.network))
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        source.snapshot = DefaultNetworkSnapshot(11, true)
        lease = true
        assertFalse(opened.owner.verifyOnCallback(opened.network))
        assertTrue(opened.owner.close())

        val leaseSource = Source()
        val leaseOwner = NetworkEpochOwner.open(leaseSource::register, leaseSource::read, { lease }, {}) as NetworkOwnerOpen.Ready
        lease = false
        assertFalse(leaseOwner.owner.verifyOnCallback(leaseOwner.network))
        assertFalse(leaseOwner.owner.isCurrent(leaseOwner.network))
        assertTrue(leaseOwner.owner.close())
    }

    @Test
    fun sameNetworkEventsAreAcceptedButVpnLossAndBlockingRevoke(): Unit {
        val source = Source()
        val opened = NetworkEpochOwner.open(source::register, source::read, { true }, {}) as NetworkOwnerOpen.Ready
        source.event(DefaultNetworkEvent.Available(11))
        source.event(DefaultNetworkEvent.Capabilities(11, true))
        source.event(DefaultNetworkEvent.Blocked(11, false))
        assertTrue(opened.owner.isCurrent(opened.network))
        source.event(DefaultNetworkEvent.Capabilities(11, false))
        assertFalse(opened.owner.isCurrent(opened.network))
        assertTrue(opened.owner.close())

        val blocked = Source()
        val other = NetworkEpochOwner.open(blocked::register, blocked::read, { true }, {}) as NetworkOwnerOpen.Ready
        blocked.event(DefaultNetworkEvent.Blocked(11, true))
        assertFalse(other.owner.isCurrent(other.network))
        assertTrue(other.owner.close())
    }

    @Test
    fun unknownOrChangedDuringInitialSnapshotNeverAdmits(): Unit {
        val changed = Source()
        changed.onSnapshot = { changed.event(DefaultNetworkEvent.Available(22)) }
        assertEquals(NetworkOwnerOpen.Unavailable, NetworkEpochOwner.open(changed::register, changed::read, { true }, {}))
        assertEquals(1, changed.closes)
        val vpn = Source().apply { snapshot = DefaultNetworkSnapshot(11, false) }
        assertEquals(NetworkOwnerOpen.Unavailable, NetworkEpochOwner.open(vpn::register, vpn::read, { true }, {}))
        assertEquals(1, vpn.closes)
        val blocked = Source()
        val open = NetworkEpochOwner.open({ callback ->
            blocked.register(callback).also { callback(DefaultNetworkEvent.Blocked(11, true)) }
        }, blocked::read, { true }, {})
        assertEquals(NetworkOwnerOpen.Unavailable, open)
        assertEquals(1, blocked.closes)
        val lost = Source()
        val lostOpen = NetworkEpochOwner.open({ callback ->
            lost.register(callback).also { callback(DefaultNetworkEvent.Lost) }
        }, lost::read, { true }, {})
        assertEquals(NetworkOwnerOpen.Unavailable, lostOpen)
        assertEquals(1, lost.closes)
        val mismatched = Source()
        val mismatchOpen = NetworkEpochOwner.open({ callback ->
            mismatched.register(callback).also { callback(DefaultNetworkEvent.Available(22)) }
        }, mismatched::read, { true }, {})
        assertEquals(NetworkOwnerOpen.Unavailable, mismatchOpen)
        assertEquals(1, mismatched.closes)
    }

    @Test
    fun registrationAndUnregisterFailuresAreUnknown(): Unit {
        assertEquals(NetworkOwnerOpen.Unknown, NetworkEpochOwner.open({ throw IOException("synthetic") }, { null }, { true }, {}))
        val source = Source().apply { snapshot = null; closeFails = true }
        assertEquals(NetworkOwnerOpen.Unknown, NetworkEpochOwner.open(source::register, source::read, { true }, {}))
        assertEquals(1, source.closes)
        val acquired = Source().apply { closeFails = true }
        val opened = NetworkEpochOwner.open(acquired::register, acquired::read, { true }, {}) as NetworkOwnerOpen.Ready
        assertFalse(opened.owner.close())
        assertFalse(opened.owner.close())
        assertFalse(opened.owner.isCurrent(opened.network))
        assertEquals(1, acquired.closes)
    }

    @Test
    fun revokeIsOneShotAndDoesNotWaitForBlockingTeardown(): Unit {
        val source = Source()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val opened = NetworkEpochOwner.open(source::register, source::read, { true }, {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }) as NetworkOwnerOpen.Ready
        opened.owner.ownerLost()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(NetworkRevocation.PENDING, opened.owner.revocationStatus())
        opened.owner.ownerLost()
        source.event(DefaultNetworkEvent.Lost)
        assertFalse(opened.owner.isCurrent(opened.network))
        release.countDown()
        assertEquals(1, calls.get())
        waitForRevocation(opened.owner, NetworkRevocation.RETURNED)
        assertTrue(opened.owner.close())
    }

    @Test
    fun failedTeardownNeverBecomesConfirmed(): Unit {
        val source = Source()
        val opened = NetworkEpochOwner.open(source::register, source::read, { true }, {
            throw IOException("synthetic")
        }) as NetworkOwnerOpen.Ready
        opened.owner.ownerLost()
        waitForRevocation(opened.owner, NetworkRevocation.UNKNOWN)
        assertFalse(opened.owner.isCurrent(opened.network))
        assertTrue(opened.owner.close())
    }

    @Test
    fun eventDuringBlockingCurrentCheckPreventsStaleSuccess(): Unit {
        val source = Source()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val opened = NetworkEpochOwner.open(source::register, source::read, { true }, {}) as NetworkOwnerOpen.Ready
        source.onSnapshot = {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }
        val worker = Thread { result.set(opened.owner.verifyOnCallback(opened.network)) }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        source.event(DefaultNetworkEvent.Available(22))
        assertFalse(opened.owner.isCurrent(opened.network))
        release.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(false, result.get())
        assertTrue(opened.owner.close())
    }

    private fun waitForRevocation(owner: NetworkEpochOwner, expected: NetworkRevocation): Unit {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (owner.revocationStatus() == NetworkRevocation.PENDING && System.nanoTime() < limit) Thread.yield()
        assertEquals(expected, owner.revocationStatus())
    }
}
