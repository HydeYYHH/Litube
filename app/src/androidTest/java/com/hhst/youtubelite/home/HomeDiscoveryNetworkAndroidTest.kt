package com.hhst.youtubelite.home

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.HomeCatalog
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPlaylist
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class HomeDiscoveryNetworkAndroidTest {
    @Test fun emptyAnonymousHomeServesPlayablePublicDestinations() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("homeDiscovery") == "1")
        assumeTrue(!MediaSession.snapshot().signedIn)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val beforeGeneration = MediaSession.snapshot().generation
        val client = GlobalContext.get().get<MediaClient>()
        val repository = MediaHomeRepository(client)
        val output = JSONObject().put("timeUtc", java.time.Instant.now().toString())
        try {
            withTimeout(120_000) {
                val started = SystemClock.elapsedRealtime()
                val page = repository.loadHome("All")
                output.put("elapsedMs", SystemClock.elapsedRealtime() - started).put("itemCount", page.items.size)
                    .put("partialFailure", page.partialFailure).put("hasContinuation", page.nextKey != null)
                    .put("catalog", page.catalog)
                assertTrue(page.items.isNotEmpty())
                assertTrue(page.items.all { it is FeedItem.Video })
                assertTrue(page.items.filterIsInstance<FeedItem.Video>().all { it.notInterestedToken == null && it.menuEndpoint == null })
                val charts = repository.loadHome("Music charts")
                assertEquals(HomeCatalog.MusicCharts, charts.catalog)
                val chart = charts.items.filterIsInstance<FeedItem.Playlist>().first()
                val playlist = client.read { context, http -> loadPlaylist(chart.summary.id, context, http = http) }
                assertTrue(playlist.ok)
                assertTrue(requireNotNull(playlist.value).videos.isNotEmpty())
                output.put("chartId", chart.summary.id).put("chartTitle", playlist.value?.title)
                    .put("chartVideos", playlist.value?.videos?.size)
                page.nextKey?.let { cursor ->
                    val next = repository.loadHome("All", cursor)
                    output.put("nextCount", next.items.size).put("nextPartialFailure", next.partialFailure)
                    assertEquals(page.catalog, next.catalog)
                    assertTrue(next.items.all { it is FeedItem.Video })
                }
                for (filter in listOf("Gaming", "Sports", "News")) {
                    val category = repository.loadHome(filter)
                    assertEquals(HomeCatalog.forFilter(filter), category.catalog)
                    assertTrue(category.items.isNotEmpty())
                    output.put(filter, JSONObject().put("count", category.items.size).put("hasContinuation", category.nextKey != null))
                }
                assertEquals(beforeGeneration, MediaSession.snapshot().generation)
                assertFalse(MediaSession.snapshot().signedIn)
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "home-discovery-network.json").writeText(output.toString(2))
            instrumentation.sendStatus(2, Bundle().apply { putString("homeDiscovery", output.toString()) })
        }
    }
}
