package com.hhst.youtubelite.data.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        {"header":{"musicVisualHeaderRenderer":{"foregroundThumbnail":{"musicThumbnailRenderer":{
        "thumbnail":{"thumbnails":[{"url":"https://yt3/avatar.png"}]}}}}}}
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

    @Test fun transientFailuresRecoverWithoutWaitingMinutes() = runBlocking {
        var now = 0L
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (calls == 1) 503 else 200).message("Reply")
                .body((if (calls == 1) "{}" else channelBody).toResponseBody("application/json".toMediaType())).build()
        }.build())
        val directory = MediaAvatarDirectory(client, clock = { now })
        try {
            assertNull(directory.avatar("UCabc"))
            now = 4_999L
            assertNull(directory.avatar("UCabc"))
            assertEquals(1, calls)
            now = 5_001L
            assertEquals("https://yt3/avatar.png", directory.avatar("UCabc"))
            assertEquals(2, calls)
        } finally { directory.dispose() }
    }

    @Test fun aSuccessfulButSparseResponseCanRecoverItsMissingAvatar() = runBlocking {
        var now = 0L
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body((if (calls <= 2) """{"channelMetadataRenderer":{"externalId":"UCabc","title":"Blender"}}""" else channelBody).toResponseBody("application/json".toMediaType())).build()
        }.build())
        val directory = MediaAvatarDirectory(client, clock = { now })
        try {
            assertNull(directory.avatar("UCabc"))
            assertNull(directory.avatar("UCabc"))
            assertEquals(2, calls)
            now = 5_101
            assertEquals("https://yt3/avatar.png", directory.avatar("UCabc"))
            assertEquals(3, calls)
        } finally { directory.dispose() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledWaitersDoNotKeepCompletedRequestsPastExpiry() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            reads++
            if (reads == 1) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(channelBody.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val owner = SupervisorJob()
        var now = 0L
        val directory = MediaAvatarDirectory(client, { now }, CoroutineScope(owner + StandardTestDispatcher(testScheduler)))
        try {
            val waiter = launch { directory.avatar("UCabc") }
            runCurrent()
            // Session capture and the HTTP read resume through the test dispatcher.
            // Do not block that dispatcher while waiting for the worker to reach HTTP.
            check(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                started.await(5, TimeUnit.SECONDS)
            })
            val request = owner.children.single()
            waiter.cancelAndJoin()
            release.countDown()
            request.join()
            now = 8 * 24 * 60 * 60 * 1000L
            assertEquals("https://yt3/avatar.png", directory.avatar("UCabc"))
            assertEquals(2, reads)
        } finally {
            release.countDown()
            directory.dispose()
        }
    }
    @Test fun saturatedRequestsWaitWithoutDroppingAvatars() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            calls.incrementAndGet()
            started.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(channelBody.toResponseBody("application/json".toMediaType())).build()
            } finally { active.decrementAndGet() }
        }.build())
        val directory = MediaAvatarDirectory(client)
        try {
            val results = (1..70).map { id -> async { directory.avatar("UC$id") } }
            check(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            release.countDown()
            assertEquals(List(70) { "https://yt3/avatar.png" }, results.awaitAll())
            assertEquals(70, calls.get())
            org.junit.Assert.assertTrue(peak.get() <= 2)
        } finally { release.countDown(); directory.dispose() }
    }

    @Test fun completedAvatarsPublishBeforeASlowPeer() = runBlocking {
        val release = CountDownLatch(1)
        val fast = kotlinx.coroutines.CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val body = okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            if (body.contains("UCslow")) check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(channelBody.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val directory = MediaAvatarDirectory(client)
        try {
            val load = launch {
                directory.resolveAll(listOf("UCslow", "UCfast")) { id, _ ->
                    published += id
                    if (id == "UCfast") fast.complete(Unit)
                }
            }
            kotlinx.coroutines.withTimeout(4_000) { fast.await() }
            assertEquals(listOf("UCfast"), published)
            org.junit.Assert.assertFalse(load.isCompleted)
            release.countDown()
            load.join()
            assertEquals(setOf("UCfast", "UCslow"), published.toSet())
        } finally { release.countDown(); directory.dispose() }
    }

}
