package com.hhst.youtubelite.data.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The avatar directory is the one place missing author avatars are filled: coalesced per
 * channel, capped, cached with expiries — the tests pin those bounds so no screen can
 * degrade into one request per card.
 */
class MediaAvatarDirectoryTest {
    private class Counting(private val code: Int, private val body: String, private val calls: MutableList<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            synchronized(calls) { calls += request.url.encodedPath }
            return Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "Rejected")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun directory(code: Int, body: String): Triple<MediaAvatarDirectory, MutableList<String>, Long> {
        val calls = mutableListOf<String>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor(Counting(code, body, calls)).build())
        return Triple(MediaAvatarDirectory(client), calls, 0L)
    }

    private val channelBody = """
        {"channelMetadataRenderer":{"externalId":"UCabc","title":"Blender",
        "avatar":{"thumbnails":[{"url":"https://yt3/avatar.png"}]}}}
    """.trimIndent()

    @Test fun concurrentCallersShareOneChannelRead() = runBlocking {
        val (directory, calls, _) = directory(200, channelBody)
        val avatars = listOf(async { directory.avatar("UCabc") }, async { directory.avatar("UCabc") }).awaitAll()
        assertEquals(listOf("https://yt3/avatar.png", "https://yt3/avatar.png"), avatars)
        assertEquals(1, calls.size)
        directory.dispose()
    }

    @Test fun cachedAvatarsServeLaterCallsWithinTheirValidity() = runBlocking {
        val (directory, calls, _) = directory(200, channelBody)
        assertEquals("https://yt3/avatar.png", directory.avatar("UCabc"))
        assertEquals("https://yt3/avatar.png", directory.avatar("UCabc"))
        assertEquals("a cached channel must not hit the network again", 1, calls.size)
        directory.dispose()
    }

    @Test fun failuresBackOffInsteadOfRetryingPerCard() = runBlocking {
        val (directory, calls, _) = directory(400, "{}")
        assertNull(directory.avatar("UCabc"))
        assertNull(directory.avatar("UCabc"))
        assertEquals("a failed channel must back off", 1, calls.size)
        directory.dispose()
    }

    @Test fun nonChannelIdentitiesNeverReachTheNetwork() = runBlocking {
        val (directory, calls, _) = directory(200, channelBody)
        assertNull(directory.avatar(""))
        assertNull(directory.avatar("bunny"))
        assertEquals(0, calls.size)
        directory.dispose()
    }
}
