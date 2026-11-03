package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.searchui.NumericRange
import com.seraf.vpnsweep.searchui.ScreenInformation
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.SearchProgressEvent
import com.seraf.vpnsweep.searchui.eventText
import com.seraf.vpnsweep.searchprotocol.SearchProgressKindV1
import com.seraf.vpnsweep.searchprotocol.SearchProgressOutcomeV1
import com.seraf.vpnsweep.searchstate.SearchStartStage
import com.seraf.vpnsweep.searchstate.SearchStartReason

internal data class EventLogEntry(val time: String, val level: String, val message: String)
internal data class ParameterValue(val label: String, val value: String)
internal data class ParameterGroupValues(val summary: String, val fields: List<ParameterValue>)
internal data class AppliedParameterValues(
    val fragment: ParameterGroupValues? = null,
    val mux: ParameterGroupValues? = null,
    val fingerprint: ParameterGroupValues? = null,
    val udpNoise: ParameterGroupValues? = null,
)
internal data class ScreenPresentation(val events: List<EventLogEntry>, val applied: AppliedParameterValues?)

internal fun presentInformation(information: ScreenInformation): ScreenPresentation {
    val allowedCodes = SearchStartStage.entries.flatMap { stage -> SearchStartReason.entries.map { reason -> "${stage.code}:${reason.code}" } }.toSet()
    val events = information.events.map { entry ->
        when (entry) {
            is ScreenEvent -> EventLogEntry("", eventLevel(entry), eventText(entry))
            is SearchProgressEvent -> progressEntry(entry)
        }
    } +
        listOfNotNull(information.startRejection?.takeIf { it in allowedCodes }?.let {
            EventLogEntry("", "ERROR", "Подбор не запущен ($it)")
        })
    val applied = information.applied?.let { values ->
        AppliedParameterValues(
            fragment = ParameterGroupValues(boolValue(values.fragmentEnabled), listOf(
                ParameterValue("Фрагментация", boolValue(values.fragmentEnabled)),
                ParameterValue("Пакеты", values.fragmentPackets?.wireValue ?: MISSING),
                ParameterValue("Длина", rangeValue(values.fragmentLengthBytes, " байт")),
                ParameterValue("Интервал", rangeValue(values.fragmentIntervalMillis, " мс")),
                ParameterValue("Макс. частей", rangeValue(values.fragmentMaxSplit, "")),
            )),
            mux = ParameterGroupValues(boolValue(values.muxEnabled), listOf(
                ParameterValue("Mux", boolValue(values.muxEnabled)),
                ParameterValue("TCP concurrency", values.tcpConcurrency?.toString() ?: MISSING),
                ParameterValue("XUDP concurrency", values.xudpConcurrency?.toString() ?: MISSING),
                ParameterValue("UDP 443", values.udp443?.wireValue ?: MISSING),
            )),
            fingerprint = ParameterGroupValues(values.fingerprint?.wireValue ?: MISSING, listOf(
                ParameterValue("Fingerprint", values.fingerprint?.wireValue ?: MISSING),
            )),
            udpNoise = ParameterGroupValues(boolValue(values.udpNoiseEnabled), listOf(
                ParameterValue("Шум", boolValue(values.udpNoiseEnabled)),
                ParameterValue("Записи", values.udpNoiseCount?.toString() ?: MISSING),
            )),
        )
    }
    return ScreenPresentation(events, applied)
}

private fun progressEntry(entry: SearchProgressEvent): EventLogEntry {
    val value = entry.progress
    val result = when (value.outcome) {
        SearchProgressOutcomeV1.NONE -> ""
        SearchProgressOutcomeV1.RUNNING -> "выполняется"
        SearchProgressOutcomeV1.PASS -> "успешно"
        SearchProgressOutcomeV1.FAILED -> "не пройдена"
        SearchProgressOutcomeV1.INCONCLUSIVE -> "неоднозначный результат"
        SearchProgressOutcomeV1.BLOCKED -> "недоступна"
        SearchProgressOutcomeV1.SKIPPED -> "пропущена"
        SearchProgressOutcomeV1.BUDGET -> "достигнут лимит"
        SearchProgressOutcomeV1.REQUEST_FAILED -> "ошибка запроса (request_failed)"
        SearchProgressOutcomeV1.SITE_CONSENT -> "сайт требует согласия (site_consent)"
        SearchProgressOutcomeV1.INTERRUPTED -> "прервана"
        SearchProgressOutcomeV1.TIMEOUT -> "истекло время ожидания"
    }
    val prefix = "Попытка ${value.attempt}, раунд ${value.round}"
    val message = when (value.kind) {
        SearchProgressKindV1.ATTEMPT_STARTED -> "Попытка ${value.attempt}: начата"
        SearchProgressKindV1.ATTEMPT_FINISHED -> "Попытка ${value.attempt}: завершена — $result"
        SearchProgressKindV1.CANDIDATE_SKIPPED -> "Вариант пропущен"
        SearchProgressKindV1.CORE_STARTING -> "$prefix: запуск ядра"
        SearchProgressKindV1.CORE_READY -> "$prefix: ядро готово"
        SearchProgressKindV1.CORE_STOPPING -> "$prefix: остановка ядра"
        SearchProgressKindV1.CORE_STOPPED -> if (value.outcome == SearchProgressOutcomeV1.PASS) "$prefix: ядро остановлено" else "$prefix: ошибка остановки ядра"
        SearchProgressKindV1.PROBE_STARTED -> "$prefix: проверка ${value.probe} начата"
        SearchProgressKindV1.PROBE_FINISHED -> if (value.probe == 0) "$prefix: ошибка ядра" else "$prefix: проверка ${value.probe} — $result"
        SearchProgressKindV1.MEDIA_STARTED -> "$prefix: проверка видео начата"
        SearchProgressKindV1.BUDGET_REACHED -> "Подбор приостановлен: достигнут лимит"
        SearchProgressKindV1.SAVED_RESULT_SKIPPED -> "Сохранённый вариант пропущен; поиск продолжается"
    }
    return EventLogEntry("", "INFO", message)
}

