package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.searchui.NumericRange
import com.seraf.vpnsweep.searchui.ScreenInformation
import com.seraf.vpnsweep.searchui.eventText

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
    val events = information.events.map { EventLogEntry("", "INFO", eventText(it)) }
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
