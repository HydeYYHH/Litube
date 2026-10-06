package com.hhst.youtubelite.extractor

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.grack.nanojson.JsonParser as NanoJsonParser
import com.google.gson.JsonParser
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext

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
                val json = JsonParser.parseString(text).asJsonObject
                if (json.get("generation")?.asLong != document() || json.get("session")?.asString != sessions.captureStamp()) return@runCatching
                json.getAsJsonObject("configuration")?.let {
                    if (it.toString().length <= 256 * 1024) {
                        sessions.acceptBrowserConfig(NanoJsonParser.`object`().from(it.toString()), document(), document)
                    }
                }
                if (json.get("session")?.asString != sessions.captureStamp()) return@runCatching
                val request = json.get("request")?.asString ?: return@runCatching
                val body = json.get("response")?.asString ?: return@runCatching
                if (body.toByteArray().size > 2 * 1024 * 1024) return@runCatching
                val parsed = JsonParser.parseString(request).asJsonObject
                val response = JsonParser.parseString(body).asJsonObject
                val video = VideoId.parse(parsed.get("videoId")?.asString) ?: return@runCatching
                if (response.getAsJsonObject("videoDetails")?.get("videoId")?.asString != video || response.getAsJsonObject("playabilityStatus")?.get("status")?.asString != "OK") return@runCatching
                val client = parsed.getAsJsonObject("context")?.getAsJsonObject("client") ?: return@runCatching
                if (client.get("clientName")?.asString != "WEB") return@runCatching
                val key = sessions.captureStamp() + ":" + request
                synchronized(entries) {
                    entries[key] = Entry(sessions.captureStamp(), parsed.toString(), body, System.currentTimeMillis(), document(), document)
                    while (entries.values.sumOf { it.body.toByteArray().size } > 8 * 1024 * 1024) entries.remove(entries.keys.first())
                }
            }
        }
    }

    fun get(request: Request, context: ExtractionContext): String? {
        if (request.httpMethod() != "POST" || !request.url().startsWith("https://www.youtube.com/youtubei/v1/player")) return null
        val body = request.dataToSend()?.toString(Charsets.UTF_8) ?: return null
        val semantics = runCatching { JsonParser.parseString(body).asJsonObject.toString() }.getOrNull() ?: return null
        val hint = sessions.captureStamp()
        return synchronized(entries) {
            entries.values.firstOrNull { it.scope == hint && it.semantics == semantics && it.generation == it.document() && System.currentTimeMillis() - it.at < 120_000 && sessions.isCurrent(context.session) }?.body
        }
    }
}
