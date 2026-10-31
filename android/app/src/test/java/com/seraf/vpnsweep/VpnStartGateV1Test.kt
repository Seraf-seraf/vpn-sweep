package com.seraf.vpnsweep

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRejectV1
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VpnStartGateV1Test {
    @Test
    fun acceptedTypedSnapshotFlowsThroughSearchLeaseToOneShotServiceTicket(): Unit {
        val gate = VpnStartGateV1()
        val revision = ResultRevision("101", "202")
        val sourceSnapshot = FakeSnapshot()
        val accepted = checkNotNull(AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(8, sourceSnapshot, revision))
        val searchSnapshot = SearchSnapshotV1(12, 101, 202, FakeInput())
        val searchLease = checkNotNull(
            SearchLeaseReceiptV1.fromAcceptedSnapshot(
                accepted,
                searchSnapshot,
                "4dc3d223-873b-4f01-9fc8-a7ee6ec50a23",
            ),
        )
        val identity = checkNotNull(
            VpnStartIdentityV1.fromCommittedSearch(
                accepted,
                searchLease,
                revision,
                searchComplete = true,
                resultCommitted = true,
                vpnLeaseIdentity = "6b2172a4-f329-4ef6-93c2-faa8d437ab02",
            ),
        )
        val ticket = checkNotNull(gate.issue(identity))

        assertEquals(8, ticket.identity.requestGeneration)
        assertEquals(12, ticket.identity.sessionGeneration)
        assertEquals("101", ticket.identity.sourceRevision)
        assertEquals("202", ticket.identity.selectionRevision)
        assertEquals("4dc3d223-873b-4f01-9fc8-a7ee6ec50a23", ticket.identity.searchLeaseIdentity)
        assertEquals("6b2172a4-f329-4ef6-93c2-faa8d437ab02", ticket.identity.vpnLeaseIdentity)
        assertEquals(ticket, gate.consume(ticket.token, identity))
        assertNull(gate.consume(ticket.token, identity))
        sourceSnapshot.close()
        searchSnapshot.profiles.close()
    }

    @Test
    fun rejectsStaleRevisionGenerationOrUncommittedSearchBeforeIssuingTicket(): Unit {
        val accepted = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(
            8,
            FakeSnapshot(),
            ResultRevision("101", "202"),
        ) ?: error("snapshot receipt")
        val searched = SearchLeaseReceiptV1(
            requestGeneration = 7,
            sessionGeneration = 12,
            sourceRevision = 101,
            selectionRevision = 202,
            leaseIdentity = "4dc3d223-873b-4f01-9fc8-a7ee6ec50a23",
        )

        assertNull(
            VpnStartIdentityV1.fromCommittedSearch(
                accepted,
                searched,
                ResultRevision("101", "202"),
                searchComplete = true,
                resultCommitted = true,
                vpnLeaseIdentity = "6b2172a4-f329-4ef6-93c2-faa8d437ab02",
            ),
        )
        assertNull(
            VpnStartIdentityV1.fromCommittedSearch(
                accepted,
                searched.copy(requestGeneration = 8),
                ResultRevision("101", "203"),
                searchComplete = true,
                resultCommitted = true,
                vpnLeaseIdentity = "6b2172a4-f329-4ef6-93c2-faa8d437ab02",
            ),
        )
        assertNull(
            VpnStartIdentityV1.fromCommittedSearch(
                accepted,
                searched.copy(requestGeneration = 8),
                ResultRevision("101", "202"),
                searchComplete = true,
                resultCommitted = false,
                vpnLeaseIdentity = "6b2172a4-f329-4ef6-93c2-faa8d437ab02",
            ),
        )
    }

    @Test
    fun doesNotConsumeTicketOnWrongTokenAndConsumesExactIdentityOnce(): Unit {
        val gate = VpnStartGateV1()
        val identity = VpnStartIdentityV1(
            requestGeneration = 8,
            sessionGeneration = 12,
            sourceRevision = "101",
            selectionRevision = "202",
            searchLeaseIdentity = "4dc3d223-873b-4f01-9fc8-a7ee6ec50a23",
            vpnLeaseIdentity = "6b2172a4-f329-4ef6-93c2-faa8d437ab02",
        )
        val ticket = checkNotNull(gate.issue(identity))

        assertNull(gate.consume("wrong-token", identity))
        assertEquals(identity, gate.pendingIdentity(ticket.token))
        assertNull(gate.consume(ticket.token, identity.copy(requestGeneration = 9)))
        assertNull(gate.pendingIdentity(ticket.token))
        assertFalse(gate.cancel("unknown"))
    }

    private class FakeSnapshot : SubscriptionSnapshotV1 {
        override val records: List<SubscriptionRecordV1> = listOf(
            SubscriptionRecordV1("record-1", "Synthetic", "", true, null),
            SubscriptionRecordV1("record-2", "Unsupported", "", false, SubscriptionRejectV1.UNSUPPORTED_RECORD),
        )

        override fun selectedProfiles(ids: Set<String>): PrivateInputV1? =
            if (ids == setOf("record-1")) FakeInput() else null

        override fun close(): Unit = Unit
    }

    private class FakeInput : PrivateInputV1 {
        override fun open(): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override fun close(): Unit = Unit
    }
}
