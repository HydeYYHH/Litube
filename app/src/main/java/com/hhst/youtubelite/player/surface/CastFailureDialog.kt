package com.hhst.youtubelite.player.surface

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import com.hhst.youtubelite.cast.CastFailureReport
import com.hhst.youtubelite.ui.components.AppAlertDialog
import com.hhst.youtubelite.ui.components.AppTextButton

@Composable
internal fun CastFailureDialog(report: CastFailureReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.cast_failed)
    val copied = stringResource(R.string.copied)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("cast-failure-dialog"),
        title = { Text(title) },
        text = {
            Column {
                SelectionContainer {
                    Text(report.text, modifier = Modifier.heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()).testTag("cast-failure-report"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(title, report.text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.testTag("cast-failure-copy")) { Text(stringResource(R.string.copy_all)) }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
