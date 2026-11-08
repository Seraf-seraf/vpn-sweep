package com.seraf.vpnsweep.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayLogWindowTest {
    @Test
    fun initialFailuresAreReportedAndPersistentFailuresAreNotHidden(): Unit {
        val window = XrayLogWindow(1_000L)
        assertTrue(window.starting(15_999L))
        assertFalse(window.starting(16_000L))
        assertTrue(window.shouldReport(2L, 1_000L))
        assertFalse(window.shouldReport(2L, 15_999L))
        assertTrue(window.shouldReport(2L, 16_000L))
        assertTrue(window.shouldReport(3L, 16_001L))
    }
}
