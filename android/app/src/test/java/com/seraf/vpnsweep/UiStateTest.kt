package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.isValidSubscriptionUrl
import com.seraf.vpnsweep.ui.updateVisibleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test
    fun subscriptionUrlRequiresHttpAndHost(): Unit {
        assertTrue(isValidSubscriptionUrl("https://example.com/subscription"))
        assertTrue(isValidSubscriptionUrl("http://[::1]:65535/path"))
        assertFalse(isValidSubscriptionUrl(""))
        assertFalse(isValidSubscriptionUrl("example.com/subscription"))
        assertFalse(isValidSubscriptionUrl("ftp://example.com/subscription"))
        assertFalse(isValidSubscriptionUrl("https://example.com:65536/path"))
        assertFalse(isValidSubscriptionUrl("https://example.com/a b"))
    }

    @Test
    fun selectAllAffectsOnlyVisibleSupportedServers(): Unit {
        val before = setOf("hidden", "visible-a")
        val visibleSupported = setOf("visible-a", "visible-b")

        assertEquals(
            setOf("hidden", "visible-a", "visible-b"),
            updateVisibleSelection(before, visibleSupported, select = true),
        )
        assertEquals(
            setOf("hidden"),
            updateVisibleSelection(before, visibleSupported, select = false),
        )
        assertEquals(before, updateVisibleSelection(before, emptySet(), select = true))
    }
}
