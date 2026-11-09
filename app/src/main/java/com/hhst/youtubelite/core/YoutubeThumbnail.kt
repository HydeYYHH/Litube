package com.hhst.youtubelite.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.asImage
import coil3.serviceLoaderEnabled
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import androidx.core.net.toUri
import com.hhst.youtubelite.diagnostics.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object YoutubeThumbnail {
    private val http by lazy { DiagnosticNetwork.install(OkHttpClient.Builder()).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build() }
    const val HOST = "i.ytimg.com"
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024

    private val cache = MemoryCache.Builder().maxSizeBytes(CACHE_BYTES.toLong())
        // Clearing memory must also drop entries still owned by visible views.
        .weakReferencesEnabled(false).build()
    private lateinit var context: Context
    private val imageLoader by lazy {
        ImageLoader.Builder(context).memoryCache(cache).diskCache(null)
            // Only bounded ByteArrays enter Coil. Never discover another network fetcher.
            .serviceLoaderEnabled(false).build()
    }

    /** Keep the application context; the Coil pipeline itself is initialized on first use. */
    internal fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private data class Key(val url: String, val dimension: Int, val generation: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ArtworkRequests<Key, Bitmap?>(scope) { key ->
        cached(key.url, key.dimension) ?: load(key.url, key.dimension, key.generation)
    }
    private val requestLock = Any()
    private data class Miss(val retryable: Boolean, val expiresAt: Long)
    private val misses = object : LinkedHashMap<String, Miss>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Miss>) = size > 256
    }
    private val generation = MutableStateFlow(0L)
    val version = generation.asStateFlow()

    fun cached(url: String?, maxDim: Int = MAX_DECODE_DIM): Bitmap? =
        url?.let { (cache[MemoryCache.Key(cacheKey(it, artworkDecodeDimension(maxDim)))]?.image as? BitmapImage)?.bitmap }
            ?.takeUnless { it.isRecycled }

    /** Reuse an already visible image while another display size is loading. */
    fun cachedForDisplay(url: String?): Bitmap? = url?.let {
        val prefix = "$it@"
        cache.keys.asSequence().filter { key -> key.key.startsWith(prefix) }
            .mapNotNull { key -> (cache[key]?.image as? BitmapImage)?.bitmap }.filterNot { image -> image.isRecycled }
            .maxByOrNull { image -> image.width * image.height }
    }

    /** Drops all decoded bitmaps (cache-clear "image memory" owner). */
    fun clearMemory() = synchronized(requestLock) {
        generation.value++
        cache.clear()
        misses.clear()
        requests.clear()
    }

    private fun cacheKey(url: String, maxDim: Int) = "$url@$maxDim"

    fun isAllowed(url: String): Boolean {
        val uri = url.toUri()
        return uri.scheme?.equals("https", ignoreCase = true) == true &&
            // dearrow-thumb.ajay.app serves DeArrow community thumbnails only;
            // each extra host here is a deliberate, narrow addition.
            uri.host in setOf(HOST, "i9.ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com", "yt3.googleusercontent.com",
                "lh3.googleusercontent.com", "dearrow-thumb.ajay.app") &&
            uri.userInfo.isNullOrEmpty() && uri.port in setOf(-1, 443)
    }

    /** Server answers that mean "no image here" — a miss, not a malfunction. */
    private val EXPECTED_MISS_REASONS = setOf("http_204", "http_404")
    private const val MAX_DECODE_DIM = 1600

    @WorkerThread
    fun fetch(url: String?, maxDim: Int = MAX_DECODE_DIM): Bitmap? =
        try { runBlocking { await(url, maxDim) } }
        catch (_: CancellationException) { null }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }

    suspend fun await(url: String?, maxDim: Int = MAX_DECODE_DIM): Bitmap? {
        if (url.isNullOrBlank() || !isAllowed(url)) return null
        val dimension = artworkDecodeDimension(maxDim)
        cached(url, dimension)?.let { return it }
        val key = synchronized(requestLock) {
            if (misses[url]?.expiresAt?.let { it > android.os.SystemClock.elapsedRealtime() } == true) return null
            Key(url, dimension, generation.value)
        }
        return requests.get(key)
    }

    /** Visible images may retry transient failures; permanent misses wait for a new source. */
    fun retryDelayMillis(url: String): Long? = synchronized(requestLock) {
        misses[url]?.takeIf { it.retryable }?.let {
            (it.expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        }
    }

    internal suspend fun load(url: String, maxDim: Int, requestGeneration: Long, client: OkHttpClient = http): Bitmap? {
        val diagnostic = DiagnosticContext(videoId = url.toUri().pathSegments.getOrNull(1)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) })
        AppLog.detail(AppLog.Category.GALLERY, "thumbnail.start", DiagnosticRedaction.resource(url), diagnostic)
        var reason = "unknown"
        var success = false
        var mayRetry = false
        return try {
            repeat(2) { attempt ->
                var retryable = false
                val call = client.newCall(DiagnosticNetwork.tag(Request.Builder().url(url).build(), diagnostic))
                try {
                    val bytes = call.readCancellable { conn ->
                        reason = "http_${conn.code}"
                        retryable = conn.code == 408 || conn.code == 429 || conn.code in 500..599
                        mayRetry = retryable
                        if (conn.code != 200) return@readCancellable null
                        val declared = conn.header("Content-Length")?.toLongOrNull() ?: -1
                        reason = "body_limit"
                        if (declared > MAX_DOWNLOAD_BYTES) return@readCancellable null
                        conn.body?.byteStream()?.use { readBounded(it, MAX_DOWNLOAD_BYTES) }
                    }
                    val image = if (bytes != null) {
                        reason = "decode_failed"
                        decode(bytes, maxDim)?.also {
                            synchronized(requestLock) {
                                if (generation.value == requestGeneration) {
                                    cache[MemoryCache.Key(cacheKey(url, maxDim))] = MemoryCache.Value(it.asImage())
                                }
                            }
                            success = true
                        }
                    } else null
                    if (image != null) return image
                } catch (failure: IOException) {
                    reason = "transport_failed"
                    retryable = true
                    mayRetry = true
                    if (attempt == 1) AppLog.event(AppLog.Category.GALLERY, "thumbnail.failed",
                        mapOf("reason" to reason), failure, context = diagnostic)
                } finally { call.cancel() }
                if (!retryable || attempt == 1) return null
                delay(250)
            }
            null
        } catch (cancelled: CancellationException) {
            reason = "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            reason = "transport_failed"
            AppLog.event(AppLog.Category.GALLERY, "thumbnail.failed", mapOf("reason" to reason), failure, context = diagnostic)
            null
        } finally {
            synchronized(requestLock) {
                if (generation.value == requestGeneration && reason != "cancelled") {
                    if (success) misses.remove(url)
                    else misses[url] = Miss(mayRetry, android.os.SystemClock.elapsedRealtime() + if (mayRetry) 5_000L else 60_000L)
                }
            }
            if (success) AppLog.detail(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "SUCCESS"), diagnostic)
            else AppLog.event(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "FAILURE", "reason" to reason),
                critical = reason != "cancelled" && reason !in EXPECTED_MISS_REASONS, context = diagnostic)
            AppLog.endContext(diagnostic)
        }
    }

    internal suspend fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        val target = minOf(largest, maxDim.coerceIn(64, MAX_DECODE_DIM))
        val result = imageLoader.execute(ImageRequest.Builder(context)
            .data(bytes).size(target, target).scale(Scale.FIT).precision(Precision.EXACT)
            .allowHardware(false)
            // Publish only under requestLock after checking the cache-clear generation.
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .build())
        return (result as? SuccessResult)?.image?.toBitmap()?.apply { density = Bitmap.DENSITY_NONE }
    }

    internal fun readBounded(stream: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
