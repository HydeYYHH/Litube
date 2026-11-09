package com.hhst.youtubelite.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
internal fun SettingsGroup(label: String) {
    AppText(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        size = 14, line = 20, weight = FontWeight.Medium, color = DesignTokens.Secondary)
}

@Composable
internal fun SettingsToggle(
    title: String,
    summary: String,
    key: String,
    preferences: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    val enabled = preferences[key] == true
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = enabled, role = Role.Switch) { haptic(); onToggle(key, it) }
            .testTag("settings-toggle-$key").padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabels(title, summary, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        com.hhst.youtubelite.ui.components.AppSwitch(enabled, onCheckedChange = null,
            modifier = Modifier.testTag("settings-toggle-state-$key"))
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    tag: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .testTag(tag).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabels(title, summary, Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            AppText(value, size = 14, line = 20, color = DesignTokens.Secondary)
        }
    }
}

@Composable
internal fun SettingsLabels(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        AppText(title, size = 16, line = 22)
        if (!summary.isNullOrBlank()) AppText(summary, size = 12, line = 18, color = DesignTokens.Secondary)
    }
}
