package com.hhst.youtubelite.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R

// Same bundled family the content surfaces use: one font source; pages keep their own sizes/line heights.
private val AppFont = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal), Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold), Font(R.font.roboto_bold, FontWeight.Black),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = AppFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val AppTypography = Typography(
    displayLarge = type(24, 30, FontWeight.Medium), displayMedium = type(22, 28, FontWeight.Medium),
    displaySmall = type(20, 26, FontWeight.Medium), headlineLarge = type(20, 26, FontWeight.Medium),
    headlineMedium = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Medium),
    headlineSmall = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Medium),
    titleLarge = type(SettingsTokens.TitleSize, SettingsTokens.TitleLine, FontWeight.Medium),
    titleMedium = type(SettingsTokens.BodySize, SettingsTokens.BodyLine, FontWeight.Medium),
    titleSmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    bodyLarge = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    bodyMedium = type(SettingsTokens.BodySize, SettingsTokens.BodyLine),
    bodySmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    labelLarge = type(SettingsTokens.BodySize, SettingsTokens.BodyLine, FontWeight.Medium),
    labelMedium = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
    labelSmall = type(SettingsTokens.DetailSize, SettingsTokens.DetailLine),
)
