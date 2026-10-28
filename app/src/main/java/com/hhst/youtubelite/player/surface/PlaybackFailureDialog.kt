package com.hhst.youtubelite.player.surface

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.diagnostics.PlaybackFailureReport
import com.hhst.youtubelite.ui.components.AppAlertDialog
import com.hhst.youtubelite.ui.components.AppTextButton

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PlaybackFailureDialog(report: PlaybackFailureReport, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.player_error)
    val copied = stringResource(R.string.copied)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("playback-failure-dialog"),
        title = { Text(title) },
        text = {
            Column {
                if (report.summary != title) Text(report.summary)
                SelectionContainer {
                    Text(report.text, modifier = Modifier.heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()).testTag("playback-failure-report"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppTextButton(onClick = onDismiss, modifier = Modifier.testTag("playback-failure-close")) {
                    Text(stringResource(R.string.close))
                }
                AppTextButton(onClick = { onDismiss(); onRetry() }, modifier = Modifier.testTag("playback-failure-retry")) {
                    Text(stringResource(R.string.retry))
                }
                AppTextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, report.text))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.testTag("playback-failure-copy")) { Text(stringResource(R.string.copy_all)) }
            }
        },
    )
}
