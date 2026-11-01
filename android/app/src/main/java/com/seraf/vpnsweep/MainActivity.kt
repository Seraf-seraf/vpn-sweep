package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.SweepTheme
import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seraf.vpnsweep.searchprotocol.SearchFailureV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SearchUiState
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SubscriptionUiState
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionFailureV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.ScreenInformation
import com.seraf.vpnsweep.searchui.dispatchSearchButton
import com.seraf.vpnsweep.searchui.searchButtonCancels
import com.seraf.vpnsweep.searchui.searchOutcomeEvent
import com.seraf.vpnsweep.searchui.subscriptionOutcomeEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        val graph = (application as SweepApplication).integration
        setContent {
            val vpnState by VpnStatus.state.collectAsStateWithLifecycle()
            val subscriptionState by graph.subscriptionState().collectAsStateWithLifecycle()
            val searchState by graph.searchState.collectAsStateWithLifecycle()
            val events by graph.events.collectAsStateWithLifecycle()
            var subscriptionOpen by remember { mutableStateOf(false) }
            var serversOpen by remember { mutableStateOf(false) }
            fun addEvent(event: ScreenEvent) {
                graph.addEvent(event)
            }
            val busy = vpnState.phase in setOf(VpnPhase.CONNECTING, VpnPhase.RUNNING, VpnPhase.STOPPING)
            val searchRunning = searchButtonCancels(searchState.phase)
            val selected = graph.canStartVpn()
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    startVpn(graph, ::addEvent)
                } else {
                    addEvent(ScreenEvent.VPN_PERMISSION_DENIED)
                }
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) {
                    startVpn(graph, ::addEvent)
                } else {
                    permission.launch(intent)
                }
            }
            SweepTheme {
                VpnSweepScreen(
                    state = vpnState,
                    selected = selected,
                    importing = subscriptionState.phase == SubscriptionPhase.LOADING,
                    message = "",
                    testing = searchRunning,
                    checkResults = emptyList(),
                    onPower = {
                        if (busy) {
                            startService(Intent(this@MainActivity, SweepVpnService::class.java).setAction(SweepVpnService.STOP))
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onImport = {},
                    onRunChecks = {},
                    onSubscription = {
                        subscriptionOpen = true
                    },
                    onServers = {
                        serversOpen = true
                    },
                    sourceEnabled = !busy && subscriptionState.phase != SubscriptionPhase.LOADING,
                    searchEnabled = searchRunning || graph.searchEnabled(),
                    searchRunning = searchRunning,
                    searchMessage = "",
                    information = ScreenInformation(events = events.toList(), startRejection = searchState.startRejection?.safeCode),
                    serverSummary = committedServerSummary(subscriptionState),
                    onSearch = {
                        dispatchSearchButton(searchState.phase, graph::cancelSearch, graph::startSearch) { outcome ->
                            searchOutcomeEvent(outcome, graph.searchState.value)?.let(::addEvent)
                        }
                    },
                )
                if (subscriptionOpen) {
                    SubscriptionDialog(
                        onDismiss = { subscriptionOpen = false },
                        onSubmit = { input ->
                            val outcome = graph.loadSubscription(input).status
                            subscriptionOutcomeEvent(outcome, graph.subscriptionState().value.phase)?.let(::addEvent)
                            outcome == SubscriptionStartV1.ACCEPTED
                        },
                    )
                }
                if (serversOpen) {
                    ServerSelectionDialog(
                        servers = subscriptionState.records.map { record ->
                            ServerChoice(record.id, record.title, record.subtitle, record.supported)
                        },
                        selectedIds = subscriptionState.selectedIds,
                        canConfirm = remember(subscriptionState, searchState) { graph.selectionAvailable() },
                        onDismiss = { serversOpen = false },
                        onConfirm = { ids ->
                            if (graph.select(ids)) {
                                addEvent(ScreenEvent.SELECTION_SAVED)
                                serversOpen = false
                            } else {
                                addEvent(ScreenEvent.SELECTION_FAILED)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun startVpn(graph: AppComposition, report: (ScreenEvent) -> Unit): Unit {
        val ticket = graph.prepareVpnStart()
        if (ticket == null) {
            report(ScreenEvent.VPN_START_UNAVAILABLE)
            return
        }
        try {
            startForegroundService(
                Intent(this, SweepVpnService::class.java)
                    .setAction(SweepVpnService.START)
                    .putExtra(SweepVpnService.EXTRA_START_TOKEN, ticket.token),
            )
        } catch (_: RuntimeException) {
            graph.cancelVpnStart(ticket.token)
            report(ScreenEvent.VPN_SERVICE_START_FAILED)
        }
    }
}

internal fun committedServerSummary(state: SubscriptionUiState): String =
    if (state.records.isEmpty()) "Серверы: нет данных"
    else "Серверы: ${state.records.size} · выбрано ${state.selectedIds.size}"

private fun searchStatus(
    search: SearchUiState,
    subscription: SubscriptionUiState,
    canStartVpn: Boolean,
): String = when (search.phase) {
    SearchPhase.RUNNING -> "Выполняется подбор настроек"
    SearchPhase.PAUSING -> "Подбор приостанавливается"
    SearchPhase.PAUSED -> "Подбор приостановлен"
    SearchPhase.CANCELLING -> "Подбор останавливается"
    SearchPhase.COMPLETE -> if (search.resultCommitted) "Результат сохранён" else "Результат не подтверждён"
    SearchPhase.CANCELLED -> "Подбор отменён"
    SearchPhase.FAILED -> searchFailureMessage(search.failure)
    SearchPhase.INTERRUPTED -> "Предыдущая операция прервана; запуск заблокирован"
    SearchPhase.IDLE -> when {
        subscription.phase == SubscriptionPhase.LOADING -> "Загрузка подписки"
        subscription.phase == SubscriptionPhase.FAILED -> subscriptionFailureMessage(subscription.failure)
        subscription.phase == SubscriptionPhase.NO_SUPPORTED -> "В подписке нет поддерживаемых серверов"
        subscription.selectedIds.isEmpty() -> "Выберите серверы для подбора"
        canStartVpn -> "Результат готов к подключению"
        else -> "Подбор временно недоступен"
    }
}

private fun searchFailureMessage(failure: SearchFailureV1?): String = when (failure) {
    SearchFailureV1.BUSY -> "Дождитесь окончания текущей операции"
    SearchFailureV1.INVALID_INPUT -> "Не удалось прочитать выбранные серверы"
    SearchFailureV1.CAPABILITY_BLOCKED -> "Подбор недоступен для текущей конфигурации"
    SearchFailureV1.EXHAUSTED -> "Подходящие настройки не найдены"
    SearchFailureV1.OWNER_LOST,
    SearchFailureV1.WORKER_QUARANTINED,
    SearchFailureV1.INTERRUPTED -> "Подбор прерван; повторный запуск заблокирован"
    SearchFailureV1.INTERNAL_ERROR -> "Не удалось завершить подбор"
    SearchFailureV1.PROFILE_TOO_LARGE -> "Выбранные данные превышают поддерживаемый размер"
    null -> "Не удалось завершить подбор"
}

private fun searchStartRejectedMessage(state: SearchUiState): String = state.startRejection?.let {
    "Подбор не запущен (${it.safeCode})"
} ?: "Подбор не запущен (reason_unavailable)"

private fun subscriptionFailureMessage(failure: SubscriptionFailureV1?): String = when (failure) {
    SubscriptionFailureV1.INVALID_URL -> "Введите корректную ссылку на подписку"
    SubscriptionFailureV1.TIMEOUT -> "Истекло время загрузки подписки"
    SubscriptionFailureV1.HTTP -> "Сервер отклонил запрос подписки"
    SubscriptionFailureV1.NETWORK -> "Не удалось получить подписку через выбранную сеть"
    SubscriptionFailureV1.TLS -> "Не удалось проверить защищённое соединение"
    SubscriptionFailureV1.MALFORMED,
    SubscriptionFailureV1.INVALID_BASE64 -> "Не удалось прочитать подписку"
    SubscriptionFailureV1.LIMIT_EXCEEDED -> "Подписка превышает поддерживаемый размер"
    SubscriptionFailureV1.CANCELLED -> "Загрузка подписки отменена"
    SubscriptionFailureV1.INTERNAL,
    null -> "Не удалось загрузить подписку"
}
