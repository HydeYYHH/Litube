package com.hhst.youtubelite.core

import android.util.Log
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

/** [JsonCache] backed by MMKV with Gson and wall-clock TTL. */
class MmkvJsonCache(
    private val kv: MMKV,
    private val gson: Gson = Gson(),
) : JsonCache {

    private data class Entry(val until: Long, val json: String)

    override fun <T> get(key: String, type: Class<T>): T? {
        return getWithExpiry(key, type)?.first
    }

    override fun <T> getWithExpiry(key: String, type: Class<T>): Pair<T, Long?>? {
        val raw = kv.decodeString(key, null)
        if (raw.isNullOrEmpty()) return null
        return try {
            val entry = gson.fromJson(raw, Entry::class.java) ?: return null
            if (entry.until <= System.currentTimeMillis()) {
                // Expired entries must actually go — progress keys would
                // otherwise accumulate one dead entry per watched video.
                Log.d(TAG, "expired key=$key")
                kv.removeValueForKey(key)
                return null
            }
            gson.fromJson(entry.json, type)?.let { it to entry.until }
        } catch (e: RuntimeException) {
            Log.w(TAG, "decode failed key=$key type=${type.simpleName}", e)
            null
        }
    }

    override fun put(key: String, value: Any, ttlMs: Long) {
        val entry = Entry(System.currentTimeMillis() + ttlMs, gson.toJson(value))
        kv.encode(key, gson.toJson(entry))
    }

    override fun invalidate(key: String) {
        kv.removeValueForKey(key)
    }

    private companion object {
        const val TAG = "MmkvJsonCache"
    }
}
