package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.domain.SubscribedChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ContentBrowseReuseTest {
    private val identity = RequestContext("account", "token", generation = 7)
    private val subscriptionBody = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
        {"tabRenderer":{"title":"Ada","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"channel-filter"}},
        "presentationStyle":{"style":"NEW_CONTENT"}}}]}},
        "videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"}}}"""
    private val channelBody = """{"metadata":{"channelMetadataRenderer":{"title":"Ada","externalId":"UCada"}},
        "contents":{"tabs":[{"tabRenderer":{"title":"Home","selected":true,"content":{"items":[]}}},
        {"tabRenderer":{"title":"Videos","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"videos"}}}},
        {"tabRenderer":{"title":"Shorts","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"shorts"}}}},
        {"tabRenderer":{"title":"Live","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"live"}}}},
        {"tabRenderer":{"title":"Playlists","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"playlists"}}}}]}}"""
    private val rows = """{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"}},
        "playlistRenderer":{"playlistId":"PLada","title":{"simpleText":"Playlist"}}}"""

    private class Harness(initial: RequestContext, reply: (String, String) -> Pair<Int, String>) {
        val context = AtomicReference(initial)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += request.url.encodedPath to body
            val (code, text) = reply(request.url.encodedPath, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(text.toResponseBody()).build()
        }.build(), { context.get() })
    }

    @Test fun landingEndpointsRemoveTheRepeatedHomeAndHandleResolution() = runBlocking {
        val h = Harness(identity) { path, body -> 200 to when {
            path.endsWith("resolve_url") -> """{"browseEndpoint":{"browseId":"UCada"}}"""
            body.contains("\"params\"") -> rows
            else -> channelBody
        } }
        val repository = MediaChannelRepository(h.client, MediaPostRepository(h.client))
        val opened = repository.channel("@ada")
        assertEquals(2, h.requests.size) // resolve + landing
        assertEquals("abcdefghijk", repository.videos("UCada", opened).videos.single().id)
        assertEquals("abcdefghijk", repository.shorts("UCada", opened).shorts.single().id)
        assertEquals("abcdefghijk", repository.live("UCada", opened).past.single().id)
        assertEquals("PLada", repository.playlists("UCada", opened).playlists.single().id)
        assertEquals(6, h.requests.size) // one request per deferred tab, no resolve/home repeats
        assertEquals(1, h.requests.count { it.first.endsWith("resolve_url") })
        assertEquals(1, h.requests.count { it.first.endsWith("browse") && !it.second.contains("\"params\"") })
        repository.videos("UCada", null)
        assertEquals(8, h.requests.size) // compatibility entry still discovers its own endpoint
    }

    @Test fun aLandingFromAnotherIdentityOrRepositoryCannotSupplyEndpoints() = runBlocking {
        val changes = listOf(identity.copy(accountId = "other"), identity.copy(accessToken = "new-token"),
            identity.copy(generation = 8), identity.copy(visitor = "other"), identity.copy(language = "de"))
        for (changed in changes) {
            val h = Harness(identity) { _, body -> 200 to if (body.contains("\"params\"")) rows else channelBody }
            val repository = MediaChannelRepository(h.client, MediaPostRepository(h.client))
            val opened = repository.channel("UCada")
            h.context.set(changed)
            repository.videos("UCada", opened)
            assertEquals(3, h.requests.size)
            val other = MediaChannelRepository(h.client, MediaPostRepository(h.client))
            other.videos("UCada", opened)
            assertEquals(5, h.requests.size)
        }
    }

    @Test fun aDeferredFailureRetriesOnlyItsEndpointAndAnAbsentTabNeedsNoRequest() = runBlocking {
        var failed = false
        val h = Harness(identity) { _, body ->
            if (!body.contains("\"params\"")) 200 to channelBody
            else if (!failed) { failed = true; 403 to "{}" }
            else 200 to rows
        }
        val repository = MediaChannelRepository(h.client, MediaPostRepository(h.client))
        val opened = repository.channel("UCada")
        try { repository.videos("UCada", opened); fail("expected rejected tab") } catch (_: MediaException) { }
        assertEquals("Ada", opened.profile.name)
        assertEquals("abcdefghijk", repository.videos("UCada", opened).videos.single().id)
        assertEquals(3, h.requests.size)
        val empty = Harness(identity) { _, _ -> 200 to """{"contents":{"tabRenderer":{"title":"Home","selected":true,"content":{"items":[]}}}}""" }
        val noTabs = MediaChannelRepository(empty.client, MediaPostRepository(empty.client))
        assertTrue(noTabs.shorts("UCada", noTabs.channel("UCada")).shorts.isEmpty())
        assertEquals(1, empty.requests.size)
    }

    @Test fun subscriptionsPairInEitherOrderAndEveryNewPairIsFresh() = runBlocking {
        for (directoryFirst in listOf(false, true)) {
            var clock = 0L
            val h = Harness(identity) { _, _ -> 200 to subscriptionBody }
            val repository = MediaSubscriptionsRepository(h.client, now = { clock })
            repeat(5) { round ->
                if (directoryFirst) {
                    assertTrue(repository.subscribedChannelsPage().channels.single().hasNewContent)
                    assertEquals("abcdefghijk", repository.subscriptions(SubscriptionsFilter.All).items.single().id)
                } else {
                    repository.subscriptions(SubscriptionsFilter.All)
                    assertEquals("channel-filter", repository.subscribedChannelsPage().channels.single().subscriptionParams)
                }
                assertEquals(round + 1, h.requests.size)
            }
            repository.subscriptions(SubscriptionsFilter.All)
            repository.subscriptions(SubscriptionsFilter.All)
            assertEquals(7, h.requests.size) // same consumer never reuses its own completed read
            clock = 1_001
            repository.subscribedChannelsPage()
            assertEquals(8, h.requests.size) // foreground directory cannot reuse an old feed
        }
    }

    @Test fun identitiesChannelParamsAndCursorsNeverJoinTheGlobalInitialRead() = runBlocking {
        val changes = listOf(identity.copy(accountId = "other"), identity.copy(accessToken = "new-token"),
            identity.copy(generation = 8), identity.copy(visitor = "other"), identity.copy(region = "DE"),
            identity.copy(onBehalfOfUser = "brand"))
        for (changed in changes) {
            val h = Harness(identity) { _, _ -> 200 to subscriptionBody }
            val repository = MediaSubscriptionsRepository(h.client)
            repository.subscriptions(SubscriptionsFilter.All)
            h.context.set(changed)
            repository.subscribedChannelsPage()
            assertEquals(2, h.requests.size)
        }
        val h = Harness(identity) { _, _ -> 200 to subscriptionBody }
        val repository = MediaSubscriptionsRepository(h.client)
        repository.subscriptions(SubscriptionsFilter.All)
        repository.channelSubscriptions(SubscribedChannel("UCada", "Ada", "", "", subscriptionParams = "specific"), SubscriptionsFilter.All)
        repository.subscriptions(SubscriptionsFilter.All, "cursor")
        assertEquals(3, h.requests.size)
        assertTrue(h.requests[1].second.contains("\"params\":\"specific\""))
        assertTrue(h.requests[2].second.contains("\"continuation\":\"cursor\""))
    }

    @Test fun cookieDirectoryKeepsItsIndependentGuideRequest() = runBlocking {
        val h = Harness(identity.copy(accessToken = null, webSession = WebSession("SAPISID=fixture"))) { path, _ ->
            200 to if (path.endsWith("guide")) """{"guideEntryRenderer":{"title":{"simpleText":"Ada"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCada"}}}}""" else subscriptionBody
        }
        val repository = MediaSubscriptionsRepository(h.client)
        repository.subscriptions(SubscriptionsFilter.All)
        assertEquals("UCada", repository.subscribedChannelsPage().channels.single().channelId)
        assertEquals(listOf("browse", "guide"), h.requests.map { it.first.substringAfterLast('/') })
    }

    @Test fun aWaitingDirectorySuspendsWithoutHoldingAWorkerAndSurvivesOwnerCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestNumber = java.util.concurrent.atomic.AtomicInteger()
        val h = Harness(identity) { _, _ ->
            if (requestNumber.incrementAndGet() == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            200 to subscriptionBody
        }
        val repository = MediaSubscriptionsRepository(h.client)
        val feed = async(Dispatchers.Default) { repository.subscriptions(SubscriptionsFilter.All) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val directory = async(Dispatchers.Default) { repository.subscribedChannelsPage() }
        val workersEntered = CountDownLatch(3)
        val releaseWorkers = CountDownLatch(1)
        val probes = List(3) { async(Dispatchers.Default) { h.client.read { _, _ ->
            workersEntered.countDown(); releaseWorkers.await(5, TimeUnit.SECONDS)
        } } }
        try {
            assertTrue("waiting consumer must leave all other workers usable", workersEntered.await(3, TimeUnit.SECONDS))
        } finally { releaseWorkers.countDown() }
        probes.awaitAll()
        feed.cancelAndJoin()
        release.countDown()
        assertEquals("UCada", withTimeout(5_000) { directory.await() }.channels.single().channelId)
        assertEquals(2, h.requests.size)
    }

    @Test fun aFailedFeedDoesNotPoisonTheIndependentDirectoryOrTheNextRefresh() = runBlocking {
        var count = 0
        val h = Harness(identity) { _, _ -> if (++count == 1) 403 to "{}" else 200 to subscriptionBody }
        val repository = MediaSubscriptionsRepository(h.client)
        try { repository.subscriptions(SubscriptionsFilter.All); fail("expected HTTP failure") } catch (_: MediaException) { }
        assertEquals("UCada", repository.subscribedChannelsPage().channels.single().channelId)
        repository.subscriptions(SubscriptionsFilter.All)
        assertEquals(2, h.requests.size)
    }
}
