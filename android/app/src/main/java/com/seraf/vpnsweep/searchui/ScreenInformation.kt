package com.seraf.vpnsweep.searchui

import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SearchUiState
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import com.seraf.vpnsweep.searchprotocol.WorkerCandidateFailureV2
import com.seraf.vpnsweep.searchprotocol.WorkerFatalV2

enum class ScreenEvent { READINESS_READY, READINESS_SELECT_REQUIRED, READINESS_BUSY, READINESS_UNKNOWN, READINESS_REBOOT_REQUIRED, READINESS_RECOVERY_UNAVAILABLE, SUBSCRIPTION_LOADING, SUBSCRIPTION_READY, SUBSCRIPTION_FAILED, SUBSCRIPTION_BUSY, SUBSCRIPTION_CANCELLED, SELECTION_SAVED, SELECTION_FAILED, VPN_PERMISSION_DENIED, VPN_START_UNAVAILABLE, VPN_SERVICE_START_FAILED, SEARCH_STARTING, SEARCH_STARTED, SEARCH_BUSY, SEARCH_REJECTED, SEARCH_UNKNOWN, SEARCH_INTERRUPTED, SEARCH_CANCEL_REQUESTED, SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE, SEARCH_INIT_NETWORK_CHECK_FAILED, SEARCH_INIT_NETWORK_CALLBACK_EVENT, SEARCH_INIT_NETWORK_NON_DIRECT, SEARCH_INIT_NETWORK_BLOCKED, SEARCH_INIT_PROFILES_FAILED, SEARCH_INIT_CORE_IDENTITY_FAILED, SEARCH_INIT_MANIFEST_FAILED, SEARCH_INIT_NETWORK_UNKNOWN, SEARCH_INIT_SESSION_FAILED, WORKER_ADMISSION_FAILED, WORKER_BIND_FAILED, WORKER_CALL_FAILED, WORKER_CALL_INVALID, WORKER_CALL_STATE, WORKER_CALL_DEADLINE, WORKER_CALL_CANCELLED, WORKER_CANCEL_FAILED, WORKER_CLOSE_FAILED, WORKER_SERVICE_BUSY, WORKER_SERVICE_UNAVAILABLE, WORKER_SERVICE_DEADLINE, WORKER_SERVICE_CANCELLED, WORKER_BUSY, WORKER_OWNER_LOST, WORKER_CAPABILITY_UNAVAILABLE, WORKER_DELIVERY_UNKNOWN, WORKER_DIED, WORKER_CLEANUP_INCOMPLETE, WORKER_CONFIG_REJECTED, WORKER_START_FAILED, WORKER_START_TIMEOUT, WORKER_EXITED_EARLY, ATTEMPT_STARTED, ATTEMPT_FINISHED, PAUSED, CANCELLED, COMPLETE, FAILED }
enum class Fingerprint(val wireValue: String) {
    CHROME("chrome"), FIREFOX("firefox"), SAFARI("safari"), IOS("ios"), ANDROID("android"),
    EDGE("edge"), BROWSER_360("360"), QQ("qq"), RANDOM("random"), RANDOMIZED("randomized"),
}
enum class FragmentPackets(val wireValue: String) {
    TLS_HELLO("tlshello"), ONE_TO_ONE("1-1"), ONE_TO_TWO("1-2"), ONE_TO_THREE("1-3"), ONE_TO_FIVE("1-5"),
}
enum class Udp443(val wireValue: String) { REJECT("reject"), ALLOW("allow"), SKIP("skip") }
sealed interface NumericRange {
    data class Single(val value: Int) : NumericRange
    data class Pair(val first: Int, val last: Int) : NumericRange
}

data class AppliedParameters(
    val fragmentEnabled: Boolean? = null,
    val fragmentPackets: FragmentPackets? = null,
    val fragmentLengthBytes: NumericRange? = null,
    val fragmentIntervalMillis: NumericRange? = null,
    val fragmentMaxSplit: NumericRange? = null,
    val muxEnabled: Boolean? = null,
    val tcpConcurrency: Int? = null,
    val xudpConcurrency: Int? = null,
    val udp443: Udp443? = null,
    val fingerprint: Fingerprint? = null,
    val udpNoiseEnabled: Boolean? = null,
    val udpNoiseCount: Int? = null,
)

