package com.hhst.youtubelite.ui.extension

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extension.Extension
import org.koin.androidx.compose.koinViewModel

private val ExpandSpec = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private val CollapseSpec = tween<IntSize>(
    durationMillis = 220,
    easing = FastOutSlowInEasing,
)

/** Expandable extension settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionScreen(
    onClose: () -> Unit,
    viewModel: ExtensionViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showReset by remember { mutableStateOf(false) }
    val iconTint = MaterialTheme.colorScheme.onSurface

    BackHandler { onClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extension)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.navigate_back),
                            tint = iconTint,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showReset = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_reset),
                            contentDescription = stringResource(R.string.reset_all_settings),
                            tint = iconTint,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = iconTint,
                    actionIconContentColor = iconTint,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(
                items = uiState.sections,
                key = { it.id },
            ) { section ->
                GroupBlock(
                    node = section,
                    depth = 0,
                    expanded = uiState.expanded,
                    toggles = uiState.toggles,
                    onToggleExpand = viewModel::toggleExpanded,
                    onToggle = viewModel::setEnabled,
                )
            }
        }
    }

    if (showReset) {
        val scheme = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.reset_extension_title)) },
            text = { Text(stringResource(R.string.reset_extension_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetToDefault()
                        showReset = false
                    },
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = scheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = scheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun GroupBlock(
    node: Extension,
    depth: Int,
    expanded: Set<String>,
    toggles: Map<String, Boolean>,
    onToggleExpand: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val open = node.id in expanded
    val startPad = 16.dp + (depth * 12).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onToggleExpand(node.id) }
            .padding(start = startPad, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.icon != 0) {
            Icon(
                painter = painterResource(node.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = stringResource(node.title),
            style = if (depth == 0) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (open) 90f else 0f),
        )
    }

    AnimatedVisibility(
        visible = open,
        enter = expandVertically(animationSpec = ExpandSpec, expandFrom = Alignment.Top),
        exit = shrinkVertically(animationSpec = CollapseSpec, shrinkTowards = Alignment.Top),
    ) {
        Column {
            for (child in node.children) {
                if (child.isGroup) {
                    GroupBlock(
                        node = child,
                        depth = depth + 1,
                        expanded = expanded,
                        toggles = toggles,
                        onToggleExpand = onToggleExpand,
                        onToggle = onToggle,
                    )
                } else {
                    val key = child.key ?: continue
                    ToggleRow(
                        title = child.title,
                        summary = child.summary,
                        checked = toggles[key] == true,
                        depth = depth + 1,
                        onCheckedChange = { onToggle(key, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: Int,
    summary: Int,
    checked: Boolean,
    depth: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    val startPad = 16.dp + (depth * 12).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(start = startPad, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != 0) {
                Text(
                    text = stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
