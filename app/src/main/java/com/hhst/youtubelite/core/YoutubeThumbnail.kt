package com.hhst.youtubelite.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri

/**
 * Fetches YouTube video thumbnails. Page JS and notifications must not be
 * allowed to point this at arbitrary HTTPS hosts.
 */
object YoutubeThumbnail {
    const val HOST = "i.ytimg.com"
    private const val CACHE_BYTES = 3 * 1024 * 1024
    /** Rejects a thumbnail body larger than this before decode. */
    internal const val MAX_DOWNLOAD_BYTES = 512 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String?): Bitmap? = url?.let { cache.get(it) }

    fun isAllowed(url: String): Boolean {
        val uri = url.toUri()
        return uri.scheme?.equals("https", ignoreCase = true) == true &&
            uri.host == HOST &&
            uri.userInfo.isNullOrEmpty()
    }

    fun fetch(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cached(url)?.let { return it }
        if (!isAllowed(url)) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                // Bounded so a hung fetch cannot pin the calling IO coroutine
                // (or the notification art task) longer than a playback beat.
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                connect()
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val declared = conn.contentLengthLong
            if (declared > MAX_DOWNLOAD_BYTES) return null
            val bytes = conn.inputStream.use { readBounded(it, MAX_DOWNLOAD_BYTES) } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.also { cache.put(url, it) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
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
