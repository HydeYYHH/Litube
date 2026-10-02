package com.hhst.youtubelite.extractor

import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp [Downloader] for NewPipe extraction traffic. */
class HttpDownloader(
    client: OkHttpClient,
    private val playerCache: PlayerCache? = null,
) : Downloader() {

    private val http = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod() ?: "GET"
        val url = request.url()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, request.dataToSend()?.toRequestBody())

        request.headers()?.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val cache = playerCache
        val playerId = webPlayerId(url, method, request.dataToSend())
        unifyWebSession(builder, url, request, webPlayer = playerId != null)
        // Requests NewPipe sends without a UA would otherwise go out stamped
        // okhttp/4.x (BridgeInterceptor's default); keep the app UA as fallback.
        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", Constants.userAgent())
        }
        return if (cache != null && playerId != null) {
            cache.withInFlight(playerId) { fetch(builder, url, cache, playerId) }
        } else {
            fetch(builder, url, cache, playerId)
        }
    }

    /**
     * Makes the WEB client's `*.youtube.com` requests belong to the WebView's
     * browser session (same UA and cookies): googlevideo rejects URLs whose
     * minting session differs from the requesting one.
     *
     * Scoped to WEB /player requests — other clients (ANDROID_VR, IOS,
     * TVHTML5) mint URLs bound to their own user agents, and overriding the
     * UA here breaks those URLs at googlevideo.
     */
    private fun unifyWebSession(
        builder: okhttp3.Request.Builder,
        url: String,
        request: Request,
        webPlayer: Boolean,
    ) {
        if (!webPlayer) return
        builder.header("User-Agent", Constants.userAgent())
        val requestCookies = request.headers()?.entries
            ?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value.orEmpty().joinToString("; ")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        val webViewCookies = runCatching {
            CookieManager.getInstance().getCookie("https://$host")
        }.getOrNull().orEmpty()
        // Same cookie name with different values (e.g. SOCS): keep one, not both.
        val byName = LinkedHashMap<String, String>()
        for (part in requestCookies.split(';') + webViewCookies.split(';')) {
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) byName[name] = part.trim()
        }
        val merged = byName.values.joinToString("; ")
        if (merged.isNotEmpty()) builder.header("Cookie", merged)
    }

    private fun fetch(
        builder: okhttp3.Request.Builder,
        url: String,
        cache: PlayerCache?,
        playerId: String?,
    ): Response {
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw ReCaptchaException("HTTP 429", url)
            }
            if (cache != null && playerId != null && response.isSuccessful) {
                cacheIfPlayable(cache, playerId, body)
            }
            val headers = LinkedHashMap<String, List<String>>()
            response.headers.forEach { (name, value) ->
                headers.merge(name, listOf(value)) { left, right -> left + right }
            }
            return Response(
                response.code,
                response.message,
                headers,
                body,
                response.request.url.toString(),
            )
        }
    }

    /** Video id of a WEB-client /player POST; null for anything not reusable by the page. */
    private fun webPlayerId(url: String, method: String, bodyBytes: ByteArray?): String? {
        if (!method.equals("POST", ignoreCase = true)) return null
        if (!url.contains(PLAYER_PATH, ignoreCase = true)) return null
        return try {
            val json = gson.fromJson(
                String(bodyBytes ?: return null, Charsets.UTF_8),
                JsonObject::class.java,
            )
            val clientName = json.getAsJsonObject("context")
                ?.getAsJsonObject("client")
                ?.get("clientName")?.asString
            if (clientName != WEB_CLIENT) return null
            VideoId.parse(json.get("videoId")?.asString)
        } catch (_: Exception) {
            null
        }
    }

    /** Caches only playable responses; error payloads must not be served to the page. */
    private fun cacheIfPlayable(cache: PlayerCache, videoId: String, body: String) {
        val playable = runCatching {
            gson.fromJson(body, JsonObject::class.java)
                .getAsJsonObject("playabilityStatus")
                ?.get("status")?.asString == "OK"
        }.getOrDefault(false)
        if (playable) {
            cache.put(videoId, body.toByteArray(Charsets.UTF_8))
            Log.d(TAG, "player response cached $videoId")
        }
    }

    private companion object {
        const val TAG = "HttpDownloader"
        const val PLAYER_PATH = "/youtubei/v1/player"
        const val WEB_CLIENT = "WEB"
    }
}
