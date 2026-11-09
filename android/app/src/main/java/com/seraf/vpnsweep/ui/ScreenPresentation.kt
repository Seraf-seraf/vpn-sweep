package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.searchui.TimedLogEvent
import com.seraf.vpnsweep.searchui.VpnConnectionEvent
import com.seraf.vpnsweep.searchui.XrayLogEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val events = information.events.map(::presentEvent).filter { it.message.isNotEmpty() } +
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
    val effective = information.applied?.effectiveFields?.let { fields ->
        fun field(key: String, unit: String = ""): String {
            val entry = fields[key] ?: return "Не удалось прочитать"
            if (entry.state == "not_applicable") return "Не применяется"
            val value = when (entry.value) { "true" -> "Включён"; "false" -> "Выключен"; "all" -> "Все пакеты"; else -> entry.value }
            if (entry.state == "default") return if (value.isEmpty()) "По умолчанию" else "$value$unit (по умолчанию)"
            return value + unit
        }
        fun group(summary: String, vararg keys: Pair<String, String>): ParameterGroupValues = ParameterGroupValues(field(summary), keys.map { (label, key) ->
            val unit = when (key) { "length" -> " байт"; "interval" -> " мс"; else -> "" }
            val inactive = (summary == "mux" && fields["mux"]?.value == "false" && key != "mux")
            ParameterValue(label, field(key, unit) + if (inactive && fields[key]?.state != "not_applicable") " (не используется)" else "")
        })
        AppliedParameterValues(
            group("fragment", "Фрагментация" to "fragment", "Пакеты" to "packets", "Длина" to "length", "Интервал" to "interval", "Макс. частей" to "maxSplit"),
            group("mux", "Mux" to "mux", "TCP concurrency" to "tcpConcurrency", "XUDP concurrency" to "xudpConcurrency", "UDP 443" to "quic"),
            group("fingerprint", "Fingerprint" to "fingerprint"), group("noise", "Шум" to "noise", "Записи" to "noises"),
        )
    }
    return ScreenPresentation(events, effective ?: applied)
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
        SearchProgressOutcomeV1.SITE_CONSENT -> "сайт требует согласия пользователя (site_consent)"
        SearchProgressOutcomeV1.INTERRUPTED -> "прервана"
        SearchProgressOutcomeV1.TIMEOUT -> "истекло время ожидания"
        SearchProgressOutcomeV1.TLS_FAILURE -> "ошибка проверки TLS-сертификата"
        SearchProgressOutcomeV1.SITE_CHALLENGE -> "сайт требует подтверждения или ручной проверки (site_challenge)"
    }
    val server = value.serverName.ifEmpty { "сервер ${value.profile}" }
    val prefix = "Вариант ${value.attempt} · $server · повтор ${value.round} из 2"
    val site = when (value.probe) { 1 -> "Google"; 2 -> "YouTube"; 3 -> "GitHub"; 4 -> "UDP/DNS"; else -> "Соединение" }
    val response = when {
        value.httpStatus == 0 -> result
        value.outcome == SearchProgressOutcomeV1.PASS -> "HTTPS доступен, HTTP ${value.httpStatus}"
        else -> "HTTP ${value.httpStatus}, $result"
    }
    val message = when (value.kind) {
        SearchProgressKindV1.ATTEMPT_STARTED -> "Вариант ${value.attempt} · $server — проверка начата"
        SearchProgressKindV1.ATTEMPT_FINISHED -> "Вариант ${value.attempt} — $result"
        SearchProgressKindV1.PROBE_STARTED -> "$prefix · $site — соединение устанавливается"
        SearchProgressKindV1.PROBE_FINISHED -> "$prefix · $site — $response, ${value.durationMillis} мс"
        SearchProgressKindV1.CORE_STOPPED -> if (value.outcome == SearchProgressOutcomeV1.PASS) "" else "Вариант ${value.attempt} — ошибка завершения соединения"
        SearchProgressKindV1.BUDGET_REACHED -> "Подбор приостановлен: достигнут лимит"
        SearchProgressKindV1.SAVED_RESULT_SKIPPED -> "Сохранённый вариант пропущен; поиск продолжается"
        SearchProgressKindV1.MEDIA_STARTED -> "$prefix — проверка видео"
        else -> ""
    }
    val userInterstitial = value.httpStatus == 200 && value.outcome in setOf(SearchProgressOutcomeV1.SITE_CONSENT, SearchProgressOutcomeV1.SITE_CHALLENGE)
    val level = if (value.kind in setOf(SearchProgressKindV1.PROBE_FINISHED, SearchProgressKindV1.ATTEMPT_FINISHED, SearchProgressKindV1.CORE_STOPPED) &&
        value.outcome !in setOf(SearchProgressOutcomeV1.PASS, SearchProgressOutcomeV1.NONE) && !userInterstitial
    ) "ERROR" else "INFO"
    return EventLogEntry("", level, message)
}

private val journalTime = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun presentEvent(event: com.seraf.vpnsweep.searchui.ScreenLogEvent): EventLogEntry = when (event) {
    is TimedLogEvent -> presentEvent(event.event).copy(time = journalTime.format(Instant.ofEpochMilli(event.timeMillis).atZone(ZoneId.systemDefault())))
    is ScreenEvent -> EventLogEntry("", eventLevel(event), eventText(event))
    is SearchProgressEvent -> progressEntry(event)
    is VpnConnectionEvent -> EventLogEntry("", if (event.phase == "ERROR") "ERROR" else "INFO", event.message)
    is XrayLogEvent -> {
        val reason = when (event.code) {
            1L -> "Не удалось обработать соединение"
            2L -> "Истекло время ожидания соединения"
            3L -> "Соединение было сброшено"
            4L -> "Соединение было отклонено"
            5L -> "Не удалось разрешить доменное имя"
            6L -> "Ошибка проверки защищённого соединения"
            7L -> "Предупреждение при работе ядра"
            else -> "Ошибка соединения"
        }
        val hint = if (event.starting && event.code != 7L) " После запуска сервер может ответить не сразу." else ""
        EventLogEntry("", "WARN", "Xray: $reason.$hint")
    }
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