data class ScreenInformation(
    val events: List<ScreenEvent> = emptyList(),
    val applied: AppliedParameters? = null,
    val startRejection: String? = null,
)

internal fun subscriptionEvent(phase: SubscriptionPhase): ScreenEvent? = when (phase) {
    SubscriptionPhase.LOADING -> ScreenEvent.SUBSCRIPTION_LOADING
    SubscriptionPhase.READY -> ScreenEvent.SUBSCRIPTION_READY
    SubscriptionPhase.FAILED, SubscriptionPhase.NO_SUPPORTED -> ScreenEvent.SUBSCRIPTION_FAILED
    SubscriptionPhase.CANCELLED -> ScreenEvent.SUBSCRIPTION_CANCELLED
    SubscriptionPhase.IDLE -> null
}

internal fun searchEvent(phase: SearchPhase): ScreenEvent? = when (phase) {
    SearchPhase.RUNNING -> ScreenEvent.SEARCH_STARTING
    SearchPhase.CANCELLING -> ScreenEvent.SEARCH_CANCEL_REQUESTED
    SearchPhase.PAUSED -> ScreenEvent.PAUSED
    SearchPhase.CANCELLED -> ScreenEvent.CANCELLED
    SearchPhase.COMPLETE -> ScreenEvent.COMPLETE
    SearchPhase.FAILED -> ScreenEvent.FAILED
    SearchPhase.INTERRUPTED -> ScreenEvent.SEARCH_INTERRUPTED
    SearchPhase.IDLE, SearchPhase.PAUSING -> null
}

internal fun searchOutcomeEvent(outcome: SearchStartV3, state: SearchUiState): ScreenEvent? = when (outcome) {
    SearchStartV3.ACCEPTED -> null
    SearchStartV3.BUSY -> ScreenEvent.SEARCH_BUSY
    SearchStartV3.REJECTED -> if (state.startRejection == null && state.phase != SearchPhase.INTERRUPTED) ScreenEvent.SEARCH_REJECTED else null
    SearchStartV3.UNKNOWN -> if (state.phase != SearchPhase.INTERRUPTED) ScreenEvent.SEARCH_UNKNOWN else null
}

internal fun subscriptionOutcomeEvent(outcome: SubscriptionStartV1, phase: SubscriptionPhase): ScreenEvent? = when (outcome) {
    SubscriptionStartV1.ACCEPTED -> null
    SubscriptionStartV1.BUSY -> ScreenEvent.SUBSCRIPTION_BUSY
    SubscriptionStartV1.REJECTED -> if (phase != SubscriptionPhase.FAILED) ScreenEvent.SUBSCRIPTION_FAILED else null
}

internal fun workerOpenEvent(reason: WorkerCandidateFailureV2): ScreenEvent = when (reason) {
    WorkerCandidateFailureV2.CONFIG_REJECTED -> ScreenEvent.WORKER_CONFIG_REJECTED
    WorkerCandidateFailureV2.CORE_START_FAILED -> ScreenEvent.WORKER_START_FAILED
    WorkerCandidateFailureV2.CORE_START_TIMEOUT -> ScreenEvent.WORKER_START_TIMEOUT
    WorkerCandidateFailureV2.CORE_EXITED_BEFORE_READY -> ScreenEvent.WORKER_EXITED_EARLY
}

internal fun workerFatalEvent(reason: WorkerFatalV2): ScreenEvent = when (reason) {
    WorkerFatalV2.BUSY -> ScreenEvent.WORKER_BUSY
    WorkerFatalV2.OWNER_LOST -> ScreenEvent.WORKER_OWNER_LOST
    WorkerFatalV2.CAPABILITY_UNAVAILABLE -> ScreenEvent.WORKER_CAPABILITY_UNAVAILABLE
    WorkerFatalV2.UNKNOWN_DELIVERY -> ScreenEvent.WORKER_DELIVERY_UNKNOWN
    WorkerFatalV2.WORKER_DIED -> ScreenEvent.WORKER_DIED
    WorkerFatalV2.CLEANUP_INCOMPLETE -> ScreenEvent.WORKER_CLEANUP_INCOMPLETE
}

