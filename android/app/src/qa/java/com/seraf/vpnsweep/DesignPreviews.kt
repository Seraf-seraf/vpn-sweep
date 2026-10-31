package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.AppliedParameterValues
import com.seraf.vpnsweep.ui.EventLogEntry
import com.seraf.vpnsweep.ui.EventLogPanel
import com.seraf.vpnsweep.ui.ParameterGroupValues
import com.seraf.vpnsweep.ui.ParameterValue
import com.seraf.vpnsweep.ui.ParametersPanel
import com.seraf.vpnsweep.ui.ServerSelectionDialogContent
import com.seraf.vpnsweep.ui.SubscriptionDialogContent
import com.seraf.vpnsweep.ui.SubscriptionDialogStatus
import com.seraf.vpnsweep.ui.SweepColors
import com.seraf.vpnsweep.ui.SweepTheme
import com.android.tools.screenshot.PreviewTest
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// The following data exists only in the debug source set for design previews.
private val previewServers = listOf(
    ServerChoice("de", "Германия 01", "VLESS · Reality", true),
    ServerChoice("nl", "Нидерланды 02", "VLESS · Reality", true),
    ServerChoice("fi", "Финляндия 03", "VLESS · WS / TLS", true),
    ServerChoice("fr", "Франция 04", "Trojan · TLS", true),
    ServerChoice("se", "Швеция 05", "Shadowsocks", true),
    ServerChoice("unsupported", "Неподдерживаемая конфигурация", "Пример", false),
)
private val previewParameters = AppliedParameterValues(
    fragment = ParameterGroupValues(
        "Вкл",
        listOf(
            ParameterValue("Фрагментация", "Вкл"),
            ParameterValue("Пакеты", "tlshello"),
            ParameterValue("Длина", "128–256 байт"),
            ParameterValue("Интервал", "10–20 мс"),
            ParameterValue("Макс. частей", "64"),
        ),
    ),
    mux = ParameterGroupValues("Выкл", emptyList()),
    fingerprint = ParameterGroupValues("chrome", listOf(ParameterValue("Fingerprint", "chrome"))),
    udpNoise = ParameterGroupValues("Недоступен", emptyList()),
)

@Composable
private fun HomePreview(state: VpnState, selected: Boolean) {
    SweepTheme {
        VpnSweepScreen(
            state = state,
            selected = selected,
            importing = false,
            message = "",
            testing = false,
            checkResults = emptyList(),
            onPower = {},
            onImport = {},
            onRunChecks = {},
            onSubscription = {},
            onServers = {},
        )
    }
}

@PreviewTest
@Preview(name = "PREVIEW · главный экран", widthDp = 360, heightDp = 800)
@Composable private fun PreviewHome() = HomePreview(VpnState(), selected = false)

@PreviewTest
@Preview(name = "PREVIEW · подключение", widthDp = 360, heightDp = 800)
@Composable private fun PreviewConnecting() = HomePreview(VpnState(VpnPhase.CONNECTING, "Подключение…"), selected = true)

@PreviewTest
@Preview(name = "PREVIEW · VPN запущен", widthDp = 360, heightDp = 800)
@Composable private fun PreviewRunning() = HomePreview(VpnState(VpnPhase.RUNNING, "Туннель запущен"), selected = true)

@PreviewTest
@Preview(name = "PREVIEW · отключение", widthDp = 360, heightDp = 800)
@Composable private fun PreviewStopping() = HomePreview(VpnState(VpnPhase.STOPPING, "Отключение…"), selected = true)

