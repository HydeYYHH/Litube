package com.hhst.youtubelite.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fetches bounded artwork from the service's image hosts. */
object YoutubeThumbnail {
    private val http by lazy { DiagnosticNetwork.install(OkHttpClient.Builder()).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build() }
    const val HOST = "i.ytimg.com"
    private const val CACHE_BYTES = 24 * 1024 * 1024
    /** Rejects a thumbnail body larger than this before decode. */
    private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private data class Key(val url: String, val dimension: Int, val generation: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ArtworkRequests<Key, Bitmap?>(scope) { key ->
        cached(key.url, key.dimension) ?: load(key.url, key.dimension, key.generation)
    }
    private val requestLock = Any()
    private val generation = MutableStateFlow(0L)
    val version = generation.asStateFlow()

    fun cached(url: String?, maxDim: Int = MAX_DECODE_DIM): Bitmap? =
        url?.let { cache.get(cacheKey(it, maxDim.coerceIn(64, MAX_DECODE_DIM))) }

    /** Drops all decoded bitmaps (cache-clear "image memory" owner). */
    fun clearMemory() = synchronized(requestLock) {
        generation.value++
        cache.evictAll()
        requests.clear()
    }

    private fun cacheKey(url: String, maxDim: Int) = "$url@$maxDim"

    fun isAllowed(url: String): Boolean {
        val uri = url.toUri()
        return uri.scheme?.equals("https", ignoreCase = true) == true &&
            // dearrow-thumb.ajay.app serves DeArrow community thumbnails only;
            // each extra host here is a deliberate, narrow addition.
            uri.host in setOf(HOST, "i9.ytimg.com", "yt3.ggpht.com", "yt3.googleusercontent.com",
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
        val dimension = maxDim.coerceIn(64, MAX_DECODE_DIM)
        cached(url, dimension)?.let { return it }
        val key = synchronized(requestLock) { Key(url, dimension, generation.value) }
        return requests.get(key)
    }

    private suspend fun load(url: String, maxDim: Int, requestGeneration: Long): Bitmap? = coroutineScope {
        val diagnostic = DiagnosticContext(videoId = url.toUri().pathSegments.getOrNull(1)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) })
        AppLog.detail(AppLog.Category.GALLERY, "thumbnail.start", DiagnosticRedaction.resource(url), diagnostic)
        var reason = "unknown"
        var success = false
        val call = http.newCall(DiagnosticNetwork.tag(Request.Builder().url(url).build(), diagnostic))
        try {
            async(Dispatchers.IO) {
                call.execute().use { conn ->
                    reason = "http_${conn.code}"
                    if (conn.code != 200) return@async null
                    val declared = conn.header("Content-Length")?.toLongOrNull() ?: -1
                    reason = "body_limit"
                    if (declared > MAX_DOWNLOAD_BYTES) return@async null
                    val bytes = conn.body?.byteStream()?.use { readBounded(it, MAX_DOWNLOAD_BYTES) } ?: return@async null
                    reason = "decode_failed"
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@async null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.also {
                            synchronized(requestLock) {
                                if (generation.value == requestGeneration) cache.put(cacheKey(url, maxDim), it)
                            }
                            success = true
                        }
                }
            }.await()
        } catch (cancelled: CancellationException) {
            reason = "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            reason = "transport_failed"
            AppLog.event(AppLog.Category.GALLERY, "thumbnail.failed", mapOf("reason" to reason), failure, context = diagnostic)
            null
        } finally {
            call.cancel()
            if (success) AppLog.detail(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "SUCCESS"), diagnostic)
            else AppLog.event(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "FAILURE", "reason" to reason),
                critical = reason != "cancelled" && reason !in EXPECTED_MISS_REASONS, context = diagnostic)
            AppLog.endContext(diagnostic)
        }
    }

    /** Reads at most [max] bytes; null when the stream exceeds the cap. */
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
