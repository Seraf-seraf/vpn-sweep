package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchprotocol.WorkerInterruptedV2
import com.seraf.vpnsweep.searchprotocol.WorkerOperationV1
import com.seraf.vpnsweep.searchprotocol.WorkerReplyV1
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SearchWorkerBinderPortTest {
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
