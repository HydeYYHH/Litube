package com.hhst.youtubelite.player.surface

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A modal sheet in the player window, so opening it never changes system-bar ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullscreenPlayerSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheet)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    fun finish() {
        if (!dismissed) {
            dismissed = true
            dismiss()
        }
    }
    val close = {
        if (!closing) {
            closing = true
            scope.launch { sheet.hide(); finish() }
        }
    }
    LaunchedEffect(sheet) {
        // A drag can cancel the opening animation; keep observing dismissal independently.
        launch { sheet.show() }
        snapshotFlow { sheet.targetValue }.first { it != SheetValue.Hidden }
        // Includes drag-handle and accessibility dismiss actions, even during opening.
        snapshotFlow { sheet.currentValue to sheet.targetValue }
            .first { (current, target) ->
                current == SheetValue.Hidden && target == SheetValue.Hidden
            }
        finish()
    }
    BackHandler(onBack = close)
    val maxHeight = PlayerUi.sheetMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp
    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize(),
        scaffoldState = scaffold,
        sheetPeekHeight = 0.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 0.dp,
        sheetSwipeEnabled = !closing,
        containerColor = Color.Transparent,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.testTag("player-sheet-handle"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        },
        sheetContent = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), content = content)
        },
    ) {
        Box(
            Modifier.fillMaxSize()
                .testTag("player-sheet-scrim")
                .background(BottomSheetDefaults.ScrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.close),
                    onClick = close,
                ),
        )
    }
}
