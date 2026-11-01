package com.seraf.vpnsweep.searchui

import com.seraf.vpnsweep.ui.presentInformation
import com.seraf.vpnsweep.SearchReadiness
import com.seraf.vpnsweep.readinessAfterChecks
import com.seraf.vpnsweep.recheckSearchReadinessFlow
import com.seraf.vpnsweep.ui.informationPaneHeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SearchUiState
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2

class ScreenInformationTest {
    @Test
    fun readinessFlowChecksRecoveryBeforeSelectionAndNeverStartsSearch(): Unit {
        var marker = "ACTIVE"
        var checks = 0
        val busy = recheckSearchReadinessFlow(true, { SearchStartV3.BUSY }, { checks++; true }, { true }, { true })
        assertEquals(SearchReadiness.BUSY, busy)
        assertEquals("ACTIVE", marker)
        assertEquals(0, checks)
        val unknown = recheckSearchReadinessFlow(true, { SearchStartV3.UNKNOWN }, { checks++; true }, { true }, { true })
        assertEquals(SearchReadiness.UNKNOWN, unknown)
        assertEquals("ACTIVE", marker)
        assertEquals(0, checks)
        val ready = recheckSearchReadinessFlow(true, { marker = "CLEAR"; SearchStartV3.ACCEPTED },
            { checks++; marker == "CLEAR" }, { true }, { true })
        assertEquals(SearchReadiness.READY, ready)
        assertEquals("CLEAR", marker)
        assertEquals(1, checks)
        val draftOnly = recheckSearchReadinessFlow(false, { error("unexpected recovery") }, { true }, { false }, { error("unexpected start check") })
        assertEquals(SearchReadiness.SELECT_REQUIRED, draftOnly)
    }

    @Test
    fun readinessNeverConfusesUnknownOrDraftWithCommittedSelection(): Unit {
        assertEquals(SearchReadiness.UNKNOWN, readinessAfterChecks(SearchStartV3.UNKNOWN, true, true, true))
        assertEquals(SearchReadiness.UNKNOWN, readinessAfterChecks(SearchStartV3.REJECTED, true, true, true))
        assertEquals(SearchReadiness.BUSY, readinessAfterChecks(SearchStartV3.BUSY, true, true, true))
        assertEquals(SearchReadiness.UNKNOWN, readinessAfterChecks(null, false, true, true))
        assertEquals(SearchReadiness.SELECT_REQUIRED, readinessAfterChecks(SearchStartV3.ACCEPTED, true, false, false))
        assertEquals(SearchReadiness.UNKNOWN, readinessAfterChecks(SearchStartV3.ACCEPTED, true, true, false))
        assertEquals(SearchReadiness.READY, readinessAfterChecks(SearchStartV3.ACCEPTED, true, true, true))
    }

    @Test
    fun warningAndFailureEventsUseHonestSeverity(): Unit {
        val entries = presentInformation(ScreenInformation(events = listOf(ScreenEvent.SEARCH_STARTING, ScreenEvent.SEARCH_BUSY, ScreenEvent.SEARCH_INTERRUPTED, ScreenEvent.WORKER_START_FAILED))).events
        assertEquals(listOf("INFO", "WARN", "ERROR", "ERROR"), entries.map { it.level })
    }

    @Test
    fun paneHeightDependsOnViewportNotTabOrContent(): Unit {
        assertEquals(0.dp, informationPaneHeight(0.dp))
        assertEquals(105.6f, informationPaneHeight(320.dp).value, 0.01f)
        assertEquals(211.2f, informationPaneHeight(640.dp).value, 0.01f)
        assertEquals(220.dp, informationPaneHeight(800.dp))
        assertEquals(156f, informationPaneHeight(240.dp, 2f).value, 0.01f)
        assertEquals(220.dp, informationPaneHeight(800.dp, 2f))
    }

