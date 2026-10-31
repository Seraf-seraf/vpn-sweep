package com.seraf.vpnsweep.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object SweepColors {
    val background = Color(0xFF081324)
    val surface = Color(0xFF111D32)
    val accent = Color(0xFFB3ABFF)
    val text = Color(0xFFF4F6FF)
    val secondary = Color(0xFF98A5C4)
    val outline = Color(0xFF344B75)
    val error = Color(0xFFFF8D9C)
    val onAccent = Color(0xFF11162C)
}

@Composable
internal fun SweepTheme(content: @Composable () -> Unit): Unit {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SweepColors.accent,
            onPrimary = SweepColors.onAccent,
            secondary = SweepColors.accent,
            background = SweepColors.background,
            surface = SweepColors.surface,
            onSurface = SweepColors.text,
            onSurfaceVariant = SweepColors.secondary,
            outline = SweepColors.outline,
            error = SweepColors.error,
        ),
        typography = SweepTypography,
        content = content,
    )
}
