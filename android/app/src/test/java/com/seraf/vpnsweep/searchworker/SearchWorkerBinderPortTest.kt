package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.WorkerInterruptedV2
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import com.seraf.vpnsweep.searchprotocol.WorkerOpenV2
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class SearchWorkerBinderPortTest {
    @Test
    fun candidateStatusIsTypedOnlyAfterConfirmedRetirement(): Unit {
        val statuses = listOf(
            Mobile.SearchWorkerCandidateConfigRejected to WorkerCandidateFailureV2.CONFIG_REJECTED,
            Mobile.SearchWorkerCandidateStartFailed to WorkerCandidateFailureV2.CORE_START_FAILED,
            Mobile.SearchWorkerCandidateStartTimeout to WorkerCandidateFailureV2.CORE_START_TIMEOUT,
            Mobile.SearchWorkerCandidateExitedBeforeReady to WorkerCandidateFailureV2.CORE_EXITED_BEFORE_READY,
        )
        for ((status, reason) in statuses) {
            var attempts = 0
            assertEquals(WorkerOpenV2.CandidateFailure(reason, true), candidateOpenAfterRetirement(status or Mobile.SearchWorkerRetryableFlag) { attempts++; true })
            assertEquals(1, attempts)
            assertEquals(WorkerOpenV2.Fatal(WorkerFatalV2.CLEANUP_INCOMPLETE), candidateOpenAfterRetirement(status) { false })
        }
        var attempted = false
        assertNull(candidateOpenAfterRetirement(Mobile.SearchWorkerDeliveryUnknown) { attempted = true; true })
        assertEquals(false, attempted)
        assertNull(candidateOpenAfterRetirement(Mobile.SearchWorkerCandidateStartFailed or (1L shl 20)) { true })
    }

    @Test
    fun onlyKnownInterruptionsBecomeTypedReplies(): Unit {
        assertEquals(WorkerReplyV1.Interrupted(WorkerInterruptedV2.CANCELLED), nonReadyWorkerReply(Mobile.SearchWorkerCancelled))
        assertEquals(WorkerReplyV1.Interrupted(WorkerInterruptedV2.DEADLINE), nonReadyWorkerReply(Mobile.SearchWorkerDeadline))
        for (status in listOf(
            Mobile.SearchWorkerReady,
            Mobile.SearchWorkerDeliveryUnknown,
            Mobile.SearchWorkerCleanupIncomplete,
            Mobile.SearchWorkerCandidateConfigRejected,
            Mobile.SearchWorkerCancelled or Mobile.SearchWorkerRetryableFlag,
            -1L,
            Long.MAX_VALUE,
        )) assertEquals(WorkerReplyV1.Quarantined, nonReadyWorkerReply(status))
    }

    @Test
    fun operationCodesMatchWorkerServiceContract(): Unit {
        assertEquals(Mobile.SearchWorkerCheck.toInt(), operationNumber(WorkerOperationV1.CHECK))
        assertEquals(Mobile.SearchWorkerUDP.toInt(), operationNumber(WorkerOperationV1.UDP))
        assertEquals(Mobile.SearchWorkerMedia.toInt(), operationNumber(WorkerOperationV1.MEDIA))
    }
}
