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
            var permissionMessage by remember { mutableStateOf("") }
            var operationMessage by remember { mutableStateOf("") }
            var subscriptionOpen by remember { mutableStateOf(false) }
            var serversOpen by remember { mutableStateOf(false) }
            var selectionError by remember { mutableStateOf("") }
            var selectionProbe by remember { mutableStateOf(0) }
            fun addEvent(event: ScreenEvent) {
                graph.addEvent(event)
            }
            val busy = vpnState.phase in setOf(VpnPhase.CONNECTING, VpnPhase.RUNNING, VpnPhase.STOPPING)
            val searchRunning = searchState.phase in setOf(
                SearchPhase.RUNNING,
                SearchPhase.PAUSING,
                SearchPhase.PAUSED,
                SearchPhase.CANCELLING,
            )
            val selected = graph.canStartVpn()
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    permissionMessage = ""
                    startVpn(graph) { message -> permissionMessage = message }
                } else {
                    permissionMessage = "Подключение VPN не разрешено"
                }
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) {
                    permissionMessage = ""
                    startVpn(graph) { message -> permissionMessage = message }
                } else {
                    permission.launch(intent)
                }
            }
            SweepTheme {
                VpnSweepScreen(
                    state = vpnState,
                    selected = selected,
                    importing = subscriptionState.phase == SubscriptionPhase.LOADING,
                    message = operationMessage,
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
                        operationMessage = ""
                        subscriptionOpen = true
                    },
                    onServers = {
                        operationMessage = ""
                        selectionError = ""
                        selectionProbe++
                        serversOpen = true
                    },
                    permissionMessage = permissionMessage,
                    sourceEnabled = !busy && !searchRunning && subscriptionState.phase != SubscriptionPhase.LOADING,
                    searchEnabled = graph.searchEnabled() || searchRunning,
                    searchRunning = searchRunning,
                    searchMessage = "",
                    information = ScreenInformation(events = events.toList(), startRejection = searchState.startRejection?.safeCode),
                    onSearch = {
                        operationMessage = ""
                        if (searchRunning) {
                            graph.cancelSearch()
                        } else {
                            val outcome = graph.startSearch()
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
                            when (outcome) {
                                SubscriptionStartV1.ACCEPTED -> {
                                    operationMessage = ""
                                    true
                                }
                                SubscriptionStartV1.BUSY -> {
                                    operationMessage = "Дождитесь окончания текущей загрузки"
                                    false
                                }
                                SubscriptionStartV1.REJECTED -> {
                                    operationMessage = "Не удалось начать загрузку подписки"
                                    false
                                }
                            }
                        },
                    )
                }
                if (serversOpen) {
                    ServerSelectionDialog(
                        servers = subscriptionState.records.map { record ->
                            ServerChoice(record.id, record.title, record.subtitle, record.supported)
                        },
                        selectedIds = subscriptionState.selectedIds,
                        canConfirm = remember(subscriptionState, searchState, selectionProbe) { graph.selectionAvailable() },
                        errorMessage = selectionError,
                        onRefreshAvailability = {
                            if (graph.searchState.value.ownerBusyRetry) {
                                when (graph.retryOwnerBusyForSelection()) {
                                    SearchStartV3.BUSY -> addEvent(ScreenEvent.SEARCH_BUSY)
                                    SearchStartV3.REJECTED -> addEvent(ScreenEvent.SEARCH_REJECTED)
                                    SearchStartV3.ACCEPTED, SearchStartV3.UNKNOWN -> Unit
                                }
                            }
                            selectionProbe++
                        },
                        onDismiss = { serversOpen = false },
                        onConfirm = { ids ->
                            if (graph.select(ids)) {
                                addEvent(ScreenEvent.SELECTION_SAVED)
                                operationMessage = ""
                                serversOpen = false
                            } else {
                                addEvent(ScreenEvent.SELECTION_FAILED)
                                selectionError = "Не удалось сохранить выбор серверов"
                                selectionProbe++
                                operationMessage = "Не удалось сохранить выбор серверов"
                            }
                        },
                    )
                }
            }
        }
    }

    private fun startVpn(graph: AppComposition, report: (String) -> Unit): Unit {
        val ticket = graph.prepareVpnStart()
        if (ticket == null) {
            report("Сначала завершите подбор и дождитесь сохранения результата")
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
            report("Не удалось запустить VPN-сервис")
        }
    }
}

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
