package com.movtery.zalithlauncher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.movtery.zalithlauncher.R

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val base = Typography()

private fun TextStyle.inter(tight: Boolean = false): TextStyle =
    copy(fontFamily = InterFamily, letterSpacing = if (tight) (-0.02).em else letterSpacing)

val AppTypography = Typography(
    displayLarge = base.displayLarge.inter(true),
    displayMedium = base.displayMedium.inter(true),
    displaySmall = base.displaySmall.inter(true),
    headlineLarge = base.headlineLarge.inter(true),
    headlineMedium = base.headlineMedium.inter(true),
    headlineSmall = base.headlineSmall.inter(true),
    titleLarge = base.titleLarge.inter(true),
    titleMedium = base.titleMedium.inter(),
    titleSmall = base.titleSmall.inter(),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter(),
    labelMedium = base.labelMedium.inter(),
    labelSmall = base.labelSmall.inter()
)
