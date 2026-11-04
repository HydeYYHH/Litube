package com.hhst.youtubelite.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailDecodeAndroidTest {
    private fun image(width: Int, height: Int): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                source.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally { source.recycle() }
    }

    @Test fun decodingKeepsTheRequestedDetailWithoutUpscaling() = runBlocking {
        for ((width, height, target) in listOf(Triple(1280, 720, 1080), Triple(3840, 2160, 1080),
            Triple(720, 1280, 1080), Triple(320, 180, 1080), Triple(301, 199, 256))) {
            val decoded = YoutubeThumbnail.decode(image(width, height), target)
            assertNotNull(decoded)
            requireNotNull(decoded)
            try {
                assertEquals(minOf(maxOf(width, height), target), maxOf(decoded.width, decoded.height))
                assertTrue(kotlin.math.abs(decoded.width.toFloat() / decoded.height - width.toFloat() / height) < 0.01f)
                assertTrue(decoded.byteCount <= target * target * 4)
                assertEquals(Bitmap.DENSITY_NONE, decoded.density)
            } finally { decoded.recycle() }
        }
    }

    @Test fun invalidBytesDoNotProduceAnImage() = runBlocking {
        assertNull(YoutubeThumbnail.decode(byteArrayOf(1, 2, 3), 1080))
    }

    @Test fun transientImageFailuresRetryButMissingAndInvalidImagesDoNot() = runBlocking {
        val bytes = image(128, 72)
        for (first in listOf(503, 429, 404, 200)) {
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val attempt = calls.incrementAndGet()
                val status = if (attempt == 1) first else 200
                val body = if (attempt == 1) byteArrayOf(1, 2, 3) else bytes
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Image").body(body.toResponseBody()).build()
            }.build()
            val decoded = YoutubeThumbnail.load("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", 128, -1, client)
            if (first == 503 || first == 429) {
                assertNotNull(decoded)
                assertEquals(2, calls.get())
                decoded?.recycle()
            } else {
                assertNull(decoded)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun exhaustedTransientFailuresRemainRetryableAndPermanentMissesDoNot() = runBlocking {
        val url = "https://yt3.ggpht.com/retry-avatar"
        YoutubeThumbnail.clearMemory()
        try {
            for (status in listOf(503, 404)) {
                val calls = AtomicInteger()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    calls.incrementAndGet()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(status).message("Unavailable").body(byteArrayOf().toResponseBody()).build()
                }.build()
                assertNull(YoutubeThumbnail.load(url, 128, YoutubeThumbnail.version.value, client))
                assertEquals(if (status == 503) 2 else 1, calls.get())
                assertEquals(status == 503, YoutubeThumbnail.retryDelayMillis(url) != null)
                // A second visible card shares the negative cache instead of
                // immediately starting another pair of HTTP requests.
                assertNull(YoutubeThumbnail.await(url, 128))
                assertEquals(if (status == 503) 2 else 1, calls.get())
                if (status == 503) {
                    val healthy = OkHttpClient.Builder().addInterceptor { chain ->
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                            .message("OK").body(image(64, 64).toResponseBody()).build()
                    }.build()
                    assertNotNull(YoutubeThumbnail.load(url, 128, YoutubeThumbnail.version.value, healthy))
                    assertNull(YoutubeThumbnail.retryDelayMillis(url))
                }
                YoutubeThumbnail.clearMemory()
            }
        } finally { YoutubeThumbnail.clearMemory() }
    }

    @Test fun liveChatAvatarHostIsAcceptedWithoutBroadeningToOtherHosts() {
        assertTrue(YoutubeThumbnail.isAllowed("https://yt4.ggpht.com/avatar=s64"))
        for (url in listOf("http://yt4.ggpht.com/avatar", "https://yt4.ggpht.com.evil.example/avatar",
            "https://evil.example/yt4.ggpht.com/avatar", "https://viewer@yt4.ggpht.com/avatar",
            "https://yt4.ggpht.com:444/avatar")) assertFalse(url, YoutubeThumbnail.isAllowed(url))
    }

    @Test fun oversizedBodiesAreRejectedBeforeCoilAndNeverRetried() = runBlocking {
        val limit = 4 * 1024 * 1024
        // The header check must reject even a small, otherwise valid image.
        // Without a header, the streaming limit must reject a large response.
        for (declared in listOf(true, false)) {
            val calls = AtomicInteger()
            val png = image(64, 64)
            val body = if (declared) png else ByteArray(limit + 1).also { png.copyInto(it) }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Oversized").body(body.toResponseBody()).apply {
                        if (declared) header("Content-Length", (limit + 1).toString())
                    }.build()
            }.build()
            assertNull(YoutubeThumbnail.load("https://i.ytimg.com/oversized-$declared", 128, -1, client))
            assertEquals(1, calls.get())
        }
    }

    @Test fun oldGenerationsCannotRepopulateTheCoilCache() = runBlocking {
        val url = "https://yt3.ggpht.com/stale-coil-avatar"
        YoutubeThumbnail.clearMemory()
        val oldGeneration = YoutubeThumbnail.version.value
        YoutubeThumbnail.clearMemory()
        val bytes = image(64, 64)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        try {
            val stale = YoutubeThumbnail.load(url, 128, oldGeneration, client)
            assertNotNull(stale)
            assertNull(YoutubeThumbnail.cached(url, 128))
            assertNull(YoutubeThumbnail.cachedForDisplay(url))
            stale?.recycle()
            val current = YoutubeThumbnail.load(url, 128, YoutubeThumbnail.version.value, client)
            assertTrue(current === YoutubeThumbnail.cached(url, 128))
            assertTrue(current === YoutubeThumbnail.cachedForDisplay(url))
            YoutubeThumbnail.clearMemory()
            assertNull(YoutubeThumbnail.cachedForDisplay(url))
            assertFalse(requireNotNull(current).isRecycled)
        } finally { YoutubeThumbnail.clearMemory() }
    }
}
