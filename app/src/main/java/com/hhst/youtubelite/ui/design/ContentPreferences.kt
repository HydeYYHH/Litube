package com.hhst.youtubelite.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import org.koin.core.context.GlobalContext

val LocalHideShorts = staticCompositionLocalOf { false }

@Composable
internal fun rememberHideShorts(): Boolean {
    val manager = remember { GlobalContext.getOrNull()?.getOrNull<ExtensionManager>() }
    var hidden by remember(manager) { mutableStateOf(manager?.isEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS) == true) }
    DisposableEffect(manager) {
        val listener: (String) -> Unit = { key ->
            if (key == PreferenceKeys.ENABLE_HIDE_SHORTS || key == "*")
                hidden = manager?.isEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS) == true
        }
        manager?.addOnChangedListener(listener)
        listener("*")
        onDispose { manager?.removeOnChangedListener(listener) }
    }
    return hidden
}
