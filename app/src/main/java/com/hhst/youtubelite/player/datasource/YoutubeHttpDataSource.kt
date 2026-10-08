package com.hhst.youtubelite.player.datasource

import android.net.Uri
import java.net.NoRouteToHostException
import java.util.concurrent.TimeUnit
import android.util.Log
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan

/** Media3 and cast consume the extractor's session, client, method and bounded range plan. */
@UnstableApi
class YoutubeHttpDataSource private constructor(
    private val callFactory: Call.Factory,
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
    private val userAgent: String,
    private val mediaRequests: YoutubeMediaRequests?,
    private val inheritedPlan: RequestPlan?,
    private val planResolver: ((String) -> RequestPlan?)?,
) : BaseDataSource(true), HttpDataSource {

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var responseCode = 0
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var activePlan: RequestPlan? = null
    private var windowRead = 0L
    private var windowLimit = 0L
    private var expectedWindow = -1L
    @Volatile private var activeCall: Call? = null
    private var effectiveRange = RequestPlan.Range.NONE
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
        windowRead = 0
        activePlan = planResolver?.invoke(dataSpec.uri.toString()) ?: inheritedPlan ?: mediaRequests?.plan(dataSpec.uri.toString())
        rangeQueryApplied = false
        transferInitializing(dataSpec)

        activePlan?.resourceLength?.takeIf { it > 0 }?.let { total ->
            if (dataSpec.position > total) throw HttpDataSource.HttpDataSourceException(
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE), dataSpec,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            if (dataSpec.position == total) {
                opened = true
                responseCode = 200
                transferStarted(dataSpec)
                return 0
            }
        }

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
                    return 0L
                }
            }
            resp.close()
            closeQuietly()
            if (responseCode == 403) {
                Log.w(
                    TAG,
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

        activePlan?.let { plan ->
            val window = requireNotNull(mediaRequests).window(resp, plan)
            expectedWindow = window.expectedBytes
            windowLimit = if (effectiveRange == RequestPlan.Range.NONE) Long.MAX_VALUE
                else window.expectedBytes.takeIf { it >= 0 } ?: plan.chunkLimit
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

        activePlan?.let { plan ->
            mediaRequests?.window(resp, plan)?.total?.let { total ->
                val available = (total - dataSpec.position).coerceAtLeast(0)
                if (dataSpec.length != LENGTH_UNSET) bytesToRead = minOf(dataSpec.length, available)
            }
        }

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
        if (activePlan != null && effectiveRange != RequestPlan.Range.NONE && dataSpec.length == LENGTH_UNSET) {
            val total = activePlan?.resourceLength?.takeIf { it > 0 }
                ?: mediaRequests?.window(resp, requireNotNull(activePlan))?.total
            if (total != null) bytesToRead = total - dataSpec.position
            else if (resp.code == 200 && !rangeQueryApplied) bytesToRead = (resp.body?.contentLength() ?: LENGTH_UNSET).let { if (it < 0) it else it - dataSpec.position }
            else bytesToRead = LENGTH_UNSET
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
        activeCall?.cancel()
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
        val boundPlan = planResolver?.invoke(spec.uri.toString()) ?: inheritedPlan ?: mediaRequests?.plan(spec.uri.toString())
        if (boundPlan != null) return execute(spec.uri.toString(), spec.httpMethod, spec.position, spec.length,
            spec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP), spec.httpRequestHeaders)
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
            NoRouteToHostException("Too many redirects: $redirectCount"), spec,
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
        if (mediaRequests != null) {
            val plan = planResolver?.invoke(url) ?: inheritedPlan ?: mediaRequests.plan(url)
            if (plan != null) {
                activePlan = plan
                val range = when {
                    rangeParameterEnabled && url.toHttpUrlOrNull()?.encodedPath?.startsWith("/videoplayback") == true -> RequestPlan.Range.QUERY
                    plan.range != RequestPlan.Range.NONE -> plan.range
                    position > 0 || length >= 0 -> RequestPlan.Range.HEADER
                    else -> plan.range
                }
                effectiveRange = range
                rangeQueryApplied = range == RequestPlan.Range.QUERY
                return executeCall(mediaRequests.build(url, plan, position, length, requestParameters, range))
            }
            val builder = Request.Builder().url(url).header("User-Agent", userAgent)
            requestParameters.filterKeys { it.lowercase() !in setOf("cookie", "authorization") }.forEach { (k, v) -> builder.header(k, v) }
            HttpUtil.buildRangeRequestHeader(position, length)?.let { builder.header("Range", it) }
            return executeCall(builder.get().build())
        }
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Accept-Encoding", if (allowGzip) "gzip" else "identity")
        requestParameters.filterKeys { it.lowercase() !in setOf("authorization", "cookie") }
            .forEach { (k, v) -> builder.header(k, v) }
        HttpUtil.buildRangeRequestHeader(position, length)?.let { builder.header("Range", it) }
        if (method == DataSpec.HTTP_METHOD_HEAD) builder.head()
        return executeCall(builder.build())
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

    private fun executeCall(request: Request): Response {
        val call = callFactory.newCall(request)
        activeCall = call
        return call.execute()
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != LENGTH_UNSET && bytesRead >= bytesToRead) return C.RESULT_END_OF_INPUT
        val plan = activePlan
        if (plan != null && effectiveRange != RequestPlan.Range.NONE && windowRead >= windowLimit) {
            // A short window with no proven total is the final query-cropped view.
            if (bytesToRead == LENGTH_UNSET && windowLimit < plan.chunkLimit) return C.RESULT_END_OF_INPUT
            inputStream?.close(); response?.close()
            val original = requireNotNull(dataSpec)
            val position = original.position + bytesRead
            val remaining = if (bytesToRead == LENGTH_UNSET) LENGTH_UNSET else bytesToRead - bytesRead
            val next = makeConnection(original.buildUpon().setPosition(position).setLength(remaining).build())
            response = next
            responseCode = next.code
            if (next.code == 416 && HttpUtil.getDocumentSize(next.header("Content-Range")) == position) return C.RESULT_END_OF_INPUT
            if (!next.isSuccessful) throw HttpDataSource.InvalidResponseCodeException(next.code, next.message, null, next.headers.toMultimap(), original, ByteArray(0))
            val window = requireNotNull(mediaRequests).window(next, plan)
            expectedWindow = window.expectedBytes
            windowLimit = window.expectedBytes.takeIf { it >= 0 } ?: minOf(plan.chunkLimit, if (remaining < 0) plan.chunkLimit else remaining)
            window.total?.let { if (bytesToRead == LENGTH_UNSET) bytesToRead = it - original.position }
            inputStream = next.body?.byteStream() ?: throw IOException("MEDIA_EMPTY_BODY")
            windowRead = 0
        }
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        var count = if (bytesToRead == LENGTH_UNSET) length else minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        if (plan != null && effectiveRange != RequestPlan.Range.NONE) count = minOf(count.toLong(), windowLimit - windowRead).toInt()
        if (count <= 0) return C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, count)
        if (read == -1) {
            if (expectedWindow >= 0 && windowRead < expectedWindow || bytesToRead != LENGTH_UNSET && bytesRead < bytesToRead) throw IOException("MEDIA_EARLY_EOF")
            return C.RESULT_END_OF_INPUT
        }
        bytesRead += read
        windowRead += read
        bytesTransferred(read)
        return read
    }

    private fun closeQuietly() {
        runCatching { response?.close() }
        response = null
        activeCall = null
    }

    class Factory(
        private val callFactory: Call.Factory,
        private val userAgent: String,
    ) : HttpDataSource.Factory {
        private var connectTimeoutMs = 20_000
        private var readTimeoutMs = 30_000
        private var rangeParameterEnabled = false
        private var rnParameterEnabled = false

        // Required by the Factory interface but unused: every request's headers
        // come from DataSpec.httpRequestHeaders, so factory-level defaults have
        // no path into a request.
        override fun setDefaultRequestProperties(properties: Map<String, String>): Factory = this

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
        private var mediaRequests: YoutubeMediaRequests? = null
        private var inheritedPlan: RequestPlan? = null
        private var planResolver: ((String) -> RequestPlan?)? = null
        fun setPlanResolver(value: (String) -> RequestPlan?) = apply { planResolver = value }
        fun setMediaRequests(value: YoutubeMediaRequests?, plan: RequestPlan? = null) = apply {
            mediaRequests = value; inheritedPlan = plan; timedCallFactory = null
        }

        override fun createDataSource(): YoutubeHttpDataSource = YoutubeHttpDataSource(
            sharedCallFactory(), rangeParameterEnabled,
            rnParameterEnabled, userAgent, mediaRequests, inheritedPlan, planResolver,
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
                    .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(0L, TimeUnit.MILLISECONDS)
                    .apply { mediaRequests?.let { followRedirects(false); followSslRedirects(false); addInterceptor(it.interceptor()) } }
                    .build()
                    .also { timedCallFactory = it }
            }
        }
    }

    companion object {
        private const val TAG = "YoutubeHttpDataSource"
        private const val MAX_REDIRECTS = 20
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val REDIRECT_CODES_GET_FALLBACK = setOf(300, 301, 302, 303)
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
