package com.seraf.vpnsweep.subscriptionruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionDisplayTextTest {
    @Test
    fun readsLowercaseJsonFieldsAndFallsBackToNonemptyTitle(): Unit {
        val fields = mapOf("title" to "Zürich", "subtitle" to "VLESS", "Title" to "wrong")
        val requested = mutableListOf<String>()
        val display = readSubscriptionDisplayText(3) { key ->
            requested += key
            fields[key].orEmpty()
        }

        assertEquals("Zürich", display.title)
        assertEquals("VLESS", display.subtitle)
        assertEquals(listOf("title", "subtitle"), requested)

        val uppercaseFields = mapOf("Title" to "wrong", "Subtitle" to "wrong")
        val missing = readSubscriptionDisplayText(4) { key -> uppercaseFields[key].orEmpty() }
        assertEquals("Сервер 4", missing.title)
    }
}