@PreviewTest
@Preview(name = "PREVIEW · крупный шрифт", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable private fun PreviewLargeText() = HomePreview(VpnState(), selected = false)

@PreviewTest
@Preview(name = "PREVIEW · значок приложения", widthDp = 160, heightDp = 160)
@Composable
private fun PreviewLauncherIcon() {
    Box(Modifier.fillMaxSize().background(SweepColors.background), contentAlignment = Alignment.Center) {
        Box(Modifier.size(108.dp).clip(CircleShape).background(SweepColors.background)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · короткая подписка", widthDp = 320, heightDp = 120)
@Preview(name = "PREVIEW · короткая подписка, крупный шрифт", widthDp = 320, heightDp = 120, fontScale = 2f)
@Composable
private fun PreviewShortSubscription() {
    SweepTheme { SubscriptionDialogContent("", SubscriptionDialogStatus.Empty, {}, {}, {}) }
}

@PreviewTest
@Preview(name = "PREVIEW · подписка ниже высоты кнопок", widthDp = 320, heightDp = 79)
@Preview(name = "PREVIEW · подписка равна высоте кнопок", widthDp = 320, heightDp = 80)
@Preview(name = "PREVIEW · подписка выше высоты кнопок", widthDp = 320, heightDp = 81)
@Preview(name = "PREVIEW · подписка ниже минимума тела", widthDp = 320, heightDp = 135)
@Preview(name = "PREVIEW · подписка равна минимуму тела", widthDp = 320, heightDp = 136)
@Preview(name = "PREVIEW · подписка выше минимума тела", widthDp = 320, heightDp = 137)
@Composable
private fun PreviewSubscriptionFooterBoundary() {
    SweepTheme { SubscriptionDialogContent("", SubscriptionDialogStatus.Empty, {}, {}, {}) }
}

@PreviewTest
@Preview(name = "PREVIEW · короткий список серверов", widthDp = 320, heightDp = 180)
@Preview(name = "PREVIEW · короткий список со сводкой", widthDp = 320, heightDp = 250)
@Preview(name = "PREVIEW · короткий список, крупный шрифт", widthDp = 320, heightDp = 340, fontScale = 2f)
@Composable
private fun PreviewShortServers() {
    SweepTheme {
        Box(Modifier.fillMaxSize().background(SweepColors.background)) {
            ServerSelectionDialogContent(emptyList(), emptySet(), {}, {})
        }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · параметры", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewParameters() {
    SweepTheme {
        Column(
            Modifier.fillMaxSize().background(SweepColors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        ) { ParametersPanel(initialFragmentExpanded = true) }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · параметры, пример", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewParametersExample() {
    SweepTheme {
        Column(
            Modifier.fillMaxSize().background(SweepColors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        ) { ParametersPanel(initialFragmentExpanded = true, applied = previewParameters) }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · параметры, крупный шрифт", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun PreviewParametersLargeText() {
    SweepTheme {
        Column(
            Modifier.fillMaxSize().background(SweepColors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        ) { ParametersPanel(initialFragmentExpanded = true, applied = previewParameters) }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · журнал, пример", widthDp = 360, heightDp = 300)
@Composable
private fun PreviewEventLogExample() {
    SweepTheme {
        Column(Modifier.fillMaxSize().background(SweepColors.background).padding(24.dp)) {
            EventLogPanel(
                listOf(
                    EventLogEntry("21:01:00", "INFO", "Приложение готово"),
                    EventLogEntry("21:01:01", "INFO", "Подписка загружена"),
                    EventLogEntry("21:01:02", "INFO", "Выбрано серверов: 3"),
                    EventLogEntry("21:01:03", "INFO", "Ожидание подбора"),
                ),
            )
        }
    }
}

@Composable
private fun SubscriptionPreview(status: SubscriptionDialogStatus, link: String) {
    SweepTheme {
        Box(
            Modifier.fillMaxSize().background(SweepColors.background).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            SubscriptionDialogContent(link, status, {}, {}, {})
        }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · подписка пустая", widthDp = 360, heightDp = 640)
@Preview(name = "PREVIEW · подписка пустая, крупный шрифт", widthDp = 320, heightDp = 720, fontScale = 2f)
@Composable private fun PreviewSubscriptionEmpty() = SubscriptionPreview(SubscriptionDialogStatus.Empty, "")

@PreviewTest
@Preview(name = "PREVIEW · ссылка введена", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionInput() = SubscriptionPreview(SubscriptionDialogStatus.Input, "https://example.com/sub/demo")

@PreviewTest
@Preview(name = "PREVIEW · загрузка", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionLoading() = SubscriptionPreview(SubscriptionDialogStatus.Loading, "https://example.com/sub/demo")

@PreviewTest
@Preview(name = "PREVIEW · неверная ссылка", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionInvalid() = SubscriptionPreview(SubscriptionDialogStatus.InvalidUrl, "example")

@PreviewTest
@Preview(name = "PREVIEW · ошибка загрузки", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionNetworkError() = SubscriptionPreview(SubscriptionDialogStatus.NetworkError, "https://example.com/sub/demo")

@PreviewTest
@Preview(name = "PREVIEW · ошибка формата", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionMalformed() = SubscriptionPreview(SubscriptionDialogStatus.MalformedData, "https://example.com/sub/demo")

@PreviewTest
@Preview(name = "PREVIEW · загрузка недоступна", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionUnavailable() = SubscriptionPreview(SubscriptionDialogStatus.Unavailable, "https://example.com/sub/demo")

@PreviewTest
@Preview(name = "PREVIEW · подписка, крупный шрифт", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable private fun PreviewSubscriptionLargeText() = SubscriptionPreview(SubscriptionDialogStatus.InvalidUrl, "example")

@Composable
private fun ServersPreview(servers: List<ServerChoice>, selected: Set<String>) {
    SweepTheme {
        Box(Modifier.fillMaxSize().background(SweepColors.background)) {
            ServerSelectionDialogContent(servers, selected, {}, {})
        }
    }
}

@PreviewTest
@Preview(name = "PREVIEW · серверы пустые", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersEmpty() = ServersPreview(emptyList(), emptySet())

@PreviewTest
@Preview(name = "PREVIEW · серверы не выбраны", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersNone() = ServersPreview(previewServers, emptySet())

@PreviewTest
@Preview(name = "PREVIEW · часть серверов", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersPartial() = ServersPreview(previewServers, setOf("de", "nl", "fr"))

@PreviewTest
@Preview(name = "PREVIEW · все поддерживаемые серверы", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersAll() = ServersPreview(previewServers, setOf("de", "nl", "fi", "fr", "se"))

@PreviewTest
@Preview(name = "PREVIEW · серверы, крупный шрифт", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable private fun PreviewServersLargeText() = ServersPreview(previewServers, setOf("de", "nl"))
