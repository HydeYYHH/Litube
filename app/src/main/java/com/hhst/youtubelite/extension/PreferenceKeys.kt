package com.hhst.youtubelite.extension

/** Preference keys and first-launch defaults. Strings match the existing MMKV layout. */
object PreferenceKeys {
    const val HAPTIC_STRENGTH = "haptic_strength"
    const val ENABLE_DISPLAY_DISLIKES = "enable_display_dislikes"
    const val ENABLE_HIDE_SHORTS = "enable_hide_shorts"
    const val REMEMBER_QUALITY = "remember_quality"
    const val REMEMBER_PLAYBACK_SPEED = "remember_playback_speed"
    const val REMEMBER_LAST_POSITION = "remember_last_position"
    const val REMEMBER_RESIZE_MODE = "remember_resize_mode"
    const val USE_ORIGINAL_TITLE = "use_original_title"

    const val ENABLE_BACKGROUND_PLAY = "enable_background_play"
    const val ENABLE_PIP = "enable_pip"
    const val ENABLE_IN_APP_MINI_PLAYER = "enable_in_app_mini_player"

    const val SKIP_SPONSORS = "skip_sponsors"
    const val SKIP_SELF_PROMO = "skip_self_promo"
    const val SKIP_INTERACTION = "skip_interaction"
    const val SKIP_INTRO = "skip_intro"
    const val SKIP_OUTRO = "skip_outro"
    const val SKIP_PREVIEW = "skip_preview"
    const val SKIP_MUSIC_OFFTOPIC = "skip_music_offtopic"
    const val SKIP_FILLER = "skip_filler"
    const val SKIP_POI_HIGHLIGHT = "skip_poi_highlight"
    const val SPONSOR_COUNTDOWN = "sponsor_countdown"

    const val ENABLE_SHOW_LIKES = "enable_show_likes"

    /** Legacy master switch; only used to seed [GESTURE_KEYS]. */
    const val ENABLE_PLAYER_GESTURES = "enable_player_gestures"

    const val GESTURE_TAP_WINDOWED = "gesture_tap_windowed"
    const val GESTURE_TAP_FULLSCREEN = "gesture_tap_fullscreen"
    const val GESTURE_DOUBLE_TAP_WINDOWED = "gesture_double_tap_windowed"
    const val GESTURE_DOUBLE_TAP_FULLSCREEN = "gesture_double_tap_fullscreen"
    const val GESTURE_LONG_PRESS_WINDOWED = "gesture_long_press_windowed"
    const val GESTURE_LONG_PRESS_FULLSCREEN = "gesture_long_press_fullscreen"
    const val GESTURE_BRIGHTNESS_WINDOWED = "gesture_brightness_windowed"
    const val GESTURE_BRIGHTNESS_FULLSCREEN = "gesture_brightness_fullscreen"
    const val GESTURE_VOLUME_WINDOWED = "gesture_volume_windowed"
    const val GESTURE_VOLUME_FULLSCREEN = "gesture_volume_fullscreen"
    const val GESTURE_SEEK_WINDOWED = "gesture_seek_windowed"
    const val GESTURE_SEEK_FULLSCREEN = "gesture_seek_fullscreen"
    const val GESTURE_FULLSCREEN_WINDOWED = "gesture_fullscreen_windowed"
    const val GESTURE_FULLSCREEN_FULLSCREEN = "gesture_fullscreen_fullscreen"

    val GESTURE_KEYS: List<String> = listOf(
        GESTURE_TAP_WINDOWED,
        GESTURE_TAP_FULLSCREEN,
        GESTURE_DOUBLE_TAP_WINDOWED,
        GESTURE_DOUBLE_TAP_FULLSCREEN,
        GESTURE_LONG_PRESS_WINDOWED,
        GESTURE_LONG_PRESS_FULLSCREEN,
        GESTURE_BRIGHTNESS_WINDOWED,
        GESTURE_BRIGHTNESS_FULLSCREEN,
        GESTURE_VOLUME_WINDOWED,
        GESTURE_VOLUME_FULLSCREEN,
        GESTURE_SEEK_WINDOWED,
        GESTURE_SEEK_FULLSCREEN,
        GESTURE_FULLSCREEN_WINDOWED,
        GESTURE_FULLSCREEN_FULLSCREEN,
    )

    val DEFAULTS: Map<String, Boolean> = mapOf(
        ENABLE_DISPLAY_DISLIKES to true,
        ENABLE_SHOW_LIKES to true,
        ENABLE_HIDE_SHORTS to false,
        SKIP_SPONSORS to true,
        SKIP_SELF_PROMO to true,
        SKIP_INTERACTION to true,
        SKIP_INTRO to true,
        SKIP_OUTRO to true,
        SKIP_PREVIEW to true,
        SKIP_MUSIC_OFFTOPIC to true,
        SKIP_FILLER to true,
        SKIP_POI_HIGHLIGHT to true,
        SPONSOR_COUNTDOWN to true,
        REMEMBER_LAST_POSITION to true,
        REMEMBER_QUALITY to true,
        ENABLE_BACKGROUND_PLAY to true,
        ENABLE_PIP to true,
        ENABLE_IN_APP_MINI_PLAYER to true,
        REMEMBER_RESIZE_MODE to false,
        REMEMBER_PLAYBACK_SPEED to false,
        USE_ORIGINAL_TITLE to false,
        GESTURE_TAP_WINDOWED to true,
        GESTURE_TAP_FULLSCREEN to true,
        GESTURE_DOUBLE_TAP_WINDOWED to true,
        GESTURE_DOUBLE_TAP_FULLSCREEN to true,
        GESTURE_LONG_PRESS_WINDOWED to true,
        GESTURE_LONG_PRESS_FULLSCREEN to true,
        GESTURE_BRIGHTNESS_WINDOWED to true,
        GESTURE_BRIGHTNESS_FULLSCREEN to true,
        GESTURE_VOLUME_WINDOWED to true,
        GESTURE_VOLUME_FULLSCREEN to true,
        GESTURE_SEEK_WINDOWED to true,
        GESTURE_SEEK_FULLSCREEN to true,
        GESTURE_FULLSCREEN_WINDOWED to true,
        GESTURE_FULLSCREEN_FULLSCREEN to true,
    )
}
