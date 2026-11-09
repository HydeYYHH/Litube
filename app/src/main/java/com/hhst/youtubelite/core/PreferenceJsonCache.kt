package com.hhst.youtubelite.core

import com.hhst.youtubelite.diagnostics.AppLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

class PreferenceJsonCache(
    private val kv: PreferenceStore,
) : JsonCache {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun <T : Any> get(key: String, type: Class<T>): T? {
        return getWithExpiry(key, type)?.first
    }

    override fun <T : Any> getWithExpiry(key: String, type: Class<T>): Pair<T, Long?>? {
        val raw = kv.readString(key) ?: return null
        if (raw.isEmpty()) return null
        return try {
            val entry = JSONObject(raw)
            val until = entry.getLong("until")
            if (until <= System.currentTimeMillis()) {
                // Expired entries must actually go — progress keys would
                // otherwise accumulate one dead entry per watched video.
                AppLog.detail(AppLog.Category.STORAGE, "cache_expired", mapOf("namespace" to key.substringBefore(':').take(32)))
                kv.remove(key)
                return null
            }
            val decoded: T = if (type == String::class.java) {
                @Suppress("UNCHECKED_CAST")
                entry.getString("str") as T
            } else {
                decode(entry.getJSONObject("obj").toString(), type)
            }
            decoded to until
        } catch (e: Exception) {
            kv.remove(key)
            AppLog.event(AppLog.Category.STORAGE, "cache_corrupt", mapOf("namespace" to key.substringBefore(':').take(32), "value_type" to type.simpleName), e)
            null
        }
    }

    override fun put(key: String, value: Any, ttlMs: Long) {
        val entry = JSONObject()
        entry.put("until", System.currentTimeMillis() + ttlMs)
        try {
            if (value is String) entry.put("str", value)
            else entry.put("obj", JSONObject(json.encodeToString(serializerOf(value), value)))
            kv.putString(key, entry.toString())
        } catch (e: Exception) {
            AppLog.event(AppLog.Category.STORAGE, "cache_write_failed",
                mapOf("namespace" to key.substringBefore(':').take(32), "value_type" to value.javaClass.simpleName), e, critical = true)
        }
    }

    override fun invalidate(key: String) {
        kv.remove(key)
    }

    private fun <T : Any> decode(raw: String, type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        val serializer = serializerFor(type) as KSerializer<T>
        return json.decodeFromString(serializer, raw)
    }

    private fun serializerOf(value: Any): KSerializer<Any> {
        @Suppress("UNCHECKED_CAST")
        return serializerFor(value::class.java) as KSerializer<Any>
    }

    private fun serializerFor(type: Class<*>): KSerializer<*> = when (type) {
        com.hhst.youtubelite.extractor.Metadata::class.java -> com.hhst.youtubelite.extractor.Metadata.serializer()
        com.hhst.youtubelite.extractor.Stream::class.java -> com.hhst.youtubelite.extractor.Stream.serializer()
        com.hhst.youtubelite.extractor.ChapterList::class.java -> com.hhst.youtubelite.extractor.ChapterList.serializer()
        com.hhst.youtubelite.player.engine.ResumePoint::class.java -> com.hhst.youtubelite.player.engine.ResumePoint.serializer()
        else -> error("Unsupported cache record: ${type.name}")
    }
}
