package com.hhst.youtubelite.player.datasource

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.zip.GZIPInputStream
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import androidx.core.net.toUri

/**
 * YouTube /videoplayback DataSource on the app's shared [OkHttpClient].
 *
 * OkHttp (vs HttpURLConnection) gives connection pooling and HTTP/2
 * multiplexing across concurrent chunk loads — seek responsiveness on
 * googlevideo depends on it. YouTube-specific behavior is unchanged:
 * - `/videoplayback` requests are sent as POST with a fixed `{0x78, 0}` body
 *   (YouTube player protocol), not GET.
 * - DASH chunk requests carry `&range=pos-end` + `&rn=N` query params instead of
 *   the Range header (gated by [rangeParameterEnabled]/[rnParameterEnabled]).
 * - WebView cookies are omitted on `/videoplayback` except for WEB-minted URLs
 *   (session cookies 403 ANDROID_VR / TVHTML5 URLs). Other hosts still get them.
 *   Web streaming URLs get Origin/Referer/Sec-Fetch; Android/iOS/VR/TV
 *   streaming URLs get their client-specific User-Agent.
 * - Manual redirect following (max 20). Non-videoplayback POST follows
 *   300–303 and retries as GET; 307/308 on POST are not followed. For
 *   `/videoplayback` every hop is POST with the same fixed body (YouTube
 *   player protocol, see POST_BODY) regardless of the `method` argument —
 *   the method only matters for other hosts (HEAD/GET/POST).
 * - HTTP 416 with position == document size is treated as a successful empty tail.
 * - gzip responses are unwrapped when the data spec allows it.
 */
