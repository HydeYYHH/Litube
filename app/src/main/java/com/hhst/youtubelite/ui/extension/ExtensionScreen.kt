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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.extension.Extension
import com.hhst.youtubelite.extension.ExtensionKind
import com.hhst.youtubelite.ui.components.ConstrainedPage
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    onNavigate: (String) -> Unit = {},
    viewModel: ExtensionViewModel = koinViewModel(),
) {
    val haptics: HapticsController = koinInject()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showReset by remember { mutableStateOf(false) }
    val iconTint = MaterialTheme.colorScheme.onSurface

    BackHandler { onClose() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extension), style = MaterialTheme.typography.titleLarge) },
                expandedHeight = settingsAppBarHeight(),
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
        ConstrainedPage(Modifier.padding(padding), maxWidth = 720.dp) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = SettingsTokens.GroupGap, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap),
            ) {
                items(
                    items = uiState.sections,
                    key = { it.id },
                ) { section ->
                    if (section.isNav) {
                        NavRow(
                            node = section,
                            depth = 0,
                            onClick = { onNavigate(section.id) },
                        )
                    } else {
                        GroupBlock(
                            node = section,
                            depth = 0,
                            expanded = uiState.expanded,
                            toggles = uiState.toggles,
                            onToggleExpand = viewModel::toggleExpanded,
                            onToggle = { key, enabled -> viewModel.setEnabled(key, enabled); haptics.perform(HapticsController.Event.SELECTION) },
                            strength = uiState.hapticStrength,
                            onStrength = viewModel::setHapticStrength,
                            onPreview = { haptics.perform(HapticsController.Event.SELECTION) },
                            onNavigate = onNavigate,
                        )
                    }
                }
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
    onNavigate: (String) -> Unit,
    strength: Int,
    onStrength: (Int) -> Unit,
    onPreview: () -> Unit,
) {
    val open = node.id in expanded
    val startPad = SettingsTokens.Indent * (depth - 1).coerceAtLeast(0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsTokens.RowHeight)
            .clickable(role = Role.Button) { onToggleExpand(node.id) }
            .padding(start = startPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.icon != 0) {
            Box(Modifier.size(SettingsTokens.IconSlot), contentAlignment = Alignment.Center) { Icon(
                painter = painterResource(node.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(SettingsTokens.IconSize),
            ) }
            Spacer(Modifier.width(SettingsTokens.LabelGap))
        } else {
            Spacer(Modifier.width(SettingsTokens.PageInset))
        }
        Text(
            text = stringResource(node.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(SettingsTokens.IconSlot), contentAlignment = Alignment.Center) { Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(SettingsTokens.IconSize)
                .rotate(if (open) 90f else 0f),
        ) }
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
                        strength = strength,
                        onStrength = onStrength,
                        onPreview = onPreview,
                        onNavigate = onNavigate,
                    )
                } else if (child.isNav) {
                    NavRow(
                        node = child,
                        depth = depth + 1,
                        onClick = { onNavigate(child.id) },
                    )
                } else if (child.kind == ExtensionKind.SLIDER) {
                    Column(Modifier.fillMaxWidth().padding(start = startPad + SettingsTokens.PageInset,
                        end = SettingsTokens.PageInset, top = SettingsTokens.RowPadding, bottom = SettingsTokens.RowPadding)) {
                        Text(stringResource(child.title), style = MaterialTheme.typography.bodyMedium)
                        Text(if (strength == 0) stringResource(R.string.haptics_off) else "$strength%", style = MaterialTheme.typography.bodySmall)
                        Slider(value = strength.toFloat(), onValueChange = { onStrength(it.toInt()) },
                            valueRange = 0f..100f, onValueChangeFinished = onPreview)
                    }
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
private fun NavRow(
    node: Extension,
    depth: Int,
    onClick: () -> Unit,
) {
    val startPad = SettingsTokens.Indent * (depth - 1).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsTokens.RowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = startPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.icon != 0) {
            Box(Modifier.size(SettingsTokens.IconSlot), contentAlignment = Alignment.Center) { Icon(
                painter = painterResource(node.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(SettingsTokens.IconSize),
            ) }
            Spacer(Modifier.width(SettingsTokens.LabelGap))
        } else {
            Spacer(Modifier.width(SettingsTokens.PageInset))
        }
        Text(
            text = stringResource(node.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(SettingsTokens.IconSlot), contentAlignment = Alignment.Center) { Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsTokens.IconSize),
        ) }
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
    val startPad = SettingsTokens.PageInset + SettingsTokens.Indent * (depth - 1).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsTokens.RowHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = startPad, end = SettingsTokens.PageInset,
                top = SettingsTokens.RowPadding, bottom = SettingsTokens.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.bodyMedium,
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
            onCheckedChange = null,
        )
    }
}
