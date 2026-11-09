package com.hhst.youtubelite.extension

import android.util.Log
import com.hhst.youtubelite.diagnostics.AppLog

/** [PrefStore]-backed toggle store under the `preferences:` prefix. */
class ExtensionManager(private val store: PrefStore) {

    init {
        seedDefaults()
        AppLog.registerSnapshot("controlled_settings") { allPreferences() + mapOf("haptic_strength" to hapticStrength()) }
    }

    private val listeners = mutableListOf<(String) -> Unit>()

    /**
     * Notifies [listener] whenever a toggle actually flips. Listeners that
     * touch WebView should hop to the main thread themselves.
     */
    fun addOnChangedListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeOnChangedListener(listener: (String) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    private fun notifyChanged(key: String) {
        val toCall = synchronized(listeners) { listeners.toList() }
        toCall.forEach {
            runCatching { it(key) }
                .onFailure { AppLog.event(AppLog.Category.EXTENSION, "preference_listener_failed", mapOf("key" to key.takeIf { it in PreferenceKeys.DEFAULTS || it == "*" }), it) }
        }
    }

    fun isEnabled(key: String): Boolean {
        val pref = prefKey(key)
        val default = PreferenceKeys.DEFAULTS[key] == true
        return store.getBool(pref, default)
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val pref = prefKey(key)
        val default = PreferenceKeys.DEFAULTS[key] == true
        val previous = if (store.contains(pref)) store.getBool(pref, default) else default
        store.putBool(pref, enabled)
        if (previous != enabled) {
            if (key in PreferenceKeys.DEFAULTS) AppLog.event(AppLog.Category.EXTENSION, "preference_changed", mapOf("key" to key, "before" to previous, "after" to enabled))
            bumpVersion()
            notifyChanged(key)
        }
    }

    fun resetToDefault() {
        val changedKeys = mutableListOf<String>()
        if (hapticStrength() != 30) changedKeys += PreferenceKeys.HAPTIC_STRENGTH
        store.putLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), 30L)
        for ((key, value) in PreferenceKeys.DEFAULTS) {
            val pref = prefKey(key)
            if (!store.contains(pref) || store.getBool(pref, value) != value) {
                changedKeys += key
            }
            store.putBool(pref, value)
        }
        if (changedKeys.isNotEmpty()) {
            AppLog.event(AppLog.Category.EXTENSION, "preferences_reset", mapOf("changed_keys" to changedKeys, "changed_count" to changedKeys.size))
            bumpVersion()
            notifyChanged("*")
        }
    }

    fun allPreferences(): Map<String, Boolean> =
        PreferenceKeys.DEFAULTS.keys.associateWith { isEnabled(it) }

    fun importPreferences(values: Map<String, Boolean>, haptic: Int?) {
        val changedKeys = mutableListOf<String>()
        if (haptic != null) {
            val next = haptic.coerceIn(0, 100)
            if (next != hapticStrength()) changedKeys += PreferenceKeys.HAPTIC_STRENGTH
            store.putLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), next.toLong())
        }
        for ((key, value) in values) {
            if (key !in PreferenceKeys.DEFAULTS) continue
            val pref = prefKey(key)
            if (!store.contains(pref) || store.getBool(pref, value) != value) {
                changedKeys += key
            }
            store.putBool(pref, value)
        }
        if (changedKeys.isNotEmpty()) {
            AppLog.event(AppLog.Category.EXTENSION, "preferences_imported", mapOf("changed_keys" to changedKeys, "changed_count" to changedKeys.size))
            bumpVersion()
            notifyChanged("*")
        }
    }

    fun hapticStrength(): Int = store.getLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), 30L).coerceIn(0, 100).toInt()

    fun setHapticStrength(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == hapticStrength()) return
        val previous = hapticStrength()
        store.putLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), next.toLong())
        AppLog.event(AppLog.Category.EXTENSION, "preference_changed", mapOf("key" to PreferenceKeys.HAPTIC_STRENGTH, "before" to previous, "after" to next))
        bumpVersion()
        notifyChanged(PreferenceKeys.HAPTIC_STRENGTH)
    }

    /** Persisted appearance; System follows the OS. Applied by [AppAppearance] at startup. */
    fun themeMode(): com.hhst.youtubelite.ui.theme.ThemeMode =
        com.hhst.youtubelite.ui.theme.ThemeMode.entries.getOrElse(
            store.getLong(prefKey(PreferenceKeys.THEME_MODE), 0L).toInt()) { com.hhst.youtubelite.ui.theme.ThemeMode.System }

    fun setThemeMode(mode: com.hhst.youtubelite.ui.theme.ThemeMode) {
        if (mode == themeMode()) return
        val previous = themeMode()
        store.putLong(prefKey(PreferenceKeys.THEME_MODE), mode.ordinal.toLong())
        AppLog.event(AppLog.Category.EXTENSION, "preference_changed",
            mapOf("key" to PreferenceKeys.THEME_MODE, "before" to previous.name, "after" to mode.name))
        bumpVersion()
        notifyChanged(PreferenceKeys.THEME_MODE)
    }

    /** Persisted app language as a BCP-47 tag; empty means "follow the system". */
    fun appLanguage(): String = store.getString(prefKey(PreferenceKeys.APP_LANGUAGE), "")

    fun setAppLanguage(tag: String) {
        if (tag == appLanguage()) return
        val previous = appLanguage()
        store.putString(prefKey(PreferenceKeys.APP_LANGUAGE), tag)
        AppLog.event(AppLog.Category.EXTENSION, "preference_changed",
            mapOf("key" to PreferenceKeys.APP_LANGUAGE, "before" to previous, "after" to tag))
        bumpVersion()
        notifyChanged(PreferenceKeys.APP_LANGUAGE)
    }

    fun version(): Long = store.getLong(KEY_VERSION, 0L)

    private fun seedDefaults() {
        migrateGestures()
        for ((key, value) in PreferenceKeys.DEFAULTS) {
            val pref = prefKey(key)
            if (!store.contains(pref)) {
                store.putBool(pref, value)
            }
        }
    }

    private fun migrateGestures() {
        if (PreferenceKeys.GESTURE_KEYS.any { store.contains(prefKey(it)) }) return
        val legacy = prefKey(PreferenceKeys.ENABLE_PLAYER_GESTURES)
        val enabled = if (store.contains(legacy)) {
            store.getBool(legacy, true)
        } else {
            true
        }
        for (gesture in PreferenceKeys.GESTURE_KEYS) {
            store.putBool(prefKey(gesture), enabled)
        }
    }

    private fun bumpVersion() {
        store.putLong(KEY_VERSION, version() + 1L)
    }

    private fun prefKey(key: String): String = "$PREFIX$key"

    private companion object {
        const val TAG = "ExtensionManager"
        const val PREFIX = "preferences:"
        const val KEY_VERSION = "preferences:version"
    }
}
