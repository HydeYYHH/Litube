package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LiveChatDiscoveryTest {
    private val chat = """"liveChatRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"chat-key"}}]}"""
    private fun next(owner: String, chatField: String = "") = """{"videoDetails":{"videoId":"abcdefghijk","title":"$owner title","author":"$owner","channelId":"UC$owner"},
        "contents":[{"compactVideoRenderer":{"videoId":"$owner-related","title":{"simpleText":"$owner recommendation"}}}],
        "subscribeButtonRenderer":{"subscribed":true},"likeButtonRenderer":{"likeStatus":"LIKE"}${if (chatField.isBlank()) "" else ",$chatField"}}"""
    private fun player(live: Boolean) = """{"videoDetails":{"videoId":"abcdefghijk","isLive":$live,"isLiveContent":true},
        "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-11-04"}},
        "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?account=1"}}}"""

    private fun repository(live: Boolean = true, inline: Boolean = false, guest: Boolean = false,
        publicReply: () -> Pair<Int, String> = { 200 to next("Public", chat) }): Pair<MediaWatchRepository, MutableList<Pair<String, Boolean>>> {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url.encodedPath.substringAfterLast('/')
            val authenticated = request.header("Authorization") != null
            synchronized(calls) { calls += endpoint to authenticated }
            val (status, body) = when {
                endpoint == "player" -> 200 to player(live)
                authenticated -> 200 to next("Account", if (inline) chat else "")
                else -> publicReply()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Reply")
                .body(body.toResponseBody()).build()
        }.build()
        return MediaWatchRepository(MediaClient(http) { RequestContext(accessToken = if (guest) null else "test", generation = 7) }) to calls
    }

    @Test fun signedInLiveChatIsDiscoveredWithoutReplacingAccountMetadata() = runBlocking {
        val (repository, calls) = repository()
        val page = repository.watch("abcdefghijk")
        assertEquals("chat-key", page.video.chatKey)
        assertFalse(page.video.chatLookupFailed)
        assertEquals("Account", page.video.channel)
        assertEquals("Account title", page.video.title)
        assertTrue(page.video.subscribed)
        assertEquals(com.hhst.youtubelite.watch.domain.VideoRating.Like, page.video.rating)
        assertEquals(listOf("Account-related"), page.related.map { it.id })
        assertTrue(page.video.tracking?.playbackUrl.orEmpty().contains("account=1"))
        assertEquals(1, calls.count { it == "next" to false })
    }

    @Test fun aConfirmedChatDisabledStreamDoesNotFabricateAChat() = runBlocking {
        val (repository, _) = repository(publicReply = { 200 to next("Public") })
        val video = repository.watch("abcdefghijk").video
        assertNull(video.chatKey)
        assertFalse(video.chatLookupFailed)
        assertTrue(video.live)
    }

    @Test fun failedDiscoveryKeepsPlaybackAndCanBeRetriedFresh() = runBlocking {
        var unavailable = true
        val (repository, _) = repository(publicReply = { if (unavailable) 503 to "{}" else 200 to next("Public", chat) })
        val failed = repository.watch("abcdefghijk").video
        assertEquals("Account title", failed.title)
        assertTrue(failed.live)
        assertTrue(failed.chatLookupFailed)
        unavailable = false
        val recovered = repository.watchFresh("abcdefghijk").video
        assertEquals("chat-key", recovered.chatKey)
        assertFalse(recovered.chatLookupFailed)
    }

    @Test fun existingChatContinuationsDoNotCostAnotherRead() = runBlocking {
        val (repository, calls) = repository(inline = true)
        assertEquals("chat-key", repository.watch("abcdefghijk").video.chatKey)
        assertEquals(2, calls.size)
    }

    @Test fun archivedStreamsDoNotStartLiveChatDiscovery() = runBlocking {
        val (repository, calls) = repository(live = false)
        assertFalse(repository.watch("abcdefghijk").video.live)
        assertEquals(2, calls.size)
    }

    @Test fun guestsUseTheirExistingWebChatWithoutAnotherRead() = runBlocking {
        val (repository, calls) = repository(guest = true)
        assertEquals("chat-key", repository.watch("abcdefghijk").video.chatKey)
        assertEquals(2, calls.size)
        assertTrue(calls.none { it.second })
    }
}
