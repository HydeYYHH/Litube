package com.hhst.youtubelite.extension

import com.tencent.mmkv.MMKV

/** Boolean/long store used by [ExtensionManager]. */
interface PrefStore {
    fun contains(key: String): Boolean
    fun getBool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
}

class MmkvPrefStore(private val kv: MMKV) : PrefStore {
    override fun contains(key: String): Boolean = kv.contains(key)

    override fun getBool(key: String, default: Boolean): Boolean =
        kv.decodeBool(key, default)

    override fun putBool(key: String, value: Boolean) {
        kv.encode(key, value)
    }

    override fun getLong(key: String, default: Long): Long =
        kv.decodeLong(key, default)

    override fun putLong(key: String, value: Long) {
        kv.encode(key, value)
    }
}
