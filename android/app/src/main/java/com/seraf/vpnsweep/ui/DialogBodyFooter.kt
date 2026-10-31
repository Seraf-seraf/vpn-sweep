package com.seraf.vpnsweep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val MinimumBodyControlHeight = 56.dp
private val MinimumBodyTitleLineHeight = 30.sp

@Composable
internal fun DialogBodyFooter(
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    footerLeading: (@Composable () -> Unit)? = null,
): Unit {
    val minimumBodyHeight = with(LocalDensity.current) {
        maxOf(MinimumBodyControlHeight.toPx(), MinimumBodyTitleLineHeight.toPx()).roundToInt()
    }
    val scrollState = rememberScrollState()
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val footerPlaceable = subcompose("measureFooter") {
            Box(Modifier.clearAndSetSemantics {}) { footer() }
        }.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val leadingPlaceable = footerLeading?.let { leading ->
            subcompose("measureFooterLeading") {
                Box(Modifier.clearAndSetSemantics {}) { leading() }
            }.single().measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity),
            )
        }
        val leadingFits = !constraints.hasBoundedHeight || leadingPlaceable == null ||
            constraints.maxHeight - footerPlaceable.height - leadingPlaceable.height >= minimumBodyHeight
        val scrollEntireDialog = constraints.hasBoundedHeight &&
            constraints.maxHeight - footerPlaceable.height < minimumBodyHeight
        val content = subcompose("content") {
            Column(
                Modifier.fillMaxWidth().then(
                    if (scrollEntireDialog) Modifier.verticalScroll(scrollState) else Modifier,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().then(
                        if (constraints.hasBoundedHeight && !scrollEntireDialog) {
                            Modifier.weight(1f, fill = false).verticalScroll(scrollState)
                        } else Modifier,
                    ),
                ) {
                    body()
                    if (!leadingFits) footerLeading?.invoke()
                }
                if (leadingFits) footerLeading?.invoke()
                footer()
            }
        }.single().measure(constraints)
        layout(content.width, content.height) { content.placeRelative(0, 0) }
    }
}
