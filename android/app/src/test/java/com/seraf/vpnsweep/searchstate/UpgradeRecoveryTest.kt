package com.seraf.vpnsweep.searchstate

import java.io.IOException
import com.seraf.vpnsweep.SearchReadiness
import com.seraf.vpnsweep.recheckSearchReadinessFlow
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import org.junit.Assert.*
import org.junit.Test

class UpgradeRecoveryTest {
    @Test fun ordinaryActiveOwnerBusyUsesExistingReadinessRetry(): Unit {
        val backend = Backend()
        backend.rawMarker.value = MarkerState.CLEAR
        val recovery = UpgradeRecovery(backend, eligibleAtStartup = false)
        backend.rawMarker.value = MarkerState.ACTIVE
        assertFalse(recovery.blocked())
        var calls = 0
        val result = recheckSearchReadinessFlow(
            ownerBusyRetry = true,
            recover = { calls++; SearchStartV3.ACCEPTED },
            selectionAvailable = { true },
            hasCommittedSelection = { true },
            searchEnabled = { true },
        )
        assertEquals(SearchReadiness.READY, result)
        assertEquals(1, calls)
    }
    @Test fun explicitArmAndLaterKernelBootPreserveSelectionBeforeUnblocking(): Unit {
        val backend = Backend()
        val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
        assertTrue(recovery.blocked())
        assertEquals(RecoveryStamp(FIRST_BOOT), backend.intent)
        backend.uptimeMillis = 7_200_000L
        backend.boot = RecoveryStamp(SECOND_BOOT)
        assertEquals(UpgradeRecoveryResult.RECOVERED, recovery.recover())
        assertEquals(listOf("acquire", "reconcile", "marker-clear", "marker-read", "owner-close", "intent-remove", "intent-read"), backend.steps.takeLast(7))
        assertFalse(recovery.blocked())
        assertEquals(setOf("chosen"), backend.selection)
    }

    @Test fun unchangedOrMalformedBootIdNeverAuthorizesRecovery(): Unit {
        for (stamp in listOf(RecoveryStamp(FIRST_BOOT), RecoveryStamp("invalid"), RecoveryStamp(""), RecoveryStamp("00000000-0000-0000-0000-000000000000"))) {
            val backend = Backend()
            val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
            assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
            backend.boot = stamp
            assertEquals(if (stamp.valid()) UpgradeRecoveryResult.REBOOT_REQUIRED else UpgradeRecoveryResult.UNKNOWN, recovery.recover())
            assertTrue(recovery.blocked())
            assertFalse(backend.steps.contains("acquire"))
        }
    }

    @Test fun invalidIntentAndUnexpectedClockOrMarkerStayBlocked(): Unit {
        for (fault in listOf("intent-unknown", "boot-id-unavailable", "raw-unknown")) {
            val backend = Backend().apply { this.fault = fault }
            val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
            assertEquals(UpgradeRecoveryResult.UNKNOWN, recovery.arm())
            assertTrue(recovery.blocked())
            assertFalse(backend.steps.contains("intent-write"))
        }
        assertEquals(UpgradeRecoveryResult.UNKNOWN, UpgradeRecovery(Backend(), eligibleAtStartup = false).arm())
    }

    @Test fun physicalClearCannotBypassIntentOrUnknown(): Unit {
        val backend = Backend()
        val marker = RecoveryFencedMarker(backend.rawMarker, backend)
        backend.rawMarker.value = MarkerState.CLEAR
        backend.intent = RecoveryStamp(FIRST_BOOT)
        assertEquals(MarkerState.ACTIVE, marker.read())
        assertFalse(marker.markActive())
        assertFalse(marker.markClear())
        backend.fault = "intent-unknown"
        assertEquals(MarkerState.UNKNOWN, marker.read())
        assertFalse(marker.markActive())
        assertFalse(marker.markClear())
    }

