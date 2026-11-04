package com.hhst.youtubelite.data.media

import android.os.SystemClock
import android.util.Log
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** App repository -> MSC -> MediaClient -> actual loopback sockets; no public requests. */
class ContentBrowseReuseAndroidTest {
    private val identity = RequestContext("account", "fixture-token", generation = 7)
    private val subscriptions = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
        "title":"Ada","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"channel-filter"}},
        "presentationStyle":{"style":"NEW_CONTENT"}}}]}},"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"}}}"""
    private val home = """{"metadata":{"channelMetadataRenderer":{"title":"Ada","externalId":"UCada"}},
        "contents":{"tabs":[{"tabRenderer":{"title":"Home","selected":true,"content":{"items":[]}}},
        {"tabRenderer":{"title":"Videos","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"videos"}}}},
        {"tabRenderer":{"title":"Shorts","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"shorts"}}}},
        {"tabRenderer":{"title":"Live","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"live"}}}},
        {"tabRenderer":{"title":"Playlists","endpoint":{"browseEndpoint":{"browseId":"UCada","params":"playlists"}}}}]}}"""
    private val rows = """{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"}},
        "playlistRenderer":{"playlistId":"PLada","title":{"simpleText":"Playlist"}},
        "continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""

    private fun client(server: MockWebServer, context: () -> RequestContext) = MediaClient(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }.build(), context)

    @Test fun fiveChannelVisitsUseOneLandingAndOneRequestPerDeferredTab() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.clone().readUtf8())
                    return MockResponse().setBody(when {
                        request.path!!.endsWith("resolve_url") -> """{"browseEndpoint":{"browseId":"UCada"}}"""
                        body.has("continuation") -> "{}"
                        body.has("params") -> rows
                        else -> home
                    })
                }
            }
            val context = AtomicReference(identity)
            val client = client(server) { context.get() }
            val repository = MediaChannelRepository(client, MediaPostRepository(client))
            repository.channel("UCada")
            repository.videos("UCada", null) // original, independently discover the tab endpoint
            assertEquals(3, server.requestCount)
            repeat(3) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            Log.i("MscContentReuse", "channel compatibility baseline: landing+Videos requests=3")
            repeat(5) { round ->
                val before = server.requestCount
                val start = SystemClock.elapsedRealtime()
                val opened = repository.channel("@ada")
                val videos = repository.videos(opened.profile.channelId, opened)
                assertEquals("abcdefghijk", videos.videos.single().id)
                assertEquals("next-page", videos.nextKey)
                assertEquals("abcdefghijk", repository.shorts("UCada", opened).shorts.single().id)
                assertEquals("abcdefghijk", repository.live("UCada", opened).past.single().id)
                assertEquals("PLada", repository.playlists("UCada", opened).playlists.single().id)
                assertNull(repository.videos("UCada", videos.nextKey).nextKey)
                assertEquals(7, server.requestCount - before) // resolve + home + four tabs + continuation
                val requests = List(7) { server.takeRequest(2, TimeUnit.SECONDS)!! }
                assertEquals(1, requests.count { it.path!!.endsWith("resolve_url") })
                val browse = requests.filter { it.path!!.endsWith("browse") }.map { JSONObject(it.body.readUtf8()) }
                assertEquals(1, browse.count { !it.has("params") && !it.has("continuation") })
                assertEquals(setOf("videos", "shorts", "live", "playlists"), browse.filter { it.has("params") }.map { it.getString("params") }.toSet())
                assertEquals("next-page", browse.single { it.has("continuation") }.getString("continuation"))
                Log.i("MscContentReuse", "channel round=$round requests=7 duplicateHome=0 elapsedMs=${SystemClock.elapsedRealtime() - start}")
            }
            val opened = repository.channel("UCada")
            context.set(identity.copy(accountId = "other", accessToken = "other-token", generation = 8))
            val before = server.requestCount
            repository.videos("UCada", opened)
            assertEquals(2, server.requestCount - before) // old-account endpoints must not be reused
        }
    }

    @Test fun fiveSubscriptionPairsShareOneInitialBrowseAndRefreshIdentityBoundariesRemainFresh() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(subscriptions)
            }
            val context = AtomicReference(identity)
            var clock = 0L
            val client = client(server) { context.get() }
            client.read { session, http -> com.liskovsoft.youtubeapi.videoinfo.V2.loadBrowse("FEsubscriptions", session, http = http).requireValue() }
            client.read { session, http -> com.liskovsoft.youtubeapi.videoinfo.V2.loadSubscribedChannelsPage(session, http = http).requireValue() }
            assertEquals(2, server.requestCount)
            repeat(2) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            Log.i("MscContentReuse", "subscriptions independent initial reads baseline requests=2")
            val repository = MediaSubscriptionsRepository(client, now = { clock })
            repeat(5) { round ->
                val before = server.requestCount
                val start = SystemClock.elapsedRealtime()
                when (round % 3) {
                    0 -> { repository.subscriptions(SubscriptionsFilter.All); repository.subscribedChannelsPage() }
                    1 -> { repository.subscribedChannelsPage(); repository.subscriptions(SubscriptionsFilter.All) }
                    else -> coroutineScope {
                        val feed = async { repository.subscriptions(SubscriptionsFilter.All) }
                        val directory = async { repository.subscribedChannelsPage() }
                        assertEquals("abcdefghijk", feed.await().items.single().id)
                        assertTrue(directory.await().channels.single().hasNewContent)
                    }
                }
                assertEquals(1, server.requestCount - before)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                val body = JSONObject(request.body.readUtf8())
                assertEquals("FEsubscriptions", body.getString("browseId"))
                assertFalse(body.has("params")); assertFalse(body.has("continuation"))
                Log.i("MscContentReuse", "subscriptions round=$round requests=1 baseline=2 elapsedMs=${SystemClock.elapsedRealtime() - start}")
            }
            var before = server.requestCount
            repository.subscriptions(SubscriptionsFilter.All)
            repository.subscriptions(SubscriptionsFilter.All)
            assertEquals(2, server.requestCount - before)
            clock += 1_001
            repository.subscribedChannelsPage()
            assertEquals(3, server.requestCount - before) // no old-feed reuse on directory refresh
            repository.subscriptions(SubscriptionsFilter.All) // pair the just-refreshed directory
            repository.subscriptions(SubscriptionsFilter.All) // leave an unpaired feed
            context.set(identity.copy(accountId = "brand", accessToken = "brand-token", generation = 8))
            before = server.requestCount
            repository.subscribedChannelsPage()
            assertEquals(1, server.requestCount - before)
        }
    }

    @Test fun aCancelledOwnerLeavesItsWaitingDirectoryUsable() = joinedDirectorySurvivesOwnerExit(cancelOwner = true)

    @Test fun aRejectedOwnerLeavesItsWaitingDirectoryUsable() = joinedDirectorySurvivesOwnerExit(cancelOwner = false)

    private fun joinedDirectorySurvivesOwnerExit(cancelOwner: Boolean) = runBlocking<Unit> {
        MockWebServer().use { server ->
            val firstRequest = CountDownLatch(1)
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (requests.incrementAndGet() == 1) {
                        firstRequest.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(403).setBody("{}")
                    }
                    return MockResponse().setBody(subscriptions)
                }
            }
            val directoryAcquired = CountDownLatch(1)
            val contextReads = AtomicInteger()
            val repository = MediaSubscriptionsRepository(client(server) {
                // Owner checks once at admission and once before HTTP; the joiner
                // checks at admission and completion, after acquiring its ticket.
                if (contextReads.incrementAndGet() == 4) directoryAcquired.countDown()
                identity
            })
            val owner = async(Dispatchers.Default) { runCatching { repository.subscriptions(SubscriptionsFilter.All) } }
            assertTrue(firstRequest.await(3, TimeUnit.SECONDS))
            val directory = async(Dispatchers.Default) { repository.subscribedChannelsPage() }
            try {
                assertTrue(directoryAcquired.await(3, TimeUnit.SECONDS))
                if (cancelOwner) owner.cancelAndJoin()
                release.countDown()
                if (!cancelOwner) assertTrue(owner.await().exceptionOrNull() is MediaException)
                assertEquals("UCada", withTimeout(5_000) { directory.await() }.channels.single().channelId)
                assertEquals(2, server.requestCount)
                // A waiter fallback is intentionally not retained; refresh reads anew.
                repository.subscriptions(SubscriptionsFilter.All)
                assertEquals(3, server.requestCount)
            } finally { release.countDown(); owner.cancel(); directory.cancel() }
        }
    }
}
