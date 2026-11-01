package com.seraf.vpnsweep.searchstate

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateSessionMarkerRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recoveryRequiresExistingValidMarker(): Unit {
        val root = File(temporary.root, "marker")
        val marker = PrivateSessionMarker(root) { }
        assertEquals(MarkerState.CLEAR, marker.read())
        assertEquals(MarkerState.UNKNOWN, marker.readForRecovery())
        assertTrue(marker.markActive())
        assertEquals(MarkerState.ACTIVE, marker.readForRecovery())
        assertTrue(marker.markClear())
        assertEquals(MarkerState.CLEAR, marker.readForRecovery())
        File(root, "current").writeText("invalid")
        assertEquals(MarkerState.UNKNOWN, marker.readForRecovery())
        assertTrue(File(root, "current").delete())
        assertEquals(MarkerState.UNKNOWN, marker.readForRecovery())
    }

    @Test fun completedWitnessRequiresDurablePresentMarkerAtStartup(): Unit {
        val markerRoot = File(temporary.root, "marker")
        val fenceRoot = File(temporary.root, "fence")
        var failSync = false
        val marker = PrivateSessionMarker(markerRoot) { if (failSync) throw java.io.IOException("synthetic") }
        val fence = RecoveryIntentStore(fenceRoot) { }
        val boot = RecoveryStamp("11111111-1111-4111-8111-111111111111")
        assertTrue(marker.markActive())
        assertTrue(fence.create(boot))
        assertTrue(marker.markClear())
        assertTrue(fence.finish())
        failSync = true
        assertFalse(marker.markClear())
        val failedMarker = PrivateSessionMarker(markerRoot) { throw java.io.IOException("synthetic") }
        val restartedFence = RecoveryIntentStore(fenceRoot) { }
        assertEquals(RecoveryIntentState.Done, restartedFence.read())
        assertTrue(recoveryRequiredAtStartup(failedMarker, restartedFence.read(), failedMarker::readForRecovery))
        val syncedMarker = PrivateSessionMarker(markerRoot) { }
        assertFalse(recoveryRequiredAtStartup(syncedMarker, restartedFence.read(), syncedMarker::readForRecovery))
        assertTrue(File(markerRoot, "current").delete())
        assertTrue(recoveryRequiredAtStartup(syncedMarker, restartedFence.read(), syncedMarker::readForRecovery))
        val fresh = PrivateSessionMarker(File(temporary.root, "fresh-marker")) { }
        assertFalse(recoveryRequiredAtStartup(fresh, RecoveryIntentState.Absent, fresh::readForRecovery))
    }
}
