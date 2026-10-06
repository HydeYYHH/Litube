package com.hhst.youtubelite.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.components.ConstrainedPage
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(label: String, version: String, onClose: () -> Unit, busy: Boolean, status: String,
    onSource: () -> Unit, onUpdate: () -> Unit, onRelease: () -> Unit, onClear: () -> Unit, onExport: () -> Unit) {
    BackHandler { onClose() }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge) },
            expandedHeight = settingsAppBarHeight(),
            navigationIcon = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back)) } })
    }) { padding ->
        ConstrainedPage(Modifier.padding(padding), maxWidth = 720.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SettingsTokens.PageInset),
                verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.mipmap.ic_launcher_foreground), label, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSource, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_source)) }
                HorizontalDivider()
                TextButton(enabled = !busy, onClick = onUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_check_update)) }
                TextButton(onClick = onRelease, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_releases)) }
                TextButton(enabled = !busy, onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_clear_cache)) }
                TextButton(enabled = !busy, onClick = onExport, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_export_logs)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
