package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.searchui.NumericRange
import com.seraf.vpnsweep.searchui.ScreenInformation
import com.seraf.vpnsweep.searchui.ScreenEvent
import com.seraf.vpnsweep.searchui.eventText
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
    val events = information.events.map { EventLogEntry("", eventLevel(it), eventText(it)) } +
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

private const val MISSING = "Нет данных"
private fun boolValue(value: Boolean?): String = when (value) { true -> "Вкл"; false -> "Выкл"; null -> MISSING }
private fun rangeValue(value: NumericRange?, unit: String): String = when (value) {
    is NumericRange.Single -> "${value.value}$unit"
    is NumericRange.Pair -> "${value.first}-${value.last}$unit"
    null -> MISSING
}

internal fun eventLevel(event: ScreenEvent): String = when (event) {
    ScreenEvent.READINESS_SELECT_REQUIRED, ScreenEvent.READINESS_BUSY, ScreenEvent.READINESS_UNKNOWN, ScreenEvent.SUBSCRIPTION_BUSY, ScreenEvent.SEARCH_BUSY, ScreenEvent.SEARCH_REJECTED, ScreenEvent.WORKER_SERVICE_CANCELLED, ScreenEvent.WORKER_CALL_CANCELLED -> "WARN"
    ScreenEvent.SUBSCRIPTION_FAILED, ScreenEvent.SELECTION_FAILED, ScreenEvent.SEARCH_UNKNOWN,
    ScreenEvent.SEARCH_INTERRUPTED, ScreenEvent.SEARCH_INIT_NETWORK_POLICY_UNAVAILABLE,
    ScreenEvent.SEARCH_INIT_PROFILES_FAILED, ScreenEvent.SEARCH_INIT_CORE_IDENTITY_FAILED,
    ScreenEvent.SEARCH_INIT_MANIFEST_FAILED, ScreenEvent.SEARCH_INIT_NETWORK_UNKNOWN, ScreenEvent.SEARCH_INIT_SESSION_FAILED,
    ScreenEvent.WORKER_ADMISSION_FAILED, ScreenEvent.WORKER_BIND_FAILED, ScreenEvent.WORKER_CALL_FAILED, ScreenEvent.WORKER_CALL_INVALID, ScreenEvent.WORKER_CALL_STATE, ScreenEvent.WORKER_CALL_DEADLINE, ScreenEvent.WORKER_CANCEL_FAILED, ScreenEvent.WORKER_CLOSE_FAILED,
    ScreenEvent.WORKER_SERVICE_BUSY, ScreenEvent.WORKER_SERVICE_UNAVAILABLE, ScreenEvent.WORKER_SERVICE_DEADLINE, ScreenEvent.WORKER_BUSY, ScreenEvent.WORKER_OWNER_LOST,
    ScreenEvent.WORKER_CAPABILITY_UNAVAILABLE, ScreenEvent.WORKER_DELIVERY_UNKNOWN, ScreenEvent.WORKER_DIED,
    ScreenEvent.WORKER_CLEANUP_INCOMPLETE, ScreenEvent.WORKER_CONFIG_REJECTED, ScreenEvent.WORKER_START_FAILED,
    ScreenEvent.WORKER_START_TIMEOUT, ScreenEvent.WORKER_EXITED_EARLY, ScreenEvent.FAILED -> "ERROR"
    else -> "INFO"
}