@UnstableApi
class YoutubeHttpDataSource private constructor(
    private val callFactory: Call.Factory,
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
    private val defaultRequestProperties: HttpDataSource.RequestProperties?,
    private val userAgent: String,
) : BaseDataSource(true), HttpDataSource {

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var responseCode = 0
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var requestNumber = 0L
    /** True when this [open] put `&range=` on the request that produced [response]. */
    private var rangeQueryApplied = false

    // -- HttpDataSource --

    override fun getUri(): Uri? =
        response?.request?.url?.toString()?.let(Uri::parse)

    override fun getResponseCode(): Int =
        if (response == null || responseCode <= 0) -1 else responseCode

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        rangeQueryApplied = false
        transferInitializing(dataSpec)

        val resp = try {
            makeConnection(dataSpec).also { response = it }
        } catch (e: IOException) {
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        responseCode = resp.code
        val responseMessage = resp.message

        if (responseCode !in 200..299) {
            val headers = resp.headers.toMultimap()
            if (responseCode == 416) {
                val documentSize = HttpUtil.getDocumentSize(resp.header("Content-Range"))
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    resp.close()
                    return if (dataSpec.length != LENGTH_UNSET) dataSpec.length else 0L
                }
            }
            resp.close()
            closeQuietly()
            if (responseCode == 403) {
                Log.w(
                    "YoutubeHttp",
                    "HTTP 403 host=${resp.request.url.host} path=${resp.request.url.encodedPath} " +
                        "c=${resp.request.url.queryParameter("c")} " +
                        "rangeParam=$rangeParameterEnabled rnParam=$rnParameterEnabled " +
                        "pos=${dataSpec.position} len=${dataSpec.length}",
                )
            }
            val cause = if (responseCode == 416) {
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            } else null
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode, responseMessage, cause, headers, dataSpec, ByteArray(0),
            )
        }

        // A 200 to a Range-header request is the full representation, so the
        // body starts at byte 0 and the caller still needs [dataSpec.position]
        // skipped. YouTube `&range=` already sliced that window; skipping
        // again would drop the first `position` bytes of the chunk.
        val bytesToSkip = skipBytesOnOpen(
            responseCode,
            dataSpec.position,
            rangeQueryApplied,
        )

        val compressed = "gzip".equals(resp.header("Content-Encoding"), ignoreCase = true)
        bytesToRead = if (!compressed) {
            if (dataSpec.length != LENGTH_UNSET) dataSpec.length
            else {
                val contentLength = HttpUtil.getContentLength(
                    resp.header("Content-Length"),
                    resp.header("Content-Range"),
                )
                if (contentLength != LENGTH_UNSET) contentLength - bytesToSkip
                else LENGTH_UNSET
            }
        } else dataSpec.length

        inputStream = try {
            resp.body?.byteStream()?.let { if (compressed) GZIPInputStream(it) else it }
                ?: throw IOException("Empty response body")
        } catch (e: IOException) {
            closeQuietly()
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        opened = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: IOException) {
            closeQuietly()
            if (e is HttpDataSource.HttpDataSourceException) throw e
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            return readInternal(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, dataSpec!!, HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
    }

    override fun close() {
        try {
            inputStream?.close()
        } catch (_: IOException) {
        } finally {
            inputStream = null
            closeQuietly()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    // -- connection --

    private fun makeConnection(spec: DataSpec): Response {
        var url = spec.uri.toString()
        var method = spec.httpMethod
        val allowGzip = spec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)

        var redirectCount = 0
        while (redirectCount++ <= MAX_REDIRECTS) {
            val resp = execute(url, method, spec.position, spec.length, allowGzip,
                spec.httpRequestHeaders)
            val code = resp.code
            val location = resp.header("Location")

            val redirect = when (method) {
                DataSpec.HTTP_METHOD_GET, DataSpec.HTTP_METHOD_HEAD -> code in REDIRECT_CODES
                DataSpec.HTTP_METHOD_POST -> code in REDIRECT_CODES_GET_FALLBACK
                else -> false
            }
            if (!redirect) return resp
            resp.close()
            if (location == null) {
                throw HttpDataSource.HttpDataSourceException(
                    "Null location redirect", spec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            if (method == DataSpec.HTTP_METHOD_POST) method = DataSpec.HTTP_METHOD_GET
            url = resolveRedirect(url, location) ?: throw HttpDataSource.HttpDataSourceException(
                "Unresolvable redirect location: $location", spec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        throw HttpDataSource.HttpDataSourceException(
            java.net.NoRouteToHostException("Too many redirects: $redirectCount"), spec,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
    }

    /**
     * Resolves [location] (absolute, network-path, root- or path-relative) against
     * [baseUrl] per RFC 3986 — OkHttp's `HttpUrl.resolve` covers all the
     * forms googlevideo hands back, including query-only and dot-segment refs.
     */
    private fun resolveRedirect(baseUrl: String, location: String): String? =
        baseUrl.toHttpUrlOrNull()?.resolve(location.trim())?.toString()

    private fun execute(
        url: String,
        method: Int,
        position: Long,
        length: Long,
        allowGzip: Boolean,
        requestParameters: Map<String, String>,
    ): Response {
        var requestUrl = url
        val parsed = requestUrl.toUri()
        val isVideoPlayback = parsed.path?.startsWith("/videoplayback") == true
        // The `c` param carries the minting client; ANDROID (reel) URLs are the
        // window-exempt family, everything else adaptive is window-bound.
        val urlClient = parsed.getQueryParameter("c")
        val isExemptMuxed = urlClient == "ANDROID" &&
            parsed.getQueryParameter("itag")?.let { it == "18" } == true

        // Query separators chosen from the URL's own shape: googlevideo URLs
        // normally carry a query, but appending "&rn=..." to a query-less URL
        // would corrupt the path. `[?&]rn=` also catches a "?rn=" form that
        // the old `contains("&rn=")` guard missed.
        if (isVideoPlayback && rnParameterEnabled && !RN_PARAM.containsMatchIn(requestUrl)) {
            requestUrl += (if ('?' in requestUrl) "&" else "?") + "rn=${requestNumber++}"
        }
        // `&range=` clips googlevideo's view of the file to exactly that byte
        // window (verified: content-range total becomes range-end+1, and the
        // full-length view breaks), which is what sidx DASH chunking needs.
        // Range-header playback (exempt muxed itag 18) must NOT use it: the
        // MP4 parser reads across arbitrary ranges of the true file.
        val rangeParamOk = isVideoPlayback && rangeParameterEnabled && !isExemptMuxed
        val appliedRangeQuery = rangeParamOk && (position != 0L || length != LENGTH_UNSET)
        if (appliedRangeQuery) {
            requestUrl += (if ('?' in requestUrl) "&" else "?") + "range=$position" +
                if (length != LENGTH_UNSET) "-${position + length - 1}" else "-"
        }
        rangeQueryApplied = appliedRangeQuery

        val builder = Request.Builder().url(requestUrl)

        defaultRequestProperties?.snapshot?.forEach { (k, v) -> builder.header(k, v) }
        requestProperties.snapshot.forEach { (k, v) -> builder.header(k, v) }
        requestParameters.forEach { (k, v) -> builder.header(k, v) }

        // Cookie policy mirrors the minting session: googlevideo 403s URLs
        // whose requesting session differs from the minting one. WEB URLs are
        // minted inside the WebView browser session (see HttpDownloader), so
        // they must be fetched with that session's cookies; IOS/ANDROID/VR/TV
        // URLs are minted by client-scoped sessions and get none. Timedtext
        // and other hosts always get WebView cookies.
        if (!isVideoPlayback || urlClient == "WEB") {
            runCatching { CookieManager.getInstance().getCookie(requestUrl) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { builder.header("Cookie", it) }
        }

        if (!rangeParamOk) {
            HttpUtil.buildRangeRequestHeader(position, length)?.let {
                builder.header("Range", it)
            }
        }

        if (YoutubeParsingHelper.isWebStreamingUrl(requestUrl) ||
            YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(requestUrl)
        ) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        }

        builder.header("TE", "trailers")
        builder.header("Accept", "*/*")

        val ua = when {
            YoutubeParsingHelper.isAndroidVrStreamingUrl(requestUrl) ->
                YoutubeParsingHelper.getAndroidVrUserAgent()
            YoutubeParsingHelper.isVisionOsStreamingUrl(requestUrl) ->
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            YoutubeParsingHelper.isAndroidStreamingUrl(requestUrl) ->
                YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(requestUrl) ->
                YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isTvHtml5StreamingUrl(requestUrl) ->
                YoutubeParsingHelper.getTvHtml5UserAgent()
            else -> userAgent
        }
        builder.header("User-Agent", ua)
        builder.header("Accept-Encoding", if (allowGzip) "gzip" else "identity")

        if (isVideoPlayback) {
            builder.post(POST_BODY.toRequestBody(OCTET_STREAM))
        } else if (method == DataSpec.HTTP_METHOD_HEAD) {
            builder.head()
        }
        // GET is OkHttp's default.

        val client = callFactory
        return client.newCall(builder.build()).execute()
    }

    private fun skipFully(bytesToSkip: Long, spec: DataSpec) {
        if (bytesToSkip == 0L) return
        val buffer = ByteArray(4096)
        var skipped = 0L
        while (skipped < bytesToSkip) {
            val read = (inputStream ?: throw IOException("InputStream is null"))
                .read(buffer, 0, minOf(bytesToSkip - skipped, buffer.size.toLong()).toInt())
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            if (read == -1) {
                throw HttpDataSource.HttpDataSourceException(
                    spec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            skipped += read
            bytesTransferred(read)
        }
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesToRead != LENGTH_UNSET) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            minOf(length.toLong(), remaining).toInt()
        } else length
        val read = stream.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    private fun closeQuietly() {
        runCatching { response?.close() }
        response = null
    }

    class Factory(
        private val callFactory: Call.Factory,
        private val userAgent: String,
    ) : HttpDataSource.Factory {
        private val defaultRequestProperties = HttpDataSource.RequestProperties()
        private var connectTimeoutMs = 20_000
        private var readTimeoutMs = 30_000
        private var rangeParameterEnabled = false
        private var rnParameterEnabled = false

        override fun setDefaultRequestProperties(properties: Map<String, String>): Factory {
            defaultRequestProperties.clearAndSet(properties)
            return this
        }

        fun setConnectTimeoutMs(value: Int) = apply {
            connectTimeoutMs = value
            timedCallFactory = null
        }
        fun setReadTimeoutMs(value: Int) = apply {
            readTimeoutMs = value
            timedCallFactory = null
        }
        fun setRangeParameterEnabled(value: Boolean) = apply { rangeParameterEnabled = value }
        fun setRnParameterEnabled(value: Boolean) = apply { rnParameterEnabled = value }

        override fun createDataSource(): YoutubeHttpDataSource = YoutubeHttpDataSource(
            sharedCallFactory(), rangeParameterEnabled,
            rnParameterEnabled, defaultRequestProperties, userAgent,
        )

        @Volatile
        private var timedCallFactory: Call.Factory? = null

        /** One OkHttp client per factory so chunk loads share the connection pool. */
        private fun sharedCallFactory(): Call.Factory {
            timedCallFactory?.let { return it }
            synchronized(this) {
                timedCallFactory?.let { return it }
                val base = callFactory as? OkHttpClient ?: return callFactory
                return base.newBuilder()
                    .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(0L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                    .also { timedCallFactory = it }
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 20
        private val POST_BODY = byteArrayOf(0x78, 0)
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val REDIRECT_CODES_GET_FALLBACK = setOf(300, 301, 302, 303)
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val RN_PARAM = Regex("[?&]rn=")
        /** media3's C.LENGTH_UNSET is the int -1; this is the long form used by DataSpec lengths. */
        private const val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()

        /**
         * Bytes to discard from a 200 body before the caller-requested offset.
         *
         * [rangeQueryApplied] means the request used YouTube's `range=` query,
         * so a 200 body is already the requested slice and must not be skipped.
         */
        internal fun skipBytesOnOpen(
            responseCode: Int,
            position: Long,
            rangeQueryApplied: Boolean,
        ): Long {
            if (responseCode == 200 && position != 0L && !rangeQueryApplied) return position
            return 0L
        }
    }
}
