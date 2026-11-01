package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.isValidSubscriptionUrl
import com.seraf.vpnsweep.ui.updateVisibleSelection
import com.seraf.vpnsweep.ui.subscriptionInputStatus
import com.seraf.vpnsweep.ui.subscriptionCanSubmit
import com.seraf.vpnsweep.ui.SubscriptionDialogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test
    fun subscriptionUrlRequiresHttpsAndSafeAuthority(): Unit {
        assertTrue(isValidSubscriptionUrl("https://example.com/subscription"))
        assertFalse(isValidSubscriptionUrl("http://[::1]:65535/path"))
        assertFalse(isValidSubscriptionUrl("https://user@example.com/path"))
        assertFalse(isValidSubscriptionUrl("https://example.com/path#fragment"))
        assertFalse(isValidSubscriptionUrl("https://example.com/path#"))
        assertFalse(isValidSubscriptionUrl("https://example.com:/path"))
        assertFalse(isValidSubscriptionUrl("https://example.com:0/path"))
        assertTrue(isValidSubscriptionUrl("https://example.com:65535/path"))
        assertFalse(isValidSubscriptionUrl(""))
        assertFalse(isValidSubscriptionUrl("example.com/subscription"))
        assertFalse(isValidSubscriptionUrl("ftp://example.com/subscription"))
        assertFalse(isValidSubscriptionUrl("https://example.com:65536/path"))
        assertFalse(isValidSubscriptionUrl("https://example.com/a b"))
    }

    @Test
    fun submitFailureIsVisibleAndValidInputCanRetryOrEdit(): Unit {
        val valid = "https://example.com/subscription"
        assertEquals(SubscriptionDialogStatus.Input, subscriptionInputStatus(valid, false))
        val failed = subscriptionInputStatus(valid, true)
        assertEquals(SubscriptionDialogStatus.Unavailable, failed)
        assertTrue(subscriptionCanSubmit(valid, failed))
        assertEquals(SubscriptionDialogStatus.InvalidUrl, subscriptionInputStatus("invalid", false))
        assertFalse(subscriptionCanSubmit("invalid", SubscriptionDialogStatus.InvalidUrl))
        assertEquals(SubscriptionDialogStatus.Input, subscriptionInputStatus(valid, false))
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
