package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MediaWatchRepository
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.watch.domain.WatchRelated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in real-service verification: menu reads only, no playback or account feedback writes. */
class RecommendationMenuProbeAndroidTest {
    @Test fun homeAndRelatedMenusResolveBothActionsWithoutInlineEndpoints() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue(MediaSession.signedIn())
        val report = JSONArray()
        var expectedTitle: String? = null
        var matchesRequestedVideo = false
        var menuReads = 0
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path in setOf("next", "player", "browse", "get_panel", "token")) { "Unexpected operation" }
            chain.proceed(chain.request()).also { response ->
                if (path == "get_panel") {
                    menuReads++
                    val title = runCatching { JSONObject(response.peekBody(1024 * 1024).string())
                        .getJSONObject("content").getJSONObject("engagementPanelSectionListRenderer")
                        .getJSONObject("header").getJSONObject("overlayPanelHeaderRenderer")
                        .getJSONObject("title").getString("simpleText") }.getOrNull()
                    matchesRequestedVideo = title == expectedTitle
                }
            }
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val repository = MediaWatchRepository(client)
        val page = repository.watch(args.getString("videoId") ?: "GjjK6yD5I9I")
        suspend fun verifyRelated(surface: String, cards: List<WatchRelated>) {
            for (video in cards.take(3)) {
                expectedTitle = video.title
                matchesRequestedVideo = false
                val input = video.copy(menuEndpoint = null, notInterestedToken = null, dontRecommendToken = null)
                val loaded = repository.menu(input)
                val beforeCache = menuReads
                assertEquals(loaded, repository.menu(input))
                assertEquals(beforeCache, menuReads)
                report.put(JSONObject().put("surface", surface).put("matchesRequestedVideo", matchesRequestedVideo)
                    .put("notInterested", !loaded.notInterestedToken.isNullOrBlank())
                    .put("dontRecommend", !loaded.dontRecommendToken.isNullOrBlank()))
                assertTrue("Menu must belong to the requested video", matchesRequestedVideo)
                assertFalse(loaded.notInterestedToken.isNullOrBlank())
                assertFalse(loaded.dontRecommendToken.isNullOrBlank())
            }
        }
        try {
            assertTrue(page.related.size >= 3)
            verifyRelated("watch", page.related)
            page.nextKey?.let { verifyRelated("watch-continuation", repository.next(page.video.id, it).items) }
            val home = MediaHomeRepository(client)
            val video = home.loadHome("All").items.filterIsInstance<FeedItem.Video>().first()
            expectedTitle = video.title
            matchesRequestedVideo = false
            val loaded = home.menu(video.copy(menuEndpoint = null, notInterestedToken = null, dontRecommendToken = null))
            report.put(JSONObject().put("surface", "home").put("matchesRequestedVideo", matchesRequestedVideo)
                .put("notInterested", !loaded.notInterestedToken.isNullOrBlank())
                .put("dontRecommend", !loaded.dontRecommendToken.isNullOrBlank()))
            assertTrue(matchesRequestedVideo)
            assertFalse(loaded.notInterestedToken.isNullOrBlank())
            assertFalse(loaded.dontRecommendToken.isNullOrBlank())
        } finally {
            // Only booleans and surface names; never save credentials, IDs, params or tokens.
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                "recommendation-menus.json").writeText(report.toString(2))
        }
    }
}
