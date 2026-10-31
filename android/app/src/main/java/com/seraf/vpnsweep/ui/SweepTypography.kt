package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val SweepSans: FontFamily = FontFamily(
    Font(R.font.noto_sans_regular, weight = FontWeight.Normal),
    Font(R.font.noto_sans_bold, weight = FontWeight.Bold),
)

internal val SweepMono: FontFamily = FontFamily(
    Font(R.font.source_code_pro_regular, weight = FontWeight.Normal),
)

internal val SweepTypography: Typography = Typography().let { defaults ->
    defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = SweepSans),
        displayMedium = defaults.displayMedium.copy(fontFamily = SweepSans),
        displaySmall = defaults.displaySmall.copy(fontFamily = SweepSans),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = SweepSans),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = SweepSans),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = SweepSans, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleLarge = defaults.titleLarge.copy(fontFamily = SweepSans, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = defaults.titleMedium.copy(fontFamily = SweepSans),
        titleSmall = defaults.titleSmall.copy(fontFamily = SweepSans),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = SweepSans, fontSize = 16.sp),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = SweepSans, fontSize = 14.sp),
        bodySmall = defaults.bodySmall.copy(fontFamily = SweepSans),
        labelLarge = defaults.labelLarge.copy(fontFamily = SweepSans, fontSize = 16.sp),
        labelMedium = defaults.labelMedium.copy(fontFamily = SweepSans),
        labelSmall = defaults.labelSmall.copy(fontFamily = SweepSans),
    )
}