internal fun eventText(event: ScreenEvent): String = when (event) {
    ScreenEvent.READINESS_READY -> "readiness:ready — На момент проверки подбор доступен. Подтвердите выбор и начните подбор."
    ScreenEvent.READINESS_SELECT_REQUIRED -> "readiness:select_required — Выберите серверы и подтвердите выбор."
    ScreenEvent.READINESS_BUSY -> "readiness:busy — Другая операция ещё выполняется. Дождитесь её завершения и проверьте снова."
    ScreenEvent.READINESS_UNKNOWN -> "readiness:unknown — Безопасность повторного запуска не подтверждена. Проверьте состояние приложения и повторите проверку."
    ScreenEvent.READINESS_REBOOT_REQUIRED -> "readiness:unknown — Завершение предыдущей операции пока не подтверждено."
    ScreenEvent.READINESS_RECOVERY_UNAVAILABLE -> "readiness:recovery_unavailable — Не удалось подтвердить безопасное восстановление. Сохраните данные приложения и обратитесь в поддержку."
    ScreenEvent.SUBSCRIPTION_LOADING -> "Загрузка подписки"
    ScreenEvent.SUBSCRIPTION_READY -> "Подписка обработана"
    ScreenEvent.SUBSCRIPTION_FAILED -> "Не удалось обработать подписку"
    ScreenEvent.SUBSCRIPTION_BUSY -> "Дождитесь окончания загрузки подписки"
    ScreenEvent.SUBSCRIPTION_CANCELLED -> "Загрузка подписки отменена"
    ScreenEvent.SELECTION_SAVED -> "Выбор серверов сохранён"
    ScreenEvent.SELECTION_FAILED -> "Не удалось сохранить выбор серверов"
    ScreenEvent.VPN_PERMISSION_DENIED -> "Подключение VPN не разрешено"
    ScreenEvent.VPN_START_UNAVAILABLE -> "Сначала завершите подбор и дождитесь сохранения результата"
    ScreenEvent.VPN_SERVICE_START_FAILED -> "Не удалось запустить VPN-сервис"
    ScreenEvent.SEARCH_STARTING -> "Запуск подбора"
    ScreenEvent.SEARCH_BUSY -> "Подбор временно занят"
    ScreenEvent.SEARCH_REJECTED -> "Не удалось начать подбор"
    ScreenEvent.SEARCH_UNKNOWN -> "Результат запуска подбора неизвестен"
    ScreenEvent.SEARCH_INTERRUPTED -> "Подбор прерван; повторный запуск заблокирован"
    ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE -> "search_init:network_policy_unavailable — Не удалось подтвердить подходящую сеть; проверьте настройки сети и повторите подбор."
    ScreenEvent.SEARCH_INIT_NETWORK_CHECK_FAILED -> "search_init:network_check_failed — Не удалось проверить состояние сети; проверьте настройки сети и повторите подбор."
    ScreenEvent.SEARCH_INIT_NETWORK_CALLBACK_EVENT -> "search_init:network_callback_event — Во время проверки получено событие сети; повторите подбор."
    ScreenEvent.SEARCH_INIT_NETWORK_NON_DIRECT -> "search_init:network_non_direct — Android не подтвердил сеть без VPN; проверьте настройки сети и VPN, затем повторите подбор."
    ScreenEvent.SEARCH_INIT_NETWORK_BLOCKED -> "search_init:network_blocked — Android сообщил о блокировке доступа к сети; проверьте настройки сети и повторите подбор."
    ScreenEvent.SEARCH_INIT_PROFILES_FAILED -> "search_init:profiles_failed — Не удалось прочитать выбор; проверьте подписку и выбор серверов."
    ScreenEvent.SEARCH_INIT_CORE_IDENTITY_FAILED -> "search_init:core_identity_failed — Не удалось проверить модуль; переустановите совместимую сборку."
    ScreenEvent.SEARCH_INIT_MANIFEST_FAILED -> "search_init:manifest_failed — Не удалось подготовить параметры; проверьте подписку."
    ScreenEvent.SEARCH_INIT_NETWORK_UNKNOWN -> "search_init:network_unknown — Не удалось подтвердить состояние сети или владение; повторный запуск заблокирован."
    ScreenEvent.SEARCH_INIT_SESSION_FAILED -> "search_init:session_failed — Не удалось создать сессию; проверьте состояние приложения."
    ScreenEvent.WORKER_ADMISSION_FAILED -> "worker_admission:failed — Не удалось подготовить worker; проверьте состояние приложения."
    ScreenEvent.WORKER_BIND_FAILED -> "worker_bind:failed — Не удалось запустить worker; проверьте состояние приложения."
    ScreenEvent.WORKER_CALL_FAILED -> "worker_call:failed — Вызов worker завершился ошибкой; проверьте состояние подбора."
    ScreenEvent.WORKER_CALL_INVALID -> "worker_call:invalid — Вызов отклонён; проверьте состояние подбора."
    ScreenEvent.WORKER_CALL_STATE -> "worker_call:state — Worker не готов; проверьте состояние подбора."
    ScreenEvent.WORKER_CALL_DEADLINE -> "worker_call:deadline — Истекло время вызова worker."
    ScreenEvent.WORKER_CALL_CANCELLED -> "worker_call:cancelled — Worker сообщил об отмене вызова."
    ScreenEvent.WORKER_CANCEL_FAILED -> "worker_cancel:unknown — Отмена worker не подтверждена; повторный запуск заблокирован."
    ScreenEvent.WORKER_CLOSE_FAILED -> "worker_close:unknown — Завершение worker не подтверждено; повторный запуск заблокирован."
    ScreenEvent.WORKER_SERVICE_BUSY -> "worker_service:busy — Worker занят; проверьте состояние подбора."
    ScreenEvent.WORKER_SERVICE_UNAVAILABLE -> "worker_service:unavailable — Возможность worker недоступна; проверьте совместимость."
    ScreenEvent.WORKER_SERVICE_DEADLINE -> "worker_service:deadline — Истекло время запуска worker."
    ScreenEvent.WORKER_SERVICE_CANCELLED -> "worker_service:cancelled — Worker сообщил об отмене запуска."
    ScreenEvent.WORKER_BUSY -> "worker_open:busy — Worker занят; дождитесь завершения операции."
    ScreenEvent.WORKER_OWNER_LOST -> "worker_open:owner_lost — Потеряно владение; повторный запуск заблокирован."
    ScreenEvent.WORKER_CAPABILITY_UNAVAILABLE -> "worker_open:capability_unavailable — Нужная возможность недоступна; проверьте совместимость сборки."
    ScreenEvent.WORKER_DELIVERY_UNKNOWN -> "worker_open:delivery_unknown — Результат передачи неизвестен; повторный запуск заблокирован."
    ScreenEvent.WORKER_DIED -> "worker_open:worker_died — Worker завершился; проверьте состояние приложения."
    ScreenEvent.WORKER_CLEANUP_INCOMPLETE -> "worker_open:cleanup_incomplete — Очистка не подтверждена; повторный запуск заблокирован."
    ScreenEvent.WORKER_CONFIG_REJECTED -> "worker_open:config_rejected — Конфигурация отклонена; проверьте выбранные серверы."
    ScreenEvent.WORKER_START_FAILED -> "worker_open:core_start_failed — Не удалось запустить ядро; проверьте состояние приложения."
    ScreenEvent.WORKER_START_TIMEOUT -> "worker_open:core_start_timeout — Превышено время запуска; проверьте состояние подбора."
    ScreenEvent.WORKER_EXITED_EARLY -> "worker_open:core_exited_before_ready — Ядро остановилось до готовности; проверьте конфигурацию."
    ScreenEvent.SEARCH_CANCEL_REQUESTED -> "Отмена подбора"
    ScreenEvent.SEARCH_STARTED -> "Подбор начат"
    ScreenEvent.ATTEMPT_STARTED -> "Попытка начата"
    ScreenEvent.ATTEMPT_FINISHED -> "Попытка завершена"
    ScreenEvent.PAUSED -> "Подбор приостановлен"
    ScreenEvent.CANCELLED -> "Подбор отменён"
    ScreenEvent.COMPLETE -> "Подбор завершён"
    ScreenEvent.FAILED -> "Подбор остановлен из-за ошибки"
}
