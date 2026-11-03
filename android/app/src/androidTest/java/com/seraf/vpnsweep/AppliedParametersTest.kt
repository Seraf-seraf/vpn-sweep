package com.seraf.vpnsweep

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seraf.vpnsweep.searchui.Fingerprint
import com.seraf.vpnsweep.searchui.FragmentPackets
import com.seraf.vpnsweep.searchui.NumericRange
import com.seraf.vpnsweep.searchui.ScreenInformation
import com.seraf.vpnsweep.searchui.Udp443
import com.seraf.vpnsweep.searchui.parseAppliedParameters
import com.seraf.vpnsweep.ui.InformationPanel
import com.seraf.vpnsweep.ui.SweepTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppliedParametersTest {
    @get:Rule val compose = createComposeRule()

    private val config = """{"vpnSweepSelection":{"fragment":true,"packets":"tlshello","length":"50-100","interval":"10","maxSplit":"1-5","mux":true,"tcpConcurrency":8,"xudpConcurrency":0,"quic":"reject","fingerprint":"firefox","noise":true,"noises":[{},{}]},"outbounds":[
        {"tag":"candidate","streamSettings":{"security":"reality","realitySettings":{"fingerprint":"firefox"},"sockopt":{"dialerProxy":"endpoint-fragment"}},
         "mux":{"enabled":true,"concurrency":8,"xudpConcurrency":0,"xudpProxyUDP443":"reject"}},
        {"tag":"endpoint-fragment","settings":{"fragment":{"packets":"tlshello","length":"50-100","interval":"10","maxSplit":"1-5"},"noises":[{},{}]}}
    ]}""".toByteArray()

    @Test fun readsEffectiveRenderedValues(): Unit {
        val values = checkNotNull(parseAppliedParameters(config))
        assertEquals(true, values.fragmentEnabled)
        assertEquals(FragmentPackets.TLS_HELLO, values.fragmentPackets)
        assertEquals(NumericRange.Pair(50, 100), values.fragmentLengthBytes)
        assertEquals(NumericRange.Single(10), values.fragmentIntervalMillis)
        assertEquals(NumericRange.Pair(1, 5), values.fragmentMaxSplit)
        assertEquals(true, values.muxEnabled)
        assertEquals(8, values.tcpConcurrency)
        assertEquals(0, values.xudpConcurrency)
        assertEquals(Udp443.REJECT, values.udp443)
        assertEquals(Fingerprint.FIREFOX, values.fingerprint)
        assertEquals(true, values.udpNoiseEnabled)
        assertEquals(2, values.udpNoiseCount)
    }

    @Test fun missingValuesAndInvalidResultsAreDistinct(): Unit {
        val minimal = """{"vpnSweepSelection":{"mux":false,"fingerprint":"chrome"},"outbounds":[{"tag":"candidate","mux":{"enabled":false},"streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"chrome"}}}]}""".toByteArray()
        val values = checkNotNull(parseAppliedParameters(minimal))
        assertEquals(false, values.muxEnabled)
        assertEquals(Fingerprint.CHROME, values.fingerprint)
        assertNull(values.fragmentEnabled)
        assertNull(values.udpNoiseCount)
        val emptyFingerprint = """{"vpnSweepSelection":{"mux":false,"fingerprint":""},"outbounds":[{"tag":"candidate"}]}""".toByteArray()
        val emptyFingerprintValues = checkNotNull(parseAppliedParameters(emptyFingerprint))
        assertNull(emptyFingerprintValues.fingerprint)
        assertEquals(false, emptyFingerprintValues.muxEnabled)
        assertNull(parseAppliedParameters("""{"vpnSweepSelection":{"mux":"yes"},"outbounds":[{"tag":"candidate"}]}""".toByteArray()))
        assertNull(parseAppliedParameters("""{"vpnSweepSelection":{"unexpected":true},"outbounds":[{"tag":"candidate"}]}""".toByteArray()))
        val inherited = """{"vpnSweepSelection":{},"outbounds":[{"tag":"candidate","streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"firefox"}}}]}""".toByteArray()
        assertNull(checkNotNull(parseAppliedParameters(inherited)).fingerprint)
        val legacy = """{"outbounds":[{"tag":"candidate","streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"firefox"}}}]}""".toByteArray()
        assertNull(checkNotNull(parseAppliedParameters(legacy)).fingerprint)
    }

    @Test fun parametersAreVisibleInScrollablePanel(): Unit {
        val values = checkNotNull(parseAppliedParameters(config))
        compose.setContent { SweepTheme { InformationPanel(initialTab = 1, information = ScreenInformation(applied = values), paneHeight = 400.dp) } }
        compose.onNodeWithText("Fragment").performClick()
        compose.onNodeWithText("50-100 байт").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mux").performScrollTo().performClick()
        compose.onNodeWithText("reject").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TLS fingerprint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("firefox").assertIsDisplayed()
    }
}
