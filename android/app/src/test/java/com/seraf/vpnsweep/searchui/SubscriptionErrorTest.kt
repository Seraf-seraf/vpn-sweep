package com.seraf.vpnsweep.searchui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionErrorTest {
    @Test
    fun categoriesHaveDistinctSafeMessages(): Unit {
        val messages = SubscriptionError.entries.map(::subscriptionErrorText)
        assertEquals(SubscriptionError.entries.size, messages.toSet().size)
        for (message in messages) {
            assertFalse(message.contains("://"))
            assertFalse(message.contains("token", ignoreCase = true))
        }
    }
}
