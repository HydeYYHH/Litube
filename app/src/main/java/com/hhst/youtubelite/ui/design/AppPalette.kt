package com.hhst.youtubelite.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppPalette(
    val background: Color,
    val ink: Color,
    val secondary: Color,
    /** Secondary container: chips, pressed pills, quiet surfaces. */
    val surface: Color,
    /** Stronger state container: selected rows, skeletons, banners. */
    val ghost: Color,
    val border: Color,
    val navigationDivider: Color,
    val scrim: Color,
    val menuScrim: Color,
    /** Immediate press response under icons and rows. */
    val stateLayer: Color,
    val red: Color,
    val link: Color,
)

val LightAppPalette = AppPalette(
    background = Color.White,
    ink = Color(0xFF0F0F0F),
    secondary = Color(0xFF606060),
    surface = Color(0xFFF2F2F2),
    ghost = Color(0xFFE5E5E5),
    border = Color.Black.copy(alpha = 0.1f),
    navigationDivider = Color.Black.copy(alpha = 0.2f),
    scrim = Color.Black.copy(alpha = 0.3f),
    menuScrim = Color.Black.copy(alpha = 0.6f),
    stateLayer = Color.Black.copy(alpha = 0.08f),
    red = Color(0xFFFF0033),
    link = Color(0xFF065FD4),
)

val DarkAppPalette = AppPalette(
    background = Color.Black,
    ink = Color(0xFFF1F1F1),
    secondary = Color(0xFFAAAAAA),
    surface = Color(0xFF212121),
    ghost = Color(0xFF272727),
    border = Color.White.copy(alpha = 0.1f),
    navigationDivider = Color.White.copy(alpha = 0.2f),
    scrim = Color.Black.copy(alpha = 0.5f),
    menuScrim = Color.Black.copy(alpha = 0.7f),
    stateLayer = Color.White.copy(alpha = 0.08f),
    red = Color(0xFFFF0033),
    link = Color(0xFF3EA6FF),
)

val LocalAppColors = staticCompositionLocalOf { LightAppPalette }
