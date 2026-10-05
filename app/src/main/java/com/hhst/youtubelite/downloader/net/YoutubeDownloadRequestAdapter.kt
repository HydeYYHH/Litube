package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.extractor.Format
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
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
 * Downloads retain the core plan without persisting its credentials or reconstructing its profile.
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
    @Transient val requestPlan: RequestPlan? = null,
)

object YoutubeDownloadRequestAdapter {

    fun adapt(videoId: String, format: Format): YoutubeDownloadRequestPlan {
        val plan = format.requestPlan ?: return adaptUrl(videoId, format.url, format.itag)
        return YoutubeDownloadRequestPlan(DownloadHttpMethod.GET, format.url,
            when (plan.range) {
                RequestPlan.Range.QUERY -> DownloadRangeMode.QUERY_PARAM
                RequestPlan.Range.HEADER -> DownloadRangeMode.HTTP_HEADER
                else -> DownloadRangeMode.NONE
            }, plan.profile.clientName, DownloadCookiePolicy.NONE,
            plan.headers + mapOf("User-Agent" to plan.userAgent, "Accept-Encoding" to "identity"),
            DownloadResourceIdentity.of(videoId, format), requestPlan = plan)
    }

    fun adaptUrl(videoId: String, url: String, itag: Int? = null): YoutubeDownloadRequestPlan {
        return YoutubeDownloadRequestPlan(
            method = DownloadHttpMethod.GET, url = url,
            rangeMode = DownloadRangeMode.HTTP_HEADER,
            client = null, cookiePolicy = DownloadCookiePolicy.NONE,
            headers = mapOf("User-Agent" to Constants.userAgent(), "Accept-Encoding" to "identity"),
            resourceIdentity = DownloadResourceIdentity.ofUrl(videoId, url, itag),
        )
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
