package com.hhst.youtubelite.core

import android.content.Context
import android.content.SharedPreferences
import com.hhst.youtubelite.extension.PrefStore

/** Immediately visible preferences with asynchronous disk persistence. */
class PreferenceStore private constructor(private val prefs: SharedPreferences) : PrefStore {
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun getBool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getString(key: String, default: String): String = prefs.getString(key, null) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun readString(key: String): String? = prefs.getString(key, null)

    @androidx.annotation.WorkerThread
    fun writeStringDurably(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        check(editor.commit()) { "preference_write_failed" }
    }

    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun keys(): Array<String> = prefs.all.keys.toTypedArray()

    fun entryCount(): Int = prefs.all.size

    companion object {
        private const val APP_FILE = "app_prefs"

        fun of(context: Context, name: String): PreferenceStore =
            PreferenceStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))

        fun app(context: Context): PreferenceStore = of(context, APP_FILE)
    }
}
