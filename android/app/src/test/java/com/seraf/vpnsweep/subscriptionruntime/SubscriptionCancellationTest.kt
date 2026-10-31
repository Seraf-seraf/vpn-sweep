package com.seraf.vpnsweep.subscriptionruntime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCancellationTest {
    @Test
    fun cancellationBeforeInstallationReachesBothResources(): Unit {
        val cancellation = SubscriptionCancellation()
        var request = 0
        var route = 0
        cancellation.cancel()
        cancellation.installRoute { route++ }
        cancellation.installRequest { request++ }
        assertEquals(1, request)
        assertEquals(1, route)
        assertTrue(cancellation.cancelled())
    }

    @Test
    fun revocationBeforeRequestInstallationCancelsRequest(): Unit {
        val cancellation = SubscriptionCancellation()
        var request = 0
        var route = 0
        cancellation.revoke()
        cancellation.installRoute { route++ }
        cancellation.installRequest { request++ }
        assertEquals(1, request)
        assertEquals(1, route)
        assertTrue(cancellation.revoked())
    }

    @Test
    fun failingRequestCancellationStillRevokesPendingRoute(): Unit {
        val cancellation = SubscriptionCancellation()
        var route = 0
        cancellation.installRequest { throw IllegalStateException("synthetic") }
        cancellation.installRoute { route++ }
        cancellation.cancel()
        assertEquals(1, route)
    }

    @Test
    fun racingInstallationAndCancellationCannotLoseCancellation(): Unit {
        val cancellation = SubscriptionCancellation()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val request = AtomicInteger()
        val route = AtomicInteger()
        val install = Thread {
            start.await()
            cancellation.installRoute { route.incrementAndGet() }
            cancellation.installRequest { request.incrementAndGet() }
            finished.countDown()
        }
        val cancel = Thread {
            start.await()
            cancellation.cancel()
            finished.countDown()
        }
        install.start()
        cancel.start()
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(request.get() >= 1)
        assertTrue(route.get() >= 1)
    }
}
