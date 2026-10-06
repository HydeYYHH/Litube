package com.hhst.youtubelite.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = 0.sp,
)

val AppTypography = Typography(
    displayLarge = type(24, 30, FontWeight.Medium), displayMedium = type(22, 28, FontWeight.Medium),
    displaySmall = type(20, 26, FontWeight.Medium), headlineLarge = type(20, 26, FontWeight.Medium),
    headlineMedium = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Bold),
    headlineSmall = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Bold),
    titleLarge = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Bold),
    titleMedium = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    titleSmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    bodyLarge = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    bodyMedium = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    bodySmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    labelLarge = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    labelMedium = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    labelSmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
)
