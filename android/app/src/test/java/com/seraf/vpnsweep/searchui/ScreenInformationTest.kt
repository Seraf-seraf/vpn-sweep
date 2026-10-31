package com.seraf.vpnsweep.searchui

import com.seraf.vpnsweep.ui.presentInformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenInformationTest {
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
