package com.seraf.vpnsweep.searchui

import com.seraf.vpnsweep.searchstate.SearchPhase
import com.seraf.vpnsweep.searchstate.SubscriptionPhase
import com.seraf.vpnsweep.searchstate.SearchUiState
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1

enum class ScreenEvent { SUBSCRIPTION_LOADING, SUBSCRIPTION_READY, SUBSCRIPTION_FAILED, SUBSCRIPTION_BUSY, SUBSCRIPTION_CANCELLED, SELECTION_SAVED, SELECTION_FAILED, SEARCH_STARTING, SEARCH_STARTED, SEARCH_BUSY, SEARCH_REJECTED, SEARCH_UNKNOWN, SEARCH_INTERRUPTED, SEARCH_CANCEL_REQUESTED, ATTEMPT_STARTED, ATTEMPT_FINISHED, PAUSED, CANCELLED, COMPLETE, FAILED }
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

internal fun eventText(event: ScreenEvent): String = when (event) {
    ScreenEvent.SUBSCRIPTION_LOADING -> "Загрузка подписки"
    ScreenEvent.SUBSCRIPTION_READY -> "Подписка обработана"
    ScreenEvent.SUBSCRIPTION_FAILED -> "Не удалось обработать подписку"
    ScreenEvent.SUBSCRIPTION_BUSY -> "Дождитесь окончания загрузки подписки"
    ScreenEvent.SUBSCRIPTION_CANCELLED -> "Загрузка подписки отменена"
    ScreenEvent.SELECTION_SAVED -> "Выбор серверов сохранён"
    ScreenEvent.SELECTION_FAILED -> "Не удалось сохранить выбор серверов"
    ScreenEvent.SEARCH_STARTING -> "Запуск подбора"
    ScreenEvent.SEARCH_BUSY -> "Подбор временно занят"
    ScreenEvent.SEARCH_REJECTED -> "Не удалось начать подбор"
    ScreenEvent.SEARCH_UNKNOWN -> "Результат запуска подбора неизвестен"
    ScreenEvent.SEARCH_INTERRUPTED -> "Подбор прерван; повторный запуск заблокирован"
    ScreenEvent.SEARCH_CANCEL_REQUESTED -> "Отмена подбора"
    ScreenEvent.SEARCH_STARTED -> "Подбор начат"
    ScreenEvent.ATTEMPT_STARTED -> "Попытка начата"
    ScreenEvent.ATTEMPT_FINISHED -> "Попытка завершена"
    ScreenEvent.PAUSED -> "Подбор приостановлен"
    ScreenEvent.CANCELLED -> "Подбор отменён"
    ScreenEvent.COMPLETE -> "Подбор завершён"
    ScreenEvent.FAILED -> "Подбор остановлен из-за ошибки"
}
