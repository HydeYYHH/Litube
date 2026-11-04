package com.hhst.youtubelite.extractor

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject

/** One-way capture: never delays, replaces or fabricates browser responses. */
class BrowserPlayerResponses(private val sessions: YoutubeSessionProvider) {
    private data class Entry(val scope: String, val semantics: String, val body: String, val at: Long,
                             val generation: Long, val document: () -> Long)
    private val entries = LinkedHashMap<String, Entry>(8, .75f, true)

    fun install(view: WebView, document: () -> Long) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, "NativePlayerCapture", setOf("https://www.youtube.com", "https://m.youtube.com")) { _, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.scheme != "https" || origin.host !in setOf("www.youtube.com", "m.youtube.com")) return@addWebMessageListener
            val text = message.data ?: return@addWebMessageListener
            if (text.length > 2 * 1024 * 1024) return@addWebMessageListener
            runCatching {
                val json = parseJson(text) as JsonObject
                if (json.get("generation")?.asLongNum != document() || json.get("session")?.asText != sessions.captureStamp()) return@runCatching
                json.objOrNull("configuration")?.let {
                    if (it.toString().length <= 256 * 1024) {
                        sessions.acceptBrowserConfig(it, document(), document)
                    }
                }
                if (json.get("session")?.asText != sessions.captureStamp()) return@runCatching
                val request = json.get("request")?.asText ?: return@runCatching
                val body = json.get("response")?.asText ?: return@runCatching
                if (body.toByteArray().size > 2 * 1024 * 1024) return@runCatching
                val parsed = parseJson(request) as JsonObject
                val response = parseJson(body) as JsonObject
                val video = VideoId.parse(parsed.get("videoId")?.asText) ?: return@runCatching
                if (response.objOrNull("videoDetails")?.get("videoId")?.asText != video || response.objOrNull("playabilityStatus")?.get("status")?.asText != "OK") return@runCatching
                val client = parsed.objOrNull("context")?.objOrNull("client") ?: return@runCatching
                if (client.get("clientName")?.asText != "WEB") return@runCatching
                val key = sessions.captureStamp() + ":" + request
                synchronized(entries) {
                    entries[key] = Entry(sessions.captureStamp(), parsed.toString(), body, System.currentTimeMillis(), document(), document)
                    while (entries.values.sumOf { it.body.toByteArray().size } > 8 * 1024 * 1024) entries.remove(entries.keys.first())
                }
            }
        }
    }

    fun get(url: String, method: String, body: String?, session: YoutubeWebSession): String? {
        if (method != "POST" || !url.startsWith("https://www.youtube.com/youtubei/v1/player")) return null
        if (body == null) return null
        val semantics = runCatching { (parseJson(body) as JsonObject).toString() }.getOrNull() ?: return null
        val hint = sessions.captureStamp()
        return synchronized(entries) {
            entries.values.firstOrNull { it.scope == hint && it.semantics == semantics && it.generation == it.document() && System.currentTimeMillis() - it.at < 120_000 && sessions.isCurrent(session) }?.body
        }
    }
}
