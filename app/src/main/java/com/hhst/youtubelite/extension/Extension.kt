package com.hhst.youtubelite.extension

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.hhst.youtubelite.R

/** Settings catalog node: group (children) or toggle (key). */
data class Extension(
    val id: String,
    val key: String? = null,
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int = 0,
    val children: List<Extension> = emptyList(),
    @param:StringRes val summary: Int = 0,
) {
    val isGroup: Boolean get() = children.isNotEmpty()

    companion object {
        fun catalog(): List<Extension> = listOf(
            group(
                id = "interface",
                title = R.string.interface_category,
                icon = R.drawable.ic_settings,
                children = listOf(
                    toggle(
                        PreferenceKeys.ENABLE_DISPLAY_DISLIKES,
                        R.string.display_dislikes,
                        R.string.display_dislikes_summary,
                    ),
                    // Same RYD request as dislikes, so the privacy note applies here too.
                    toggle(
                        PreferenceKeys.ENABLE_SHOW_LIKES,
                        R.string.show_likes,
                        R.string.display_dislikes_summary,
                    ),
                    toggle(PreferenceKeys.ENABLE_HIDE_SHORTS, R.string.hide_shorts),
                ),
            ),
            group(
                id = "player",
                title = R.string.player,
                icon = R.drawable.ic_play,
                children = listOf(
                    toggle(PreferenceKeys.REMEMBER_LAST_POSITION, R.string.remember_last_position),
                    toggle(PreferenceKeys.REMEMBER_QUALITY, R.string.remember_quality),
                    toggle(PreferenceKeys.REMEMBER_PLAYBACK_SPEED, R.string.remember_playback_speed),
                    toggle(PreferenceKeys.REMEMBER_RESIZE_MODE, R.string.remember_resize_mode),
                    toggle(PreferenceKeys.USE_ORIGINAL_TITLE, R.string.use_original_title),
                ),
            ),
            group(
                id = "gesture",
                title = R.string.gesture,
                icon = R.drawable.ic_gesture,
                children = listOf(
                    group(
                        id = "gesture_windowed",
                        title = R.string.enable_in_embedded,
                        children = listOf(
                            toggle(PreferenceKeys.GESTURE_TAP_WINDOWED, R.string.gesture_single_tap),
                            toggle(PreferenceKeys.GESTURE_DOUBLE_TAP_WINDOWED, R.string.gesture_double_tap),
                            toggle(PreferenceKeys.GESTURE_LONG_PRESS_WINDOWED, R.string.gesture_long_press_speed),
                            toggle(PreferenceKeys.GESTURE_BRIGHTNESS_WINDOWED, R.string.brightness),
                            toggle(PreferenceKeys.GESTURE_VOLUME_WINDOWED, R.string.volume),
                            toggle(PreferenceKeys.GESTURE_SEEK_WINDOWED, R.string.gesture_seek),
                            toggle(PreferenceKeys.GESTURE_FULLSCREEN_WINDOWED, R.string.gesture_fullscreen_swipe),
                        ),
                    ),
                    group(
                        id = "gesture_fullscreen",
                        title = R.string.enable_in_fullscreen,
                        children = listOf(
                            toggle(PreferenceKeys.GESTURE_TAP_FULLSCREEN, R.string.gesture_single_tap),
                            toggle(PreferenceKeys.GESTURE_DOUBLE_TAP_FULLSCREEN, R.string.gesture_double_tap),
                            toggle(PreferenceKeys.GESTURE_LONG_PRESS_FULLSCREEN, R.string.gesture_long_press_speed),
                            toggle(PreferenceKeys.GESTURE_BRIGHTNESS_FULLSCREEN, R.string.brightness),
                            toggle(PreferenceKeys.GESTURE_VOLUME_FULLSCREEN, R.string.volume),
                            toggle(PreferenceKeys.GESTURE_SEEK_FULLSCREEN, R.string.gesture_seek),
                            toggle(PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN, R.string.gesture_fullscreen_swipe),
                        ),
                    ),
                ),
            ),
            group(
                id = "background",
                title = R.string.background_mini_player,
                icon = R.drawable.ic_pip,
                children = listOf(
                    toggle(PreferenceKeys.ENABLE_PIP, R.string.pip),
                    toggle(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, R.string.in_app_mini_player),
                    toggle(PreferenceKeys.ENABLE_BACKGROUND_PLAY, R.string.background_play),
                ),
            ),
            group(
                id = "sponsorblock",
                title = R.string.sponsorblock,
                icon = R.drawable.ic_block,
                children = listOf(
                    toggle(PreferenceKeys.SKIP_SPONSORS, R.string.skip_sponsors),
                    toggle(PreferenceKeys.SKIP_SELF_PROMO, R.string.skip_sponsors_selfpromo),
                    toggle(PreferenceKeys.SKIP_POI_HIGHLIGHT, R.string.skip_sponsors_highlight),
                    toggle(PreferenceKeys.SPONSOR_COUNTDOWN, R.string.sponsor_countdown),
                ),
            ),
        )

        fun group(
            id: String,
            @StringRes title: Int,
            @DrawableRes icon: Int = 0,
            children: List<Extension>,
        ): Extension = Extension(id = id, title = title, icon = icon, children = children)

        fun toggle(
            key: String,
            @StringRes title: Int,
            @StringRes summary: Int = 0,
        ): Extension =
            Extension(id = key, key = key, title = title, summary = summary)
    }
}
