package com.hhst.youtubelite.ui.extension

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import com.hhst.youtubelite.ui.components.YoutubeAlertDialog as AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.components.YoutubeTextButton as TextButton
import com.hhst.youtubelite.ui.components.YoutubeTopAppBar as TopAppBar
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.SettingsBackup
import com.hhst.youtubelite.extension.SettingsBackupData
import com.hhst.youtubelite.ui.components.ConstrainedPage
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight
import org.koin.compose.koinInject
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticOperation
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataScreen(
    onClose: () -> Unit,
    manager: ExtensionManager = koinInject(),
    downloadPrefs: DownloadPrefs = koinInject(),
) {
    val context = LocalContext.current
    var pendingImport by remember { mutableStateOf<SettingsBackupData?>(null) }
    val fileOperations = remember { arrayOfNulls<DiagnosticOperation>(2) }

    BackHandler { onClose() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val operation = fileOperations[0].also { fileOperations[0] = null }
        if (uri == null) {
            operation?.finish(DiagnosticOutcome.CANCELLED, "file_picker_cancelled")
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(SettingsBackup.export(manager, downloadPrefs).toByteArray())
            } != null
        }
        val written = result.getOrDefault(false)
        operation?.finish(if (written) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILURE,
            if (written) "settings_saved" else "file_write_failed", result.exceptionOrNull())
        Toast.makeText(
            context,
            if (written) R.string.settings_export_done else R.string.settings_export_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val operation = fileOperations[1].also { fileOperations[1] = null }
        if (uri == null) {
            operation?.finish(DiagnosticOutcome.CANCELLED, "file_picker_cancelled")
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }
        val json = result.getOrNull()
        val backup = json?.let { SettingsBackup.parse(it) }
        operation?.finish(if (backup == null) DiagnosticOutcome.FAILURE else DiagnosticOutcome.SUCCESS,
            if (backup == null) "settings_read_failed" else "settings_read", result.exceptionOrNull(),
            mapOf("bytes" to json?.toByteArray()?.size))
        if (backup == null) {
            Toast.makeText(context, R.string.settings_import_failed, Toast.LENGTH_SHORT).show()
        } else {
            pendingImport = backup
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_restore), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigate_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        ConstrainedPage(Modifier.padding(padding), maxWidth = 720.dp) {
            Column(Modifier.fillMaxSize()) {
                ActionRow(
                    icon = R.drawable.ic_share,
                    title = R.string.settings_export,
                    summary = R.string.settings_export_summary,
                    onClick = {
                        fileOperations[0]?.finish(DiagnosticOutcome.SUPERSEDED, "picker_reopened")
                        fileOperations[0] = AppLog.operation(AppLog.Category.STORAGE, "settings_export")
                        exportLauncher.launch("litube-settings.json")
                    },
                )
                ActionRow(
                    icon = R.drawable.ic_download,
                    title = R.string.settings_import,
                    summary = R.string.settings_import_summary,
                    onClick = {
                        fileOperations[1]?.finish(DiagnosticOutcome.SUPERSEDED, "picker_reopened")
                        fileOperations[1] = AppLog.operation(AppLog.Category.STORAGE, "settings_import_read")
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    },
                )
            }
        }
    }

    pendingImport?.let { backup ->
        val scheme = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsBackup.apply(backup, manager, downloadPrefs)
                        pendingImport = null
                        Toast.makeText(context, R.string.settings_import_done, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(text = stringResource(R.string.confirm), color = scheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(text = stringResource(R.string.cancel), color = scheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun ActionRow(
    icon: Int,
    title: Int,
    summary: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsTokens.RowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                start = SettingsTokens.PageInset,
                end = SettingsTokens.PageInset,
                top = SettingsTokens.RowPadding,
                bottom = SettingsTokens.RowPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SettingsTokens.IconSlot), contentAlignment = Alignment.Center) { Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(SettingsTokens.IconSize),
        ) }
        Spacer(Modifier.width(SettingsTokens.LabelGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
