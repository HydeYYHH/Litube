package com.hhst.youtubelite.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.settings.domain.SettingsPage
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
fun SettingsScreen(
    page: SettingsPage,
    preferences: Map<String, Boolean>,
    notificationsEnabled: Boolean,
    onNavigate: (SettingsPage) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAbout: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAdvanced: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).testTag("settings-screen")) {
        SettingsTopBar(page.title, onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).testTag("settings-page-${page.argument}")) {
                when (page) {
                    SettingsPage.Home -> SettingsHome(onNavigate, onAbout, onAdvanced)
                    SettingsPage.General -> GeneralSettings(preferences, onToggle)
                    SettingsPage.Playback -> PlaybackSettings(preferences, onToggle)
                    SettingsPage.Notifications -> NotificationSettings(notificationsEnabled, onOpenNotificationSettings)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private val SettingsPage.title: String
    get() = when (this) {
        SettingsPage.Home -> "Settings"
        SettingsPage.General -> "General"
        SettingsPage.Playback -> "Playback"
        SettingsPage.Notifications -> "Notifications"
    }

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(DesignTokens.TopBarHeight).testTag("settings-topbar"),
        verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.Back, "Back", diameter = 48.dp, onClick = onBack)
        AppText(title, Modifier.padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsHome(onNavigate: (SettingsPage) -> Unit, onAbout: () -> Unit, onAdvanced: (() -> Unit)?) {
    SettingsGroup("Preferences")
    SettingsRow("General", "Theme, language and Shorts", tag = "settings-row-general") {
        onNavigate(SettingsPage.General)
    }
    SettingsRow("Playback", "Background play and picture-in-picture", tag = "settings-row-playback") {
        onNavigate(SettingsPage.Playback)
    }
    SettingsRow("Notifications", "System notification access", tag = "settings-row-notifications") {
        onNavigate(SettingsPage.Notifications)
    }
    if (onAdvanced != null) SettingsRow("Advanced", "Player, gestures, SponsorBlock and backup", tag = "settings-row-advanced", onClick = onAdvanced)
    SettingsGroup("About")
    SettingsRow("About Litube", "Version, updates and diagnostics", tag = "settings-row-about", onClick = onAbout)
}

@Composable
private fun GeneralSettings(preferences: Map<String, Boolean>, onToggle: (String, Boolean) -> Unit) {
    SettingsGroup("Appearance")
    SettingsRow("Theme", value = "Light", tag = "settings-row-theme")
    SettingsRow("Language", value = "English", tag = "settings-row-language")
    SettingsGroup("Feed")
    SettingsToggle("Hide Shorts", "Remove Shorts shelves from the feed",
        PreferenceKeys.ENABLE_HIDE_SHORTS, preferences, onToggle)
}

@Composable
private fun PlaybackSettings(preferences: Map<String, Boolean>, onToggle: (String, Boolean) -> Unit) {
    SettingsGroup("Playback")
    SettingsToggle("Background play", "Keep audio playing after leaving the app",
        PreferenceKeys.ENABLE_BACKGROUND_PLAY, preferences, onToggle)
    SettingsToggle("Picture-in-picture", "Continue in a floating window",
        PreferenceKeys.ENABLE_PIP, preferences, onToggle)
    SettingsToggle("Remember last position", "Resume videos where you stopped",
        PreferenceKeys.REMEMBER_LAST_POSITION, preferences, onToggle)
}

@Composable
private fun NotificationSettings(notificationsEnabled: Boolean, onOpen: () -> Unit) {
    SettingsGroup("System")
    SettingsRow("Notifications", value = if (notificationsEnabled) "On" else "Off",
        tag = "settings-notifications-state")
    SettingsRow("Open system settings", "Android notification settings for Litube",
        tag = "settings-open-notification-settings", onClick = onOpen)
}

@Composable
private fun SettingsGroup(label: String) {
    AppText(label.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        size = 14, line = 20, weight = FontWeight.Medium, color = DesignTokens.Secondary)
}

@Composable
private fun SettingsToggle(
    title: String,
    summary: String,
    key: String,
    preferences: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    val enabled = preferences[key] == true
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = enabled, role = Role.Switch) { onToggle(key, it) }
            .testTag("settings-toggle-$key").padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabels(title, summary, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        AppText(if (enabled) "On" else "Off", Modifier.testTag("settings-toggle-state-$key"),
            size = 14, line = 20, color = DesignTokens.Secondary)
    }
}

@Composable
private fun SettingsRow(
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
private fun SettingsLabels(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        AppText(title, size = 14, line = 22)
        if (summary != null) AppText(summary, size = 12, line = 18, color = DesignTokens.Secondary)
    }
}
