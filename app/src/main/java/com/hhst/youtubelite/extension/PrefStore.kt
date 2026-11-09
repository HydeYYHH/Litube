package com.hhst.youtubelite.extension

interface PrefStore {
    fun contains(key: String): Boolean
    fun getBool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}