    @Test fun eachUnconfirmedStepKeepsAdmissionBlocked(): Unit {
        for (fault in listOf("acquire", "reconcile", "marker-clear", "marker-clear-after", "marker-read", "owner-close", "intent-remove", "intent-remove-after", "intent-read")) {
            val backend = Backend()
            val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
            assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
            backend.boot = RecoveryStamp(SECOND_BOOT)
            backend.fault = fault
            assertEquals(fault, UpgradeRecoveryResult.UNKNOWN, recovery.recover())
            assertTrue(fault, recovery.blocked())
            if (fault != "intent-remove-after" && fault != "intent-read") assertNotNull(fault, backend.intent)
            if (fault == "owner-close") assertEquals(1, backend.steps.count { it == "owner-close" })
        }
    }

    @Test fun interruptAfterReconcileLeavesTheFenceInPlace(): Unit {
        val backend = Backend()
        val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
        backend.boot = RecoveryStamp(SECOND_BOOT)
        backend.fault = "interrupt-reconcile"
        try {
            assertEquals(UpgradeRecoveryResult.UNKNOWN, recovery.recover())
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(recovery.blocked())
            assertNotNull(backend.intent)
            assertFalse(backend.steps.contains("marker-clear"))
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun retryAfterFalseNegativeNeverRemovesTheIntent(): Unit {
        val backend = Backend()
        val recovery = UpgradeRecovery(backend, eligibleAtStartup = true)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
        backend.boot = RecoveryStamp(FIRST_BOOT)
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.recover())
        assertEquals(UpgradeRecoveryResult.REBOOT_REQUIRED, recovery.arm())
        assertEquals(RecoveryStamp(FIRST_BOOT), backend.intent)
        assertTrue(recovery.blocked())
        assertEquals(0, backend.removed)
    }

    private class RawMarker : SessionMarker {
        var value = MarkerState.ACTIVE
        override fun read(): MarkerState = value
        override fun markActive(): Boolean { value = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { value = MarkerState.CLEAR; return true }
    }

    private class Backend : UpgradeRecoveryBackend {
        override var recoveryBlocked: Boolean = false
        override var quarantined: Boolean = false
        val rawMarker = RawMarker()
        var boot = RecoveryStamp(FIRST_BOOT)
        var uptimeMillis = 60_000L
        var intent: RecoveryStamp? = null
        var fault = ""
        var removed = 0
        var done = false
        val selection = setOf("chosen")
        val steps = mutableListOf<String>()
        override fun readIntent(): RecoveryIntentState {
            steps.add("intent-read")
            if (fault == "intent-unknown" || fault == "intent-read" && removed > 0) return RecoveryIntentState.Unknown
            return intent?.let { RecoveryIntentState.Armed(it) } ?: if (done) RecoveryIntentState.Done else RecoveryIntentState.Absent
        }
        override fun writeIntent(stamp: RecoveryStamp): Boolean { steps.add("intent-write"); intent = stamp; done = false; return true }
        override fun finishIntent(): Boolean { steps.add("intent-remove"); if (fault == "intent-remove") return false; intent = null; done = true; removed++; return fault != "intent-remove-after" }
        override fun readStamp(): RecoveryStamp? = if (fault == "boot-id-unavailable") null else boot
        override fun readRawMarker(): MarkerState = rawMarker.read().also { steps.add("marker-read") }.let {
            if (fault == "raw-unknown" || fault == "marker-read" && it == MarkerState.CLEAR) MarkerState.UNKNOWN else it
        }
        override fun clearRawMarker(): Boolean { steps.add("marker-clear"); if (fault == "marker-clear") return false; rawMarker.markClear(); return fault != "marker-clear-after" }
        override fun acquireOwner(): RecoveryOwner? {
            steps.add("acquire")
            if (fault == "acquire") return null
            return object : RecoveryOwner {
                override fun close(): Unit { steps.add("owner-close"); if (fault == "owner-close") throw IOException("synthetic") }
            }
        }
        override fun reconcile(): Boolean { steps.add("reconcile"); if (fault == "interrupt-reconcile") Thread.currentThread().interrupt(); return fault != "reconcile" }
    }

    companion object {
        private const val FIRST_BOOT = "11111111-1111-4111-8111-111111111111"
        private const val SECOND_BOOT = "22222222-2222-4222-8222-222222222222"
    }
}
