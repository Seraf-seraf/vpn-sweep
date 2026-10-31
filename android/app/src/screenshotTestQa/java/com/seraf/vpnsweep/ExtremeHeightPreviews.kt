package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.ServerSelectionDialogContent
import com.seraf.vpnsweep.ui.SubscriptionDialogContent
import com.seraf.vpnsweep.ui.SubscriptionDialogStatus
import com.seraf.vpnsweep.ui.SweepColors
import com.seraf.vpnsweep.ui.SweepTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Synthetic subscription 272x148dp fontScale=2", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
private fun SubscriptionExtremeHeightPreview() {
    SweepTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(SweepColors.background).padding(
                start = 24.dp,
                end = 24.dp,
                top = 48.dp,
                bottom = 284.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            SubscriptionDialogContent(
                link = "example",
                status = SubscriptionDialogStatus.InvalidUrl,
                onLinkChange = {},
                onDismiss = {},
                onSubmit = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Synthetic server selection 272x148dp fontScale=2", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
private fun ServerExtremeHeightPreview() {
    SweepTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(SweepColors.background).padding(
                top = 24.dp,
                bottom = 260.dp,
            ),
        ) {
            ServerSelectionDialogContent(
                servers = listOf(
                    ServerChoice("de", "Германия 01", "VLESS · Reality", true),
                    ServerChoice("nl", "Нидерланды 02", "VLESS · Reality", true),
                    ServerChoice("fi", "Финляндия 03", "VLESS · WS / TLS", true),
                ),
                selectedIds = setOf("de", "nl"),
                onDismiss = {},
                onConfirm = {},
            )
        }
    }
}
