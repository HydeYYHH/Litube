package com.hhst.youtubelite.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.ui.components.AppSlider
import com.hhst.youtubelite.ui.design.AppIconButton
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
fun AdvancedScreen(
    preferences: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenData: () -> Unit,
    onOpenDownloads: () -> Unit,
    haptics: HapticsSettings,
    modifier: Modifier = Modifier,
    onGestureChange: (Map<String, Boolean>) -> Unit = { values -> values.forEach { (key, value) -> onToggle(key, value) } },
) {
    Column(modifier.fillMaxSize().background(DesignTokens.Background).testTag("advanced-screen")) {
        AdvancedTopBar(stringResource(R.string.settings_advanced), onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).testTag("advanced-page")) {
                SettingsGroup(stringResource(R.string.interface_category))
                HapticsRow(haptics)
                SettingsToggle(stringResource(R.string.display_dislikes), stringResource(R.string.display_dislikes_summary),
                    PreferenceKeys.ENABLE_DISPLAY_DISLIKES, preferences, onToggle)
                SettingsToggle(stringResource(R.string.show_likes), "",
                    PreferenceKeys.ENABLE_SHOW_LIKES, preferences, onToggle)

                SettingsGroup(stringResource(R.string.player))
                SettingsToggle(stringResource(R.string.remember_quality), "",
                    PreferenceKeys.REMEMBER_QUALITY, preferences, onToggle)
                SettingsToggle(stringResource(R.string.remember_playback_speed), "",
                    PreferenceKeys.REMEMBER_PLAYBACK_SPEED, preferences, onToggle)
                SettingsToggle(stringResource(R.string.autoplay), "",
                    PreferenceKeys.ENABLE_AUTOPLAY, preferences, onToggle)
                SettingsToggle(stringResource(R.string.remember_resize_mode), "",
                    PreferenceKeys.REMEMBER_RESIZE_MODE, preferences, onToggle)
                SettingsToggle(stringResource(R.string.use_original_title), "",
                    PreferenceKeys.USE_ORIGINAL_TITLE, preferences, onToggle)

                SettingsGroup(stringResource(R.string.gesture))
                GestureSettings(preferences, onGestureChange)

                SettingsGroup(stringResource(R.string.sponsorblock))
                SponsorBlockToggles(preferences, onToggle)

                SettingsGroup(stringResource(R.string.dearrow))
                SettingsToggle(stringResource(R.string.dearrow_replace_titles), stringResource(R.string.dearrow_titles_summary),
                    PreferenceKeys.DEARROW_TITLES, preferences, onToggle)
                SettingsToggle(stringResource(R.string.dearrow_replace_thumbnails), stringResource(R.string.dearrow_thumbnails_summary),
                    PreferenceKeys.DEARROW_THUMBNAILS, preferences, onToggle)

                SettingsGroup(stringResource(R.string.settings_group_system))
                SettingsRow(stringResource(R.string.settings_backup_restore), tag = "advanced-row-data", onClick = onOpenData)
                SettingsRow(stringResource(R.string.downloads), tag = "advanced-row-downloads", onClick = onOpenDownloads)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AdvancedTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(DesignTokens.TopBarHeight).testTag("advanced-topbar"),
        verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.Back, stringResource(R.string.a11y_back), diameter = 48.dp, onClick = onBack)
        AppText(title, Modifier.padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Medium)
    }
}