    @Test
    fun workerReasonIsSafeAllowlistedJournalText(): Unit {
        val event = workerOpenEvent(WorkerCandidateFailureV2.CORE_START_FAILED)
        assertTrue(presentInformation(ScreenInformation(events = listOf(event))).events.single().message.startsWith("worker_open:core_start_failed"))
        assertTrue(presentInformation(ScreenInformation(events = listOf(ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE))).events.single().message.startsWith("search_init:network_policy_unavailable"))
    }

    @Test
    fun lifecycleEventsAndSafeRejectionReachJournal(): Unit {
        assertEquals(ScreenEvent.SUBSCRIPTION_LOADING, subscriptionEvent(SubscriptionPhase.LOADING))
        assertEquals(ScreenEvent.SUBSCRIPTION_READY, subscriptionEvent(SubscriptionPhase.READY))
        assertEquals(ScreenEvent.SUBSCRIPTION_FAILED, subscriptionEvent(SubscriptionPhase.FAILED))
        assertEquals(ScreenEvent.SUBSCRIPTION_CANCELLED, subscriptionEvent(SubscriptionPhase.CANCELLED))
        assertEquals(ScreenEvent.SEARCH_STARTING, searchEvent(SearchPhase.RUNNING))
        assertEquals(ScreenEvent.SEARCH_CANCEL_REQUESTED, searchEvent(SearchPhase.CANCELLING))
        assertEquals(ScreenEvent.SEARCH_INTERRUPTED, searchEvent(SearchPhase.INTERRUPTED))
        assertEquals(ScreenEvent.SUBSCRIPTION_BUSY, subscriptionOutcomeEvent(SubscriptionStartV1.BUSY, SubscriptionPhase.LOADING))
        assertNull(subscriptionOutcomeEvent(SubscriptionStartV1.REJECTED, SubscriptionPhase.FAILED))
        assertEquals(ScreenEvent.SEARCH_BUSY, searchOutcomeEvent(SearchStartV3.BUSY, SearchUiState()))
        assertNull(searchOutcomeEvent(SearchStartV3.UNKNOWN, SearchUiState(phase = SearchPhase.INTERRUPTED)))
        val info = ScreenInformation(events = listOf(ScreenEvent.SELECTION_FAILED), startRejection = "preflight:revision_mismatch")
        assertEquals(listOf("Не удалось сохранить выбор серверов", "Подбор не запущен (preflight:revision_mismatch)"), presentInformation(info).events.map { it.message })
        assertEquals(0, presentInformation(ScreenInformation(startRejection = "secret-not-a-code")).events.size)
    }

    @Test
    fun suppliedEventsAndAppliedValuesReachPresentation(): Unit {
        val information = ScreenInformation(
            events = listOf(ScreenEvent.SEARCH_STARTED, ScreenEvent.PAUSED),
            applied = AppliedParameters(
                fragmentEnabled = true, fragmentPackets = FragmentPackets.ONE_TO_THREE,
                fragmentLengthBytes = NumericRange.Pair(50, 100), fragmentIntervalMillis = NumericRange.Single(10),
                fragmentMaxSplit = NumericRange.Pair(1, 64), tcpConcurrency = -1, xudpConcurrency = 1024,
                udp443 = Udp443.REJECT, fingerprint = Fingerprint.BROWSER_360,
            ),
        )
        val presentation = presentInformation(information)
        assertEquals(listOf("Подбор начат", "Подбор приостановлен"), presentation.events.map { it.message })
        assertEquals(listOf("Вкл", "1-3", "50-100 байт", "10 мс", "1-64"), presentation.applied!!.fragment!!.fields.map { it.value })
        assertEquals(listOf("Нет данных", "-1", "1024", "reject"), presentation.applied.mux!!.fields.map { it.value })
        assertEquals("360", presentation.applied.fingerprint!!.summary)
        assertEquals("qq", presentInformation(ScreenInformation(applied = AppliedParameters(fingerprint = Fingerprint.QQ))).applied!!.fingerprint!!.summary)
        assertNull(presentInformation(ScreenInformation()).applied)
    }
}
