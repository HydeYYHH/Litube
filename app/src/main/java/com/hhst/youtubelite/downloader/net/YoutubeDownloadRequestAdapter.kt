package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.resolve.DownloadBitrate
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.datasource.StreamSelection
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class DownloadHttpMethod { GET, POST, HEAD }

enum class DownloadRangeMode {
    /** Adaptive googlevideo: `&range=start-end` (Range header 403s). */
    QUERY_PARAM,
    /** Muxed itag 18 / non-sliced: HTTP Range header. */
    HTTP_HEADER,
    NONE,
}

enum class DownloadCookiePolicy {
    /** WEB-minted videoplayback and non-googlevideo hosts. */
    WEB_SESSION,
    NONE,
}

/**
 * Playback request rules lifted out of [com.hhst.youtubelite.player.datasource.YoutubeHttpDataSource]
 * for a download-owned client. The plan is built here and executed by the transport.
 */
data class YoutubeDownloadRequestPlan(
    val method: DownloadHttpMethod,
    val url: String,
    val rangeMode: DownloadRangeMode,
    val client: String?,
    val cookiePolicy: DownloadCookiePolicy,
    val headers: Map<String, String>,
    val resourceIdentity: String,
    val workingDirectory: String = DownloadHttpClients.WORK_DIR,
    val postPulse: Boolean = false,
)

object YoutubeDownloadRequestAdapter {

    fun adapt(videoId: String, format: Format): YoutubeDownloadRequestPlan = adaptUrl(videoId, format.url, format.itag)

    fun adaptUrl(videoId: String, url: String, itag: Int? = null): YoutubeDownloadRequestPlan {
        val client = StreamSelection.streamingClient(url)
        val isVideoPlayback = url.contains("/videoplayback")
        val itagValue = DownloadBitrate.queryParam(url, "itag")?.toIntOrNull() ?: itag
        val isExemptMuxed = client == "ANDROID" && itagValue == 18
        val rangeMode = when {
            !isVideoPlayback -> DownloadRangeMode.HTTP_HEADER
            isExemptMuxed -> DownloadRangeMode.HTTP_HEADER
            else -> DownloadRangeMode.QUERY_PARAM
        }
        val cookiePolicy = if (!isVideoPlayback || client == "WEB") {
            DownloadCookiePolicy.WEB_SESSION
        } else {
            DownloadCookiePolicy.NONE
        }
        val headers = LinkedHashMap<String, String>()
        headers["TE"] = "trailers"
        headers["Accept"] = "*/*"
        headers["Accept-Encoding"] = "identity"
        headers["User-Agent"] = userAgentFor(client)
        if (client == "WEB" || isWebStreaming(url)) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        return YoutubeDownloadRequestPlan(
            method = if (isVideoPlayback) DownloadHttpMethod.POST else DownloadHttpMethod.GET,
            url = url,
            rangeMode = rangeMode,
            client = client,
            cookiePolicy = cookiePolicy,
            headers = headers,
            resourceIdentity = DownloadResourceIdentity.ofUrl(videoId, url, itag),
            workingDirectory = DownloadHttpClients.WORK_DIR,
            postPulse = isVideoPlayback,
        )
    }

    private fun isWebStreaming(url: String): Boolean {
        val client = StreamSelection.streamingClient(url)
        return client == "WEB" || client == "WEB_EMBEDDED"
    }

    private fun userAgentFor(client: String?): String = when (client) {
        "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.61.15"
        "VISIONOS" -> "com.google.ios.youtube/20.11.6 (Darwin; CPU OS like Mac OS X)"
        "ANDROID" -> "com.google.android.youtube/19.47.53"
        "IOS" -> "com.google.ios.youtube/20.11.6"
        "TVHTML5" -> "Mozilla/5.0 (ChromiumStyleTv)"
        else -> Constants.USER_AGENT
    }
}

/**
 * Download-owned OkHttp budget. Not the player [OkHttpClient], not the
 * Media3 `player/` SimpleCache directory.
 */
object DownloadHttpClients {
    const val WORK_DIR = "download"
    const val MAX_REQUESTS = DownloadSettings.DEFAULT_CONNECTIONS
    const val MAX_REQUESTS_PER_HOST = 2

    fun create(
        maxRequests: Int = MAX_REQUESTS,
        maxRequestsPerHost: Int = MAX_REQUESTS_PER_HOST,
    ): OkHttpClient {
        val connections = maxRequests.coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)
        val dispatcher = Dispatcher().apply {
            this.maxRequests = connections
            this.maxRequestsPerHost = maxRequestsPerHost.coerceAtLeast(1)
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(null)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
