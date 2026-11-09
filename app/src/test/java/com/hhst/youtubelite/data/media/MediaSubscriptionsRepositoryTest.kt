package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.domain.SubscribedChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaSubscriptionsRepositoryTest {
    private val channel = SubscribedChannel("UCYrxIIlUsH2xrWiQowOFqHw", "Selected", "avatar", "",
        subscriptionParams = "server-channel-filter")

    @Test fun channelFeedUsesTheServerFilterAndContinuationInsteadOfFilteringTheCombinedFeed() = runBlocking {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(request.url.encodedPath.endsWith("/browse"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"contents":[
                    {"videoRenderer":{"videoId":"selected","channelId":"${channel.channelId}","title":{"simpleText":"Selected video"}}},
                    {"videoRenderer":{"videoId":"unlinked","title":{"simpleText":"Unlinked author in channel feed"}}},
                    {"videoRenderer":{"videoId":"other","channelId":"UCother","title":{"simpleText":"Other channel"}}}
                ]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = MediaSubscriptionsRepository(MediaClient(http) { RequestContext(accessToken = "token") })
        val page = repo.channelSubscriptions(channel, SubscriptionsFilter.All)
        assertEquals(listOf("selected", "unlinked"), page.items.map { it.id })
        assertTrue(page.items.all { it.channelId == channel.channelId })
        assertEquals("Selected", page.items.last().author)
        assertTrue(requests.single().contains("\"browseId\":\"FEsubscriptions\""))
        assertTrue(requests.single().contains("\"params\":\"server-channel-filter\""))
        repo.channelSubscriptions(channel, SubscriptionsFilter.All, "channel-cursor")
        assertTrue(requests.last().contains("\"continuation\":\"channel-cursor\""))
        assertFalse(requests.last().contains("\"browseId\""))
        assertEquals(2, requests.size)
    }

    @Test fun aSignedOutChannelFeedNeverSendsAnAccountRequest() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { calls++; error("must not send") }.build()
        val repo = MediaSubscriptionsRepository(MediaClient(http) { RequestContext() })
        try {
            repo.channelSubscriptions(channel, SubscriptionsFilter.All)
            fail("Expected sign-in failure")
        } catch (failure: MediaException) {
            assertEquals(RequestFailure.AUTH, failure.failure.kind)
        }
        assertEquals(0, calls)
    }
}
