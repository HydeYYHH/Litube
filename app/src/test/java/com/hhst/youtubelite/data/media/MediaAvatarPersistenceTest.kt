package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.core.JsonCache
import com.liskovsoft.youtubeapi.videoinfo.V2.MenuEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MediaAvatarPersistenceTest {
    private class Disk : JsonCache {
        var value: String? = null
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: String, type: Class<T>): T? = value as T?
        override fun put(key: String, value: Any, ttlMs: Long) { this.value = value as String }
        override fun invalidate(key: String) { value = null }
    }
    private fun client(calls: AtomicInteger, body: String = "{}", status: Int = 503) =
        MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(status).message("Reply").body(body.toResponseBody()).build()
        }.build())

    @Test fun subscriptionArtworkSurvivesDirectoryRecreationWithoutNetwork() = runBlocking {
        val disk = Disk()
        val calls = AtomicInteger()
        val first = MediaAvatarDirectory(client(calls), disk = disk)
        first.seed(mapOf("UCabc" to "https://yt3.googleusercontent.com/avatar"))
        first.dispose()
        val reopened = MediaAvatarDirectory(client(calls), disk = disk)
        try {
            assertEquals("https://yt3.googleusercontent.com/avatar", reopened.avatar("UCabc"))
            assertEquals(0, calls.get())
            reopened.clear()
            assertNull(disk.value)
        } finally { reopened.dispose() }
    }

    @Test fun transientRefreshFailureKeepsTheLastKnownAvatarVisible() = runBlocking {
        var now = 0L
        val calls = AtomicInteger()
        val directory = MediaAvatarDirectory(client(calls), clock = { now })
        try {
            directory.seed(mapOf("UCabc" to "https://yt3.googleusercontent.com/avatar"))
            now = 8 * 24 * 60 * 60 * 1000L
            assertEquals("https://yt3.googleusercontent.com/avatar", directory.avatar("UCabc"))
            assertEquals("https://yt3.googleusercontent.com/avatar", directory.avatar("UCabc"))
            assertEquals(1, calls.get())
        } finally { directory.dispose() }
    }

    @Test fun missingHomeChannelIsRecoveredOnceForConcurrentCardsAndAcrossRestarts() = runBlocking {
        val calls = AtomicInteger()
        val disk = Disk()
        val body = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[
            {"listItemViewModel":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCabc"}}}}}
        ]}}}}}"""
        val directory = MediaAvatarDirectory(client(calls, body, 200), disk = disk)
        val endpoint = MenuEndpoint("panel", "params")
        try {
            assertEquals(List(20) { "UCabc" }, List(20) { async { directory.channel("video", endpoint) } }.awaitAll())
            assertEquals(1, calls.get())
        } finally { directory.dispose() }
        val reopened = MediaAvatarDirectory(client(calls), disk = disk)
        try {
            assertEquals("UCabc", reopened.channel("video", endpoint))
            assertEquals(1, calls.get())
        } finally { reopened.dispose() }
    }
}
