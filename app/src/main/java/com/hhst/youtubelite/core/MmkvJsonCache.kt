package com.hhst.youtubelite.core

import android.util.Log
import com.hhst.youtubelite.diagnostics.AppLog
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
                AppLog.detail(AppLog.Category.STORAGE, "cache_expired", mapOf("namespace" to key.substringBefore(':').take(32)))
                kv.removeValueForKey(key)
                return null
            }
            gson.fromJson(entry.json, type)?.let { it to entry.until }
        } catch (e: RuntimeException) {
            AppLog.event(AppLog.Category.STORAGE, "cache_corrupt", mapOf("namespace" to key.substringBefore(':').take(32), "value_type" to type.simpleName), e)
            null
        }
    }

    override fun put(key: String, value: Any, ttlMs: Long) {
        val entry = Entry(System.currentTimeMillis() + ttlMs, gson.toJson(value))
        if (!kv.encode(key, gson.toJson(entry))) AppLog.event(AppLog.Category.STORAGE, "cache_write_failed",
            mapOf("namespace" to key.substringBefore(':').take(32), "cache_file_bytes" to kv.actualSize()), critical = true)
    }

    override fun invalidate(key: String) {
        kv.removeValueForKey(key)
    }

    private companion object {
        const val TAG = "MmkvJsonCache"
    }
}
