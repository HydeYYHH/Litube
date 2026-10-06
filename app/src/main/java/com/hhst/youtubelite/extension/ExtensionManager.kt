package com.hhst.youtubelite.extension

import android.util.Log

/** MMKV-backed toggle store under the `preferences:` prefix. */
class ExtensionManager(private val store: PrefStore) {

    init {
        seedDefaults()
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
                .onFailure { Log.w(TAG, "preference listener failed key=$key", it) }
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
            bumpVersion()
            notifyChanged("*")
        }
    }

    fun allPreferences(): Map<String, Boolean> =
        PreferenceKeys.DEFAULTS.keys.associateWith { isEnabled(it) }

    fun hapticStrength(): Int = store.getLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), 30L).coerceIn(0, 100).toInt()

    fun setHapticStrength(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == hapticStrength()) return
        store.putLong(prefKey(PreferenceKeys.HAPTIC_STRENGTH), next.toLong())
        bumpVersion()
        notifyChanged(PreferenceKeys.HAPTIC_STRENGTH)
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
