package com.seraf.vpnsweep.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class DialogActionsLayout(
    val stacked: Boolean,
    val cancelWeight: Float = 1f,
    val confirmWeight: Float = 1f,
)

@Composable
internal fun dialogActionsLayout(
    availableWidth: Dp,
    horizontalPadding: Dp,
    outerPadding: Dp = 0.dp,
    confirmExtra: Dp = 0.dp,
): DialogActionsLayout {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = SweepSans, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val density = LocalDensity.current
    val cancelText = measurer.measure("Отмена", style = style).size.width
    val confirmText = measurer.measure("Ок", style = style).size.width
    val cancelWidth = maxOf(48.dp, with(density) { cancelText.toDp() } + horizontalPadding * 2 + outerPadding)
    val confirmWidth = maxOf(48.dp, with(density) { confirmText.toDp() } + horizontalPadding * 2 + outerPadding + confirmExtra)
    val spacing = 8.dp
    return when {
        availableWidth >= maxOf(cancelWidth, confirmWidth) * 2 + spacing -> DialogActionsLayout(stacked = false)
        availableWidth >= cancelWidth + confirmWidth + spacing ->
            DialogActionsLayout(stacked = false, cancelWeight = cancelWidth.value, confirmWeight = confirmWidth.value)
        else -> DialogActionsLayout(stacked = true)
    }
}
