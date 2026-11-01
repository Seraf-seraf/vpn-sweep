package com.seraf.vpnsweep.searchui

import com.seraf.vpnsweep.ui.presentInformation
import com.seraf.vpnsweep.ui.informationPaneHeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SearchUiState
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2

class ScreenInformationTest {
    @Test
    fun paneHeightDependsOnViewportNotTabOrContent(): Unit {
        assertEquals(0.dp, informationPaneHeight(0.dp))
        assertEquals(105.6f, informationPaneHeight(320.dp).value, 0.01f)
        assertEquals(211.2f, informationPaneHeight(640.dp).value, 0.01f)
        assertEquals(220.dp, informationPaneHeight(800.dp))
    }

    @Test
    fun workerReasonIsSafeAllowlistedJournalText(): Unit {
        val event = workerOpenEvent(WorkerCandidateFailureV2.CORE_START_FAILED)
        assertEquals("worker_open:core_start_failed", presentInformation(ScreenInformation(events = listOf(event))).events.single().message)
        assertEquals("search_init:network_policy_unavailable", presentInformation(ScreenInformation(events = listOf(ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE))).events.single().message)
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
