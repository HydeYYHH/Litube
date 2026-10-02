package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun interface DownloadCookieSource {
    fun cookie(url: String): String?
}

object NoCookies : DownloadCookieSource {
    override fun cookie(url: String): String? = null
}

object WebViewCookies : DownloadCookieSource {
    override fun cookie(url: String): String? =
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
}

/**
 * Builds a download-owned OkHttp request from a request plan: method, `range=` vs
 * Range header, client headers, Cookie.
 */
object DownloadRequestFactory {
    private val POST_BODY = byteArrayOf(0x78, 0)
    private val OCTET = "application/octet-stream".toMediaType()

    fun fromSource(source: DownloadComponentSource): YoutubeDownloadRequestPlan =
        YoutubeDownloadRequestPlan(
            method = source.methodName?.let { runCatching { DownloadHttpMethod.valueOf(it) }.getOrNull() }
                ?: DownloadHttpMethod.GET,
            url = source.url,
            rangeMode = source.rangeModeName?.let { runCatching { DownloadRangeMode.valueOf(it) }.getOrNull() }
                ?: DownloadRangeMode.HTTP_HEADER,
            client = source.client,
            cookiePolicy = source.cookiePolicyName?.let {
                runCatching { DownloadCookiePolicy.valueOf(it) }.getOrNull()
            } ?: DownloadCookiePolicy.NONE,
            headers = source.headers,
            resourceIdentity = source.resourceIdentity.orEmpty(),
            postPulse = source.postPulse,
        )

    fun build(
        plan: YoutubeDownloadRequestPlan,
        start: Long? = null,
        endInclusive: Long? = null,
        cookies: DownloadCookieSource = NoCookies,
    ): Request {
        var url = plan.url
        val builder = Request.Builder()
        val ranged = start != null && endInclusive != null
        if (ranged) {
            when (plan.rangeMode) {
                DownloadRangeMode.QUERY_PARAM -> {
                    url = appendQuery(url, "range=$start-$endInclusive")
                }
                DownloadRangeMode.HTTP_HEADER -> {
                    builder.header("Range", "bytes=$start-$endInclusive")
                }
                DownloadRangeMode.NONE -> Unit
            }
        }
        builder.url(url)
        plan.headers.forEach { (key, value) -> builder.header(key, value) }
        if (plan.cookiePolicy == DownloadCookiePolicy.WEB_SESSION) {
            cookies.cookie(url)?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
        }
        when (plan.method) {
            DownloadHttpMethod.POST -> builder.post(POST_BODY.toRequestBody(OCTET))
            DownloadHttpMethod.HEAD -> builder.head()
            DownloadHttpMethod.GET -> builder.get()
        }
        return builder.build()
    }

    private fun appendQuery(url: String, pair: String): String {
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            return parsed.newBuilder().removeAllQueryParameters(name).addQueryParameter(name, value).build().toString()
        }
        return if ('?' in url) "$url&$pair" else "$url?$pair"
    }
}

data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long?,
)

object DownloadRangeParser {
    private val HEADER = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun parse(header: String?): ContentRange? {
        val raw = header?.trim().orEmpty()
        val match = HEADER.find(raw) ?: return null
        val total = match.groupValues[3].toLongOrNull()
        return ContentRange(
            start = match.groupValues[1].toLong(),
            end = match.groupValues[2].toLong(),
            total = total,
        )
    }

    enum class Decision { MATCH, FULL_FALLBACK, INVALID }

    /**
     * 200 is accepted only when the body can be proven to be the requested
     * chunk; otherwise the component falls back to a full-stream download.
     */
    fun decide(
        code: Int,
        requestedStart: Long,
        requestedEnd: Long,
        rangeMode: DownloadRangeMode,
        contentRange: ContentRange?,
        contentLength: Long?,
    ): Decision {
        if (code == 206) {
            if (contentRange == null) return Decision.INVALID
            if (contentRange.start != requestedStart) return Decision.INVALID
            if (contentRange.end < requestedStart) return Decision.INVALID
            return Decision.MATCH
        }
        if (code != 200) return Decision.INVALID
        if (contentRange != null) {
            return if (contentRange.start == requestedStart) Decision.MATCH else Decision.FULL_FALLBACK
        }
        val wanted = requestedEnd - requestedStart + 1L
        if (rangeMode == DownloadRangeMode.QUERY_PARAM) {
            if (contentLength == null || contentLength == wanted) return Decision.MATCH
            return Decision.FULL_FALLBACK
        }
        if (requestedStart == 0L && contentLength != null && contentLength == wanted) {
            return Decision.MATCH
        }
        return Decision.FULL_FALLBACK
    }
}
