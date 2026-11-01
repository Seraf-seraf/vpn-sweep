package com.seraf.vpnsweep.searchworker

import org.junit.Assert.*
import org.junit.Test

class SupervisorBindingAttemptTest {
    @Test fun timeoutOrBindingDiedBeforeConnectionUnbindsAndStaleCallbackCannotProveNextAttempt(): Unit {
        for (timedOut in listOf(false, true)) {
            val tasks = ArrayDeque<() -> Unit>()
            var retries = 0
            var confirmations = 0
            var unbinds = 0
            var death: (() -> Unit)? = null
            fun attempt() = SupervisorBindingAttempt(
                dispatch = { tasks.addLast(it); true },
                link = { _, died -> death = died; true },
                unbind = { unbinds++; true },
                confirmed = { confirmations++ }, retry = { retries++ },
            )
            val old = attempt()
            old.bindReturned(true)
            if (timedOut) old.connectionTimedOut() else old.bindingDied()
            assertEquals(1, tasks.size)
            old.connected(Any())
            tasks.removeFirst()()
            assertEquals(1, unbinds)
            assertEquals(1, retries)
            assertEquals(0, confirmations)
            val next = attempt()
            next.connected(Any())
            next.bindReturned(true)
            assertEquals(1, tasks.size)
            tasks.removeFirst()()
            assertEquals(0, confirmations)
            old.connected(Any())
            death!!.invoke()
            assertEquals(1, confirmations)
            assertEquals(2, unbinds)
        }
    }

    @Test fun unbindFailureOrAbsentDeathNeverConfirmsOrRetries(): Unit {
        var retries = 0
        var confirmations = 0
        var death: (() -> Unit)? = null
        val attempt = SupervisorBindingAttempt(
            dispatch = { it(); true },
            link = { _, died -> death = died; true },
            unbind = { false },
            confirmed = { confirmations++ }, retry = { retries++ },
        )
        attempt.bindReturned(true)
        attempt.connected(Any())
        death!!.invoke()
        assertEquals(0, confirmations)
        assertEquals(0, retries)
    }

    @Test fun bindingDiedDuringLinkedAttemptStillWaitsForExactDeath(): Unit {
        val tasks = ArrayDeque<() -> Unit>()
        var confirmations = 0
        var death: (() -> Unit)? = null
        val attempt = SupervisorBindingAttempt(
            dispatch = { tasks.addLast(it); true },
            link = { _, died -> death = died; true },
            unbind = { true },
            confirmed = { confirmations++ }, retry = { fail("unexpected retry") },
        )
        attempt.bindReturned(true)
        attempt.connected(Any())
        attempt.bindingDied()
        tasks.removeFirst()()
        assertEquals(0, confirmations)
        attempt.bindingDied()
        death!!.invoke()
        assertEquals(1, confirmations)
    }

    @Test fun duplicateConnectionCannotSupplyProofForAnotherAttempt(): Unit {
        val tasks = ArrayDeque<() -> Unit>()
        var confirmations = 0
        var retries = 0
        var death: (() -> Unit)? = null
        val attempt = SupervisorBindingAttempt(
            dispatch = { tasks.addLast(it); true },
            link = { _, died -> death = died; true },
            unbind = { true },
            confirmed = { confirmations++ }, retry = { retries++ },
        )
        attempt.bindReturned(true)
        attempt.connected(Any())
        attempt.connected(Any())
        tasks.removeFirst()()
        death!!.invoke()
        assertEquals(0, confirmations)
        assertEquals(1, retries)
    }

    @Test fun contradictoryConnectionAndFalseBindDoNotRetryOrConfirm(): Unit {
        var retries = 0
        var failures = 0
        var confirmations = 0
        val attempt = SupervisorBindingAttempt(
            dispatch = { it(); true }, link = { _, _ -> true }, unbind = { true },
            confirmed = { confirmations++ }, retry = { retries++ }, failed = { failures++ },
        )
        attempt.connected(Any())
        attempt.bindReturned(false)
        assertEquals(0, retries)
        assertEquals(0, confirmations)
        assertEquals(1, failures)
    }
}
