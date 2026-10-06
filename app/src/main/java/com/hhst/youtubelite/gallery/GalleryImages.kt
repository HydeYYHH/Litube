package com.hhst.youtubelite.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

object GalleryImages {
    data class Image(val file: File, val mime: String, val extension: String, val bitmap: Bitmap)
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            listOf("ggpht.com", "ytimg.com", "googleusercontent.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun clean(context: Context) {
        val dir = File(context.cacheDir, "gallery")
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }.toMutableList()
        files.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }.forEach { it.delete(); files.remove(it) }
        var total = files.sumOf { it.length() }
        files.forEach { if (total > 64L * 1024 * 1024) { total -= it.length(); it.delete() } }
    }

    fun load(context: Context, url: String): Image {
        require(allowed(url))
        val dir = File(context.cacheDir, "gallery").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, hash)
        if (!file.exists()) {
            val temp = File.createTempFile("image-", ".part", dir)
            try {
                var next = url
                var downloaded = false
                for (redirect in 0..3) {
                    require(allowed(next))
                    val connection = URI(next).toURL().openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                        if (connection.responseCode in 300..399) {
                            next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                            continue
                        }
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                        check((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) <= 20L * 1024 * 1024)
                        connection.inputStream.use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024); var total = 0L
                            while (true) {
                                val size = input.read(buffer); if (size < 0) break
                                total += size; check(total <= 20L * 1024 * 1024) { "Image too large" }
                                output.write(buffer, 0, size)
                            }
                        } }
                        downloaded = true; break
                    } finally { connection.disconnect() }
                }
                check(downloaded) { "Too many redirects" }
                check(temp.renameTo(file))
            } finally { temp.delete() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val extension = when (bounds.outMimeType) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
            "image/gif" -> "gif"; "image/avif" -> "avif"; "image/heif", "image/heic" -> "heic"
            else -> { file.delete(); error("Unsupported image format") }
        }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: run { file.delete(); error("Image decode failed") }
        file.setLastModified(System.currentTimeMillis())
        return Image(file, bounds.outMimeType, extension, bitmap)
    }
}
