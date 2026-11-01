package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.searchworker.SupervisorTerminationEvidence
import com.seraf.vpnsweep.searchworker.SupervisorDeathProof
import java.io.IOException
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupervisedStartupRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun legacyArmedClearIsRefencedBeforeIntentChangesAndProofPrecedesRecovery(): Unit {
        val backend = Backend().apply {
            intent = RecoveryIntentState.Armed(RecoveryStamp("11111111-1111-4111-8111-111111111111"))
            marker = MarkerState.CLEAR
        }
        val recovery = recovery(backend)
        assertTrue(recovery.prepare())
        assertEquals(listOf("active", "read-ACTIVE", "pending"), backend.steps.take(3))
        var result: SupervisorRecoveryResult? = null
        val identity = Any()
        assertTrue(recovery.expectSupervisor(identity))
        val proof = SupervisorTerminationEvidence(identity) { result = recovery.complete(it) }
        assertTrue(proof.beginLink())
        proof.linkResult(true)
        proof.unbindResult(true)
        assertNull(result)
        assertFalse(backend.steps.contains("owner"))
        proof.binderDied()
        assertEquals(SupervisorRecoveryResult.RECOVERED, result)
        assertEquals(MarkerState.CLEAR, backend.marker)
        assertEquals(RecoveryIntentState.Done, backend.intent)
        assertEquals(listOf("owner", "reconcile", "owner-close", "done", "clear", "read-CLEAR"), backend.steps.takeLast(6))
        assertFalse(backend.recoveryBlocked)
    }

    @Test fun eachUnknownOrIncompleteStepKeepsGuardAndNeverClearsEarly(): Unit {
        for (fault in listOf("marker-unknown", "pending", "owner-unknown", "reconcile", "owner-close", "done", "clear", "readback")) {
            val backend = Backend().apply { this.fault = fault }
            val recovery = recovery(backend)
            val prepared = recovery.prepare()
            if (fault in setOf("marker-unknown", "pending")) assertFalse(fault, prepared)
            else {
                assertTrue(fault, prepared)
                assertEquals(fault, SupervisorRecoveryResult.UNKNOWN, recovery.complete(proof(recovery)))
            }
            assertTrue(fault, backend.recoveryBlocked)
            if (fault != "clear" && fault != "readback") assertFalse(fault, backend.steps.contains("clear"))
            if (fault == "owner-close") assertEquals(1, backend.steps.count { it == "owner-close" })
        }
    }

    @Test fun busyOwnerIsRetriedOnlyAfterSameDeathProofAndNoMutation(): Unit {
        val backend = Backend().apply { fault = "owner-busy" }
        val recovery = recovery(backend)
        assertTrue(recovery.prepare())
        val confirmed = proof(recovery)
        assertEquals(SupervisorRecoveryResult.BUSY, recovery.complete(confirmed))
        assertEquals(MarkerState.ACTIVE, backend.marker)
        assertFalse(backend.steps.contains("reconcile"))
        backend.fault = ""
        assertEquals(SupervisorRecoveryResult.RECOVERED, recovery.complete(confirmed))
        assertEquals(MarkerState.CLEAR, backend.marker)
    }

    @Test fun staleAttemptProofCannotRecoverNewAttempt(): Unit {
        val backend = Backend()
        val recovery = recovery(backend)
        assertTrue(recovery.prepare())
        val old = proof(recovery)
        val current = proof(recovery)
        assertEquals(SupervisorRecoveryResult.UNKNOWN, recovery.complete(old))
        assertFalse(backend.steps.contains("owner"))
        assertEquals(SupervisorRecoveryResult.RECOVERED, recovery.complete(current))
    }

    @Test fun failedFinalMarkerSyncNeedsStrictSyncAfterProcessRestart(): Unit {
        val markerDirectory = File(temporary.root, "marker")
        var failMarkerSync = false
        val raw = PrivateSessionMarker(markerDirectory) {
            if (failMarkerSync && it == markerDirectory) throw IOException("synthetic")
        }
        assertTrue(raw.markActive())
        val intentDirectory = File(temporary.root, "fence")
        val intent = RecoveryIntentStore(intentDirectory) { }
        val backend = object : UpgradeRecoveryBackend {
            override var recoveryBlocked = true
            override var quarantined = false
            override fun readIntent(): RecoveryIntentState = intent.read()
            override fun writeIntent(stamp: RecoveryStamp): Boolean = false
            override fun finishIntent(): Boolean = false
            override fun readStamp(): RecoveryStamp? = null
            override fun readRawMarker(): MarkerState = raw.readForRecovery()
            override fun clearRawMarker(): Boolean = raw.markClear()
            override fun acquireOwner(): RecoveryOwner? = null
            override fun reconcile(): Boolean = true
        }
        val recovery = SupervisedStartupRecovery(backend, raw::markActive, intent::prepareSupervisorRecovery,
            { intent.finishSupervisorRecovery().also { if (it) failMarkerSync = true } },
            { SupervisorOwner.Acquired(object : RecoveryOwner { override fun close(): Unit = Unit }) })
        assertTrue(recovery.prepare())
        assertEquals(SupervisorRecoveryResult.UNKNOWN, recovery.complete(proof(recovery)))
        assertTrue(backend.recoveryBlocked)
        assertEquals(RecoveryIntentState.Done, RecoveryIntentStore(intentDirectory) { }.read())
        val failedRestart = PrivateSessionMarker(markerDirectory) { throw IOException("synthetic") }
        assertTrue(recoveryRequiredAtStartup(failedRestart, RecoveryIntentState.Done, failedRestart::readForRecovery))
        val confirmedRestart = PrivateSessionMarker(markerDirectory) { }
        assertFalse(recoveryRequiredAtStartup(confirmedRestart, RecoveryIntentState.Done, confirmedRestart::readForRecovery))
    }

    private fun proof(recovery: SupervisedStartupRecovery): SupervisorDeathProof {
        val identity = Any()
        assertTrue(recovery.expectSupervisor(identity))
        var result: SupervisorDeathProof? = null
        val evidence = SupervisorTerminationEvidence(identity) { result = it }
        assertTrue(evidence.beginLink())
        evidence.linkResult(true)
        evidence.unbindResult(true)
        evidence.binderDied()
        return checkNotNull(result)
    }

    private fun recovery(backend: Backend): SupervisedStartupRecovery = SupervisedStartupRecovery(
        backend,
        markActive = { backend.steps.add("active"); backend.marker = MarkerState.ACTIVE; true },
        preparePending = { backend.steps.add("pending"); if (backend.fault == "pending") false else { backend.intent = RecoveryIntentState.SupervisedPending; true } },
        finishPending = { backend.steps.add("done"); if (backend.fault == "done") false else { backend.intent = RecoveryIntentState.Done; true } },
        acquire = {
            backend.steps.add("owner")
            when (backend.fault) {
                "owner-busy" -> SupervisorOwner.Busy
                "owner-unknown" -> SupervisorOwner.Unknown
                else -> SupervisorOwner.Acquired(object : RecoveryOwner {
                    override fun close(): Unit { backend.steps.add("owner-close"); if (backend.fault == "owner-close") throw IOException("synthetic") }
                })
            }
        },
    )

    private class Backend : UpgradeRecoveryBackend {
        override var recoveryBlocked = true
        override var quarantined = false
        var marker = MarkerState.ACTIVE
        var intent: RecoveryIntentState = RecoveryIntentState.Absent
        var fault = ""
        val steps = mutableListOf<String>()
        override fun readIntent(): RecoveryIntentState = intent
        override fun writeIntent(stamp: RecoveryStamp): Boolean = error("unexpected arm")
        override fun finishIntent(): Boolean = error("unexpected reboot recovery")
        override fun readStamp(): RecoveryStamp? = error("unexpected boot id")
        override fun readRawMarker(): MarkerState {
            val value = if (fault == "marker-unknown" || fault == "readback" && marker == MarkerState.CLEAR) MarkerState.UNKNOWN else marker
            if (steps.isNotEmpty() && steps.last() in setOf("active", "clear")) steps.add("read-$value")
            return value
        }
        override fun clearRawMarker(): Boolean { steps.add("clear"); if (fault == "clear") return false; marker = MarkerState.CLEAR; return true }
        override fun acquireOwner(): RecoveryOwner? = error("unexpected owner")
        override fun reconcile(): Boolean { steps.add("reconcile"); return fault != "reconcile" }
    }
}
