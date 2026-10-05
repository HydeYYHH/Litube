package com.hhst.youtubelite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Specify container roles too: Material defaults otherwise reintroduce purple
// on dialogs, menus and selected controls in the neutral app palette.
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnSurface,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightSecondary,
    onTertiary = LightOnSecondary,
    tertiaryContainer = LightSurfaceVariant,
    onTertiaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    surfaceDim = LightSurfaceVariant,
    surfaceBright = LightBackground,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurfaceVariant,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    inversePrimary = DarkPrimary,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = YtRed,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnSurface,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = DarkSecondary,
    onTertiary = DarkOnSecondary,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    surfaceDim = DarkBackground,
    surfaceBright = DarkSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurfaceVariant,
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnSurface,
    inversePrimary = LightPrimary,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = YtRed,
)

/**
 * App [MaterialTheme]. [dynamicColor] (API 31+) uses wallpaper colors; default
 * is the fixed brand palette for consistent red accents.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
