package com.seraf.vpnsweep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Preview(name = "PREVIEW · главный экран", widthDp = 360, heightDp = 800)
@Composable private fun PreviewHome() = HomePreview(VpnState(), selected = false)

@Preview(name = "PREVIEW · подключение", widthDp = 360, heightDp = 800)
@Composable private fun PreviewConnecting() = HomePreview(VpnState(VpnPhase.CONNECTING, "Подключение…"), selected = true)

@Preview(name = "PREVIEW · VPN запущен", widthDp = 360, heightDp = 800)
@Composable private fun PreviewRunning() = HomePreview(VpnState(VpnPhase.RUNNING, "Туннель запущен"), selected = true)

@Preview(name = "PREVIEW · отключение", widthDp = 360, heightDp = 800)
@Composable private fun PreviewStopping() = HomePreview(VpnState(VpnPhase.STOPPING, "Отключение…"), selected = true)

@Preview(name = "PREVIEW · крупный шрифт", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable private fun PreviewLargeText() = HomePreview(VpnState(), selected = false)

@Preview(name = "PREVIEW · параметры", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewParameters() {
    SweepTheme {
        Column(
            Modifier.fillMaxSize().background(SweepColors.background).verticalScroll(rememberScrollState()).padding(24.dp),
        ) { ParametersPanel(initialFragmentExpanded = true) }
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

@Preview(name = "PREVIEW · подписка пустая", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionEmpty() = SubscriptionPreview(SubscriptionDialogStatus.Empty, "")

@Preview(name = "PREVIEW · ссылка введена", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionInput() = SubscriptionPreview(SubscriptionDialogStatus.Input, "https://example.com/sub/demo")

@Preview(name = "PREVIEW · загрузка", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionLoading() = SubscriptionPreview(SubscriptionDialogStatus.Loading, "https://example.com/sub/demo")

@Preview(name = "PREVIEW · неверная ссылка", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionInvalid() = SubscriptionPreview(SubscriptionDialogStatus.InvalidUrl, "example")

@Preview(name = "PREVIEW · ошибка загрузки", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionNetworkError() = SubscriptionPreview(SubscriptionDialogStatus.NetworkError, "https://example.com/sub/demo")

@Preview(name = "PREVIEW · ошибка формата", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionMalformed() = SubscriptionPreview(SubscriptionDialogStatus.MalformedData, "https://example.com/sub/demo")

@Preview(name = "PREVIEW · загрузка недоступна", widthDp = 360, heightDp = 640)
@Composable private fun PreviewSubscriptionUnavailable() = SubscriptionPreview(SubscriptionDialogStatus.Unavailable, "https://example.com/sub/demo")

@Preview(name = "PREVIEW · подписка, крупный шрифт", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable private fun PreviewSubscriptionLargeText() = SubscriptionPreview(SubscriptionDialogStatus.InvalidUrl, "example")

@Composable
private fun ServersPreview(servers: List<ServerChoice>, selected: Set<String>) {
    SweepTheme {
        ServerSelectionDialogContent(servers, selected, {}, {})
    }
}

@Preview(name = "PREVIEW · серверы пустые", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersEmpty() = ServersPreview(emptyList(), emptySet())

@Preview(name = "PREVIEW · серверы не выбраны", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersNone() = ServersPreview(previewServers, emptySet())

@Preview(name = "PREVIEW · часть серверов", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersPartial() = ServersPreview(previewServers, setOf("de", "nl", "fr"))

@Preview(name = "PREVIEW · все поддерживаемые серверы", widthDp = 360, heightDp = 720)
@Composable private fun PreviewServersAll() = ServersPreview(previewServers, setOf("de", "nl", "fi", "fr", "se"))

@Preview(name = "PREVIEW · серверы, крупный шрифт", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable private fun PreviewServersLargeText() = ServersPreview(previewServers, setOf("de", "nl"))
