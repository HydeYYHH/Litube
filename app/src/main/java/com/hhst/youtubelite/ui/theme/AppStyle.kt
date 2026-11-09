package com.hhst.youtubelite.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

object AppAppearance {
    /** Resolved darkness for the Compose theme; null means "follow the system". */
    var dark by mutableStateOf<Boolean?>(null)
        private set
    var mode by mutableStateOf(ThemeMode.System)
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun update(value: ThemeMode) {
        mode = value
        dark = when (value) {
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
            ThemeMode.System -> null
        }
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    /** Registers a non-Compose observer (system bars); returns the unsubscribe handle. */
    fun onChange(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }
}

object AppStyle {
    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
    val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val MenuShape = RoundedCornerShape(12.dp)
    /** Sheets, dialogs and popup menus share the page's neutral black/white surface.
     * Grey containers belong to controls and item states inside these panels. */
    val Panel: Color
        @Composable get() = MaterialTheme.colorScheme.surface
    val Action: Color
        @Composable get() = LocalActionColors.current?.foreground ?: MaterialTheme.colorScheme.onSurface
    val OnAction: Color
        @Composable get() = LocalActionColors.current?.background ?: MaterialTheme.colorScheme.surface
}

private data class ActionColors(val foreground: Color, val background: Color)
private val LocalActionColors = staticCompositionLocalOf<ActionColors?> { null }

@Composable
fun NeutralControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActionColors provides ActionColors(
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface,
    ), content = content)
}
