package com.seraf.vpnsweep

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.searchui.ScreenInformation
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

    private val config = """{"version":1,"values":{
        "fragment":{"value":"true","state":"value"},"packets":{"value":"tlshello","state":"value"},
        "length":{"value":"50-100","state":"value"},"interval":{"value":"10","state":"value"},"maxSplit":{"value":"1-5","state":"value"},
        "mux":{"value":"true","state":"value"},"tcpConcurrency":{"value":"8","state":"default"},"xudpConcurrency":{"value":"0","state":"value"},"quic":{"value":"reject","state":"value"},
        "fingerprint":{"value":"firefox","state":"value"},"noise":{"value":"true","state":"value"},"noises":{"value":"2","state":"value"}
    }}""".toByteArray()

    @Test fun nativeProjectionIncludesInheritedSettings(): Unit {
        val saved = """{"vpnSweepSelection":{},"outbounds":[{"tag":"candidate","protocol":"freedom","streamSettings":{"security":"tls","tlsSettings":{"fingerprint":"firefox"}},"mux":{"enabled":false}}]}""".toByteArray()
        val values = checkNotNull(parseAppliedParameters(Mobile.appliedParametersJSON(saved))).effectiveFields!!
        assertEquals("firefox", values["fingerprint"]!!.value)
        assertEquals("false", values["mux"]!!.value)
        assertEquals("not_applicable", values["packets"]!!.state)
    }

    @Test fun readsOnlyVersionedNativeProjection(): Unit {
        val values = checkNotNull(parseAppliedParameters(config)).effectiveFields!!
        assertEquals("firefox", values["fingerprint"]!!.value)
        assertEquals("default", values["tcpConcurrency"]!!.state)
        assertEquals("2", values["noises"]!!.value)
        assertNull(parseAppliedParameters("{}".toByteArray()))
        assertNull(parseAppliedParameters(config.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":2").toByteArray()))
        assertNull(parseAppliedParameters(config.toString(Charsets.UTF_8).replace("\"state\":\"value\"", "\"state\":\"invalid\"").toByteArray()))
        assertNull(parseAppliedParameters(config.toString(Charsets.UTF_8).replace("\"noises\":", "\"secret\":").toByteArray()))
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