@Composable
private fun HapticsRow(haptics: HapticsSettings) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .testTag("settings-toggle-${PreferenceKeys.HAPTIC_STRENGTH}").padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(stringResource(R.string.haptic_strength), Modifier.weight(1f), size = 14, line = 22)
            AppText(haptics.strength.toString(), size = 14, line = 20, color = DesignTokens.Secondary)
        }
        AppSlider(value = haptics.strength.toFloat(), onValueChange = { haptics.onChange(it.toInt()) },
            valueRange = 0f..100f, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

private val WINDOWED_GESTURE_ROWS = listOf(
    PreferenceKeys.GESTURE_TAP_WINDOWED to R.string.gesture_single_tap,
    PreferenceKeys.GESTURE_DOUBLE_TAP_WINDOWED to R.string.gesture_double_tap,
    PreferenceKeys.GESTURE_LONG_PRESS_WINDOWED to R.string.gesture_long_press_speed,
    PreferenceKeys.GESTURE_BRIGHTNESS_WINDOWED to R.string.brightness,
    PreferenceKeys.GESTURE_VOLUME_WINDOWED to R.string.volume,
    PreferenceKeys.GESTURE_SEEK_WINDOWED to R.string.gesture_seek,
    PreferenceKeys.GESTURE_FULLSCREEN_WINDOWED to R.string.gesture_fullscreen_swipe,
)

private val FULLSCREEN_GESTURE_ROWS = listOf(
    PreferenceKeys.GESTURE_TAP_FULLSCREEN to R.string.gesture_single_tap,
    PreferenceKeys.GESTURE_DOUBLE_TAP_FULLSCREEN to R.string.gesture_double_tap,
    PreferenceKeys.GESTURE_LONG_PRESS_FULLSCREEN to R.string.gesture_long_press_speed,
    PreferenceKeys.GESTURE_BRIGHTNESS_FULLSCREEN to R.string.brightness,
    PreferenceKeys.GESTURE_VOLUME_FULLSCREEN to R.string.volume,
    PreferenceKeys.GESTURE_SEEK_FULLSCREEN to R.string.gesture_seek,
    PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN to R.string.gesture_fullscreen_swipe,
)

@Composable
private fun GestureSettings(preferences: Map<String, Boolean>, onChange: (Map<String, Boolean>) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val embedded = stringResource(R.string.enable_in_embedded)
    val fullscreen = stringResource(R.string.enable_in_fullscreen)
    val labels = listOf(stringResource(R.string.settings_state_off), embedded, fullscreen, "$embedded · $fullscreen")
    WINDOWED_GESTURE_ROWS.zip(FULLSCREEN_GESTURE_ROWS).forEach { (windowed, full) ->
        val (key, title) = windowed
        val selected = (if (preferences[key] == true) 1 else 0) + (if (preferences[full.first] == true) 2 else 0)
        val label = stringResource(title)
        SettingsRow(label, summary = labels[selected], tag = "gesture-$key", onClick = { editing = key })
        if (editing == key) RadioDialog(
            title = label,
            options = labels.mapIndexed { index, text -> text to (index == selected) },
            tag = "gesture-scope-dialog",
            onSelect = { index ->
                onChange(mapOf(key to (index and 1 != 0), full.first to (index and 2 != 0)))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private val SPONSOR_ROWS = listOf(
    PreferenceKeys.SKIP_SPONSORS to R.string.skip_sponsors,
    PreferenceKeys.SKIP_SELF_PROMO to R.string.skip_sponsors_selfpromo,
    PreferenceKeys.SKIP_INTERACTION to R.string.skip_sponsors_interaction,
    PreferenceKeys.SKIP_INTRO to R.string.skip_sponsors_intro,
    PreferenceKeys.SKIP_OUTRO to R.string.skip_sponsors_outro,
    PreferenceKeys.SKIP_PREVIEW to R.string.skip_sponsors_preview,
    PreferenceKeys.SKIP_MUSIC_OFFTOPIC to R.string.skip_sponsors_music_offtopic,
    PreferenceKeys.SKIP_FILLER to R.string.skip_sponsors_filler,
    PreferenceKeys.SKIP_POI_HIGHLIGHT to R.string.skip_sponsors_highlight,
    PreferenceKeys.SPONSOR_COUNTDOWN to R.string.sponsor_countdown,
)

@Composable
private fun SponsorBlockToggles(preferences: Map<String, Boolean>, onToggle: (String, Boolean) -> Unit) {
    SPONSOR_ROWS.forEach { (key, title) ->
        SettingsToggle(stringResource(title), "", key, preferences, onToggle)
    }
}

class HapticsSettings(
    initial: Int,
    private val commit: (Int) -> Unit,
) {
    var strength by mutableStateOf(initial)
        private set

    fun onChange(value: Int) {
        val next = value.coerceIn(0, 100)
        strength = next
        commit(next)
    }
}
