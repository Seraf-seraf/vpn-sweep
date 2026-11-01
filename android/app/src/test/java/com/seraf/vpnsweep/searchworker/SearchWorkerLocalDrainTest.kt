package com.seraf.vpnsweep.searchworker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchWorkerLocalDrainTest {
    @Test fun sealingRejectsNewOperationsButWaitsForAResourceTransferredByAnExistingOperation(): Unit {
        val drain = SearchWorkerLocalDrain(7)
        val operation = drain.begin(7)!!
        val delivered = CountDownLatch(1)
        val proof = AtomicReference<SearchWorkerLocalDrain.Proof?>()
        assertTrue(drain.seal(7) { proof.set(it); delivered.countDown() })
        assertNull(drain.begin(7))
        val reply = operation.retain()!!
        operation.closed(true)
        assertTrue(delivered.count == 1L)
        reply.closed(true)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertNotNull(proof.get())
        assertTrue(proof.get()!!.belongsTo(drain, 7))
        assertFalse(proof.get()!!.belongsTo(drain, 8))
    }

    @Test fun unknownResourceDispositionCannotBeRepairedByASecondSignal(): Unit {
        val drain = SearchWorkerLocalDrain(7)
        val operation = drain.begin(7)!!
        val reply = operation.retain()!!
        val delivered = CountDownLatch(1)
        val proof = AtomicReference<SearchWorkerLocalDrain.Proof?>()
        assertTrue(drain.seal(7) { proof.set(it); delivered.countDown() })
        reply.closed(false)
        reply.closed(true)
        operation.closed(true)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertNull(proof.get())
        assertNull(drain.begin(7))
    }

    @Test fun staleGenerationCannotSealOrProduceEvidenceForTheCurrentJob(): Unit {
        val current = SearchWorkerLocalDrain(8)
        val old = SearchWorkerLocalDrain(7)
        val operation = current.begin(8)!!
        assertNull(current.begin(7))
        assertFalse(current.seal(7) { error("stale callback") })
        var received: SearchWorkerLocalDrain.Proof? = null
        assertTrue(current.seal(8) { received = it })
        assertNull(received)
        operation.closed(true)
        val proof = received!!
        assertTrue(proof.belongsTo(current, 8))
        assertFalse(SearchWorkerLocalDrain.Proof(current).belongsTo(current, 8))
        assertFalse(proof.belongsTo(old, 8))
        assertFalse(proof.belongsTo(current, 7))
        assertNull(operation.retain())
        assertSame(proof, received)
    }

    @Test fun unknownEvidenceDoesNotBlockOrdinaryOperationsBeforeSeal(): Unit {
        val drain = SearchWorkerLocalDrain(9)
        drain.begin(9)!!.closed(false)
        val next = drain.begin(9)
        assertNotNull(next)
        val child = next!!.retain()
        assertNotNull(child)
        next.closed(true)
        child!!.closed(true)
        var delivered = false
        var proof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(9) { delivered = true; proof = it })
        assertTrue(delivered)
        assertNull(proof)
    }

    @Test fun actualResourceScopeWaitsForLateCloseAndRejectsCloseFailure(): Unit {
        for (closeSucceeds in listOf(true, false)) {
            val drain = SearchWorkerLocalDrain(12)
            val operation = drain.begin(12)!!
            val closing = CountDownLatch(1)
            val finishClose = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val delivered = CountDownLatch(1)
            val proof = AtomicReference<SearchWorkerLocalDrain.Proof?>()
            val worker = Thread {
                try {
                    trackedWorkerResource(operation, { Any() }, {
                        closing.countDown()
                        finishClose.await()
                        if (!closeSucceeds) throw IllegalStateException("close_unknown")
                    }) { Unit }
                } catch (_: Exception) { } finally { finished.countDown() }
            }
            worker.start()
            try {
                assertTrue(closing.await(2, TimeUnit.SECONDS))
                assertTrue(drain.seal(12) { proof.set(it); delivered.countDown() })
                assertTrue(delivered.count == 1L)
                finishClose.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertTrue(delivered.await(2, TimeUnit.SECONDS))
                if (closeSucceeds) assertNotNull(proof.get()) else assertNull(proof.get())
            } finally {
                finishClose.countDown()
                worker.join(2_000)
            }
        }
    }

    @Test fun cancelledLaunchCannotRunAfterItsTicketWasReturned(): Unit {
        val drain = SearchWorkerLocalDrain(13)
        val dispatch = SearchWorkerDispatch(drain.begin(13)!!)
        dispatch.cancelBeforeClaim()
        var proof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(13) { proof = it })
        assertNotNull(proof)
        assertFalse(dispatch.claim())
    }

    @Test fun launchFailureRacingAClaimWaitsForTheActualOperation(): Unit {
        val drain = SearchWorkerLocalDrain(14)
        val ticket = drain.begin(14)!!
        val dispatch = SearchWorkerDispatch(ticket)
        assertTrue(dispatch.claim())
        dispatch.cancelBeforeClaim()
        var proof: SearchWorkerLocalDrain.Proof? = null
        assertTrue(drain.seal(14) { proof = it })
        assertNull(proof)
        ticket.closed(true)
        assertNotNull(proof)
        assertFalse(dispatch.claim())
    }

    @Test fun onlyAnExplicitPreAcquireFailureProvesThereWasNoResource(): Unit {
        for (knownEmpty in listOf(true, false)) {
            val drain = SearchWorkerLocalDrain(15)
            val ticket = drain.begin(15)!!
            try {
                trackedWorkerResource<Any, Unit>(ticket, {
                    if (knownEmpty) throw EmptyBeforeAcquire() else throw IllegalStateException("acquire_unknown")
                }, { error("no resource") }, { it is EmptyBeforeAcquire }) { error("no action") }
            } catch (_: Exception) { }
            var callback = false
            var proof: SearchWorkerLocalDrain.Proof? = null
            assertTrue(drain.seal(15) { callback = true; proof = it })
            assertTrue(callback)
            if (knownEmpty) assertNotNull(proof) else assertNull(proof)
        }
    }

    private class EmptyBeforeAcquire : Exception()
}
