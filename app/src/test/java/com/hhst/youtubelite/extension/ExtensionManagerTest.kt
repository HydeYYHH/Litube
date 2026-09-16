package com.hhst.youtubelite.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Field storage for extension preferences. */
class ExtensionManagerTest {

    private lateinit var store: MemoryPrefStore
    private lateinit var manager: ExtensionManager

    @Before
    fun setUp() {
        store = MemoryPrefStore()
        manager = ExtensionManager(store)
    }

    @Test
    fun seedsDefaultsOnFirstLaunch() {
        for ((key, expected) in PreferenceKeys.DEFAULTS) {
            assertEquals(expected, manager.isEnabled(key))
        }
    }

    @Test
    fun defaultsMatchLayout() {
        val expected = mapOf(
            "enable_display_dislikes" to true,
            "enable_show_likes" to true,
            "enable_hide_shorts" to false,
            "skip_sponsors" to true,
            "skip_self_promo" to true,
            "skip_poi_highlight" to true,
            "sponsor_countdown" to true,
            "remember_last_position" to true,
            "remember_quality" to true,
            "enable_background_play" to true,
            "enable_pip" to true,
            "enable_in_app_mini_player" to true,
            "remember_resize_mode" to false,
            "remember_playback_speed" to false,
            "use_original_title" to false,
            "gesture_tap_windowed" to true,
            "gesture_tap_fullscreen" to true,
            "gesture_double_tap_windowed" to true,
            "gesture_double_tap_fullscreen" to true,
            "gesture_long_press_windowed" to true,
            "gesture_long_press_fullscreen" to true,
            "gesture_brightness_windowed" to true,
            "gesture_brightness_fullscreen" to true,
            "gesture_volume_windowed" to true,
            "gesture_volume_fullscreen" to true,
            "gesture_seek_windowed" to true,
            "gesture_seek_fullscreen" to true,
            "gesture_fullscreen_windowed" to true,
            "gesture_fullscreen_fullscreen" to true,
        )
        assertEquals(expected, PreferenceKeys.DEFAULTS)
    }

    @Test
    fun setEnabled_persistsAndRoundTrips() {
        val key = PreferenceKeys.ENABLE_HIDE_SHORTS
        assertFalse(manager.isEnabled(key))
        manager.setEnabled(key, true)
        assertTrue(manager.isEnabled(key))
        assertTrue(ExtensionManager(store).isEnabled(key))
    }

    @Test
    fun setEnabled_sameValue_doesNotBumpVersion() {
        val key = PreferenceKeys.ENABLE_DISPLAY_DISLIKES
        val before = manager.version()
        manager.setEnabled(key, manager.isEnabled(key))
        assertEquals(before, manager.version())
    }

    @Test
    fun setEnabled_change_bumpsVersion() {
        val before = manager.version()
        manager.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true)
        assertEquals(before + 1, manager.version())
    }

    @Test
    fun resetToDefault_restoresAllAndBumpsVersion() {
        manager.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true)
        manager.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
        val afterEdits = manager.version()
        manager.resetToDefault()
        assertFalse(manager.isEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS))
        assertTrue(manager.isEnabled(PreferenceKeys.SKIP_SPONSORS))
        assertTrue(manager.version() > afterEdits)
    }

    @Test
    fun resetToDefault_notifiesOnceWithWildcard() {
        val keys = mutableListOf<String>()
        manager.addOnChangedListener { keys += it }
        manager.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true)
        manager.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
        keys.clear()
        manager.resetToDefault()
        assertEquals(listOf("*"), keys)
    }

    @Test
    fun allPreferences_containsEveryDefaultKey() {
        val snapshot = manager.allPreferences()
        assertEquals(PreferenceKeys.DEFAULTS.keys, snapshot.keys)
    }

    @Test
    fun usesPreferencesPrefixInStore() {
        manager.setEnabled(PreferenceKeys.REMEMBER_QUALITY, false)
        assertTrue(store.contains("preferences:remember_quality"))
        assertFalse(store.getBool("preferences:remember_quality", true))
    }

    @Test
    fun migratesLegacyGestureMasterSwitch() {
        val fresh = MemoryPrefStore()
        fresh.putBool("preferences:enable_player_gestures", false)
        val migrated = ExtensionManager(fresh)
        for (key in PreferenceKeys.GESTURE_KEYS) {
            assertFalse(migrated.isEnabled(key))
        }
    }

    @Test
    fun doesNotOverwriteExistingGestureKeysOnMigration() {
        val fresh = MemoryPrefStore()
        fresh.putBool("preferences:${PreferenceKeys.GESTURE_TAP_WINDOWED}", false)
        val migrated = ExtensionManager(fresh)
        assertFalse(migrated.isEnabled(PreferenceKeys.GESTURE_TAP_WINDOWED))
        assertTrue(migrated.isEnabled(PreferenceKeys.GESTURE_TAP_FULLSCREEN))
    }

    @Test
    fun unknownKey_defaultsToFalse() {
        assertFalse(manager.isEnabled("not_a_real_key"))
    }
}
