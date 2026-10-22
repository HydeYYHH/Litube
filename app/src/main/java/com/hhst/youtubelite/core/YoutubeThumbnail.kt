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

    fun cached(url: String?): Bitmap? = url?.let { cache.get(it) }

    fun isAllowed(url: String): Boolean {
        val uri = url.toUri()
        return uri.scheme?.equals("https", ignoreCase = true) == true &&
            uri.host in setOf(HOST, "i9.ytimg.com", "yt3.ggpht.com", "yt3.googleusercontent.com", "lh3.googleusercontent.com") &&
            uri.userInfo.isNullOrEmpty() && uri.port in setOf(-1, 443)
    }

    fun fetch(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cached(url)?.let { return it }
        if (!isAllowed(url)) return null
        val diagnostic = DiagnosticContext(videoId = url.toUri().pathSegments.getOrNull(1)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) })
        AppLog.detail(AppLog.Category.GALLERY, "thumbnail.start", DiagnosticRedaction.resource(url), diagnostic)
        var reason = "unknown"
        var success = false
        return try {
            http.newCall(DiagnosticNetwork.tag(Request.Builder().url(url).build(), diagnostic)).execute().use { conn ->
                reason = "http_${conn.code}"
                if (conn.code != 200) return null
                val declared = conn.header("Content-Length")?.toLongOrNull() ?: -1
                reason = "body_limit"
                if (declared > MAX_DOWNLOAD_BYTES) return null
                val bytes = conn.body?.byteStream()?.use { readBounded(it, MAX_DOWNLOAD_BYTES) } ?: return null
                reason = "decode_failed"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { cache.put(url, it); success = true }
            }
        } catch (failure: Exception) {
            reason = "transport_failed"
            AppLog.event(AppLog.Category.GALLERY, "thumbnail.failed", mapOf("reason" to reason), failure, context = diagnostic)
            null
        } finally {
            if (success) AppLog.detail(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "SUCCESS"), diagnostic)
            else AppLog.event(AppLog.Category.GALLERY, "thumbnail.end", mapOf("outcome" to "FAILURE", "reason" to reason), critical = true, context = diagnostic)
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
