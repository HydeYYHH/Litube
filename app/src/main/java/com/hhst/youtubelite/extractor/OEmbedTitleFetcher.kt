package com.hhst.youtubelite.extractor

import com.google.gson.Gson
import java.net.URLEncoder
import com.google.gson.JsonObject
import com.hhst.youtubelite.core.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the original (untranslated) video title via the locale-agnostic
 * oEmbed endpoint.
 */
class OEmbedTitleFetcher(
    client: OkHttpClient,
    private val gson: Gson = Gson(),
) {
    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val titles = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > 64
    }

    /** Failed lookups, so an unavailable title does not re-issue the request per playback. */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Ids with a lookup currently running; concurrent first lookups would each hit oEmbed. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The canonical title in the video's own language, or null when unavailable. */
    fun fetchOriginalTitle(videoId: String): String? {
        val id = VideoId.parse(videoId) ?: return null
        synchronized(titles) { titles[id] }?.let { return it }
        failures[id]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return null
            failures.remove(id, failedAt)
        }
        // Losers of a concurrent first lookup skip this round and re-check the
        // caches on their next call instead of issuing a duplicate request.
        if (!inFlight.add(id)) return null
        try {
            val title = runCatching {
                val watch = VideoId.watchUrl(id)
                val request = Request.Builder()
                    .url(
                        "https://www.youtube.com/oembed?url=" +
                            URLEncoder.encode(watch, Charsets.UTF_8.name()),
                    )
                    .header("User-Agent", Constants.userAgent())
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string() ?: return@runCatching null
                    val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                    json?.get("title")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (title == null) {
                if (failures.size >= MAX_FAILURES) {
                    val cutoff = System.currentTimeMillis() - FAILURE_TTL_MS
                    failures.entries.removeIf { it.value < cutoff }
                }
                failures[id] = System.currentTimeMillis()
                return null
            }
            failures.remove(id)
            synchronized(titles) { titles[id] = title }
            return title
        } finally {
            inFlight.remove(id)
        }
    }

    private companion object {
        /** How long a failed lookup suppresses retries. */
        val FAILURE_TTL_MS = TimeUnit.MINUTES.toMillis(5)

        /** Upper bound on remembered failures; expired entries are pruned past it. */
        const val MAX_FAILURES = 64
    }
}