private const val MISSING = "Не задан"
private fun boolValue(value: Boolean?): String = when (value) { true -> "Вкл"; false -> "Выкл"; null -> MISSING }
private fun rangeValue(value: NumericRange?, unit: String): String = when (value) {
    is NumericRange.Single -> "${value.value}$unit"
    is NumericRange.Pair -> "${value.first}-${value.last}$unit"
    null -> MISSING
}

internal fun eventLevel(event: ScreenEvent): String = when (event) {
    ScreenEvent.READINESS_SELECT_REQUIRED, ScreenEvent.READINESS_BUSY, ScreenEvent.READINESS_UNKNOWN, ScreenEvent.READINESS_REBOOT_REQUIRED, ScreenEvent.READINESS_RECOVERY_UNAVAILABLE, ScreenEvent.SUBSCRIPTION_BUSY, ScreenEvent.SEARCH_BUSY, ScreenEvent.SEARCH_REJECTED, ScreenEvent.WORKER_SERVICE_CANCELLED, ScreenEvent.WORKER_CALL_CANCELLED -> "WARN"
    ScreenEvent.SUBSCRIPTION_FAILED, ScreenEvent.SELECTION_FAILED, ScreenEvent.SUPERVISOR_RECOVERY_UNKNOWN, ScreenEvent.VPN_PERMISSION_DENIED,
    ScreenEvent.VPN_START_UNAVAILABLE, ScreenEvent.VPN_SERVICE_START_FAILED, ScreenEvent.SEARCH_UNKNOWN,
    ScreenEvent.SEARCH_INTERRUPTED, ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE,
    ScreenEvent.SEARCH_INIT_NETWORK_CHECK_FAILED, ScreenEvent.SEARCH_INIT_NETWORK_CALLBACK_EVENT,
    ScreenEvent.SEARCH_INIT_NETWORK_NON_DIRECT, ScreenEvent.SEARCH_INIT_NETWORK_BLOCKED,
    ScreenEvent.SEARCH_INIT_PROFILES_FAILED, ScreenEvent.SEARCH_INIT_CORE_IDENTITY_FAILED,
    ScreenEvent.SEARCH_INIT_MANIFEST_FAILED, ScreenEvent.SEARCH_INIT_NETWORK_UNKNOWN, ScreenEvent.SEARCH_INIT_SESSION_FAILED,
    ScreenEvent.WORKER_ADMISSION_FAILED, ScreenEvent.WORKER_BIND_FAILED, ScreenEvent.WORKER_CALL_FAILED, ScreenEvent.WORKER_CALL_INVALID, ScreenEvent.WORKER_CALL_STATE, ScreenEvent.WORKER_CALL_DEADLINE, ScreenEvent.WORKER_CANCEL_FAILED, ScreenEvent.WORKER_CLOSE_FAILED,
    ScreenEvent.WORKER_SERVICE_BUSY, ScreenEvent.WORKER_SERVICE_UNAVAILABLE, ScreenEvent.WORKER_SERVICE_DEADLINE, ScreenEvent.WORKER_BUSY, ScreenEvent.WORKER_OWNER_LOST,
    ScreenEvent.WORKER_CAPABILITY_UNAVAILABLE, ScreenEvent.WORKER_DELIVERY_UNKNOWN, ScreenEvent.WORKER_DIED,
    ScreenEvent.WORKER_CLEANUP_INCOMPLETE, ScreenEvent.WORKER_CONFIG_REJECTED, ScreenEvent.WORKER_START_FAILED,
    ScreenEvent.WORKER_START_TIMEOUT, ScreenEvent.WORKER_EXITED_EARLY, ScreenEvent.FAILED -> "ERROR"
    else -> "INFO"
}
