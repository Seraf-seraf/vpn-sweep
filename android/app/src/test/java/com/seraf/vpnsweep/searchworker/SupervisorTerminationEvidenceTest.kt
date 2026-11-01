package com.seraf.vpnsweep.searchworker

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SupervisorTerminationEvidenceTest {
    @Test fun requiresLinkUnbindAndDeathOfSameAttemptInAnyOrder(): Unit {
        for (deathBeforeLinkReturns in listOf(false, true)) {
            val deliveries = AtomicInteger()
            val evidence = SupervisorTerminationEvidence(Any()) { deliveries.incrementAndGet() }
            assertTrue(evidence.beginLink())
            if (deathBeforeLinkReturns) evidence.binderDied()
            evidence.linkResult(true)
            if (!deathBeforeLinkReturns) evidence.binderDied()
            assertEquals(0, deliveries.get())
            evidence.unbindResult(true)
            assertEquals(1, deliveries.get())
            evidence.binderDied()
            assertEquals(1, deliveries.get())
        }
    }

    @Test fun unknownOrUnlinkedAttemptNeverConfirms(): Unit {
        for (fault in listOf("no-link", "link-failed", "unbind-failed", "not-dead", "rejected")) {
            val deliveries = AtomicInteger()
            val evidence = SupervisorTerminationEvidence(Any()) { deliveries.incrementAndGet() }
            if (fault != "no-link") assertTrue(evidence.beginLink())
            if (fault == "rejected") evidence.reject()
            evidence.linkResult(fault != "link-failed")
            evidence.unbindResult(fault != "unbind-failed")
            if (fault != "not-dead") evidence.binderDied()
            assertEquals(fault, 0, deliveries.get())
        }
    }

    @Test fun bindingDiedIsNotDeathEvidenceAndCannotInvalidateLinkedDeath(): Unit {
        for (bindingEventFirst in listOf(false, true)) {
            val deliveries = AtomicInteger()
            val evidence = SupervisorTerminationEvidence(Any()) { deliveries.incrementAndGet() }
            assertTrue(evidence.beginLink())
            evidence.linkResult(true)
            if (bindingEventFirst) evidence.bindingDied()
            evidence.unbindResult(true)
            assertEquals(0, deliveries.get())
            if (!bindingEventFirst) evidence.bindingDied()
            evidence.binderDied()
            assertEquals(1, deliveries.get())
        }
    }
}
