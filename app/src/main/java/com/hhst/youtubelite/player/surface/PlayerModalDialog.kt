package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hhst.youtubelite.ui.theme.AppStyle

@Composable
internal fun PlayerModalDialog(
    onDismiss: () -> Unit,
    fullscreen: Boolean = false,
    tag: String = "player-dialog",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (fullscreen && window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().testTag("player-dialog-scrim")
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight).testTag(tag)
                    .pointerInput(Unit) { detectTapGestures {} },
                    shape = AppStyle.MenuShape, color = AppStyle.Panel,
                    contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(vertical = 8.dp), content = content)
                }
            }
        }
    }
}
