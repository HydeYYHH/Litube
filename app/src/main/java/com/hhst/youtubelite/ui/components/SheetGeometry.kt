package com.hhst.youtubelite.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** Window coordinates, shared by the watch host and sheets launched in another activity. */
interface WatchSheetHost {
    val sheetPlayerBottomPx: Int
    val sheetWindowHeightPx: Int
}

fun Context.watchSheetHost(): WatchSheetHost? {
    var current = this
    while (true) {
        if (current is WatchSheetHost) return current
        val base = (current as? ContextWrapper)?.baseContext ?: return null
        if (base === current) return null
        current = base
    }
}

val LocalSheetPlayerBottomFraction = staticCompositionLocalOf { 0f }

/** Size the entire sheet, including its handle and system bottom inset. */
@Composable
fun Modifier.watchSheetHeight(): Modifier {
    val host = LocalContext.current.watchSheetHost()
    val fraction = host?.let {
        it.sheetPlayerBottomPx.toFloat() / it.sheetWindowHeightPx.coerceAtLeast(1)
    } ?: LocalSheetPlayerBottomFraction.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    return if (fraction > 0f && fraction < 1f) {
        height(with(LocalDensity.current) { (windowHeight * (1f - fraction)).toDp() })
    } else this
}
