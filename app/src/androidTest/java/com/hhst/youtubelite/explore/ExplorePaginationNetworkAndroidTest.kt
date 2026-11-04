package com.hhst.youtubelite.explore

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaExploreRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.explore.domain.ExploreCategory
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

class ExplorePaginationNetworkAndroidTest {
    @Test fun publicDirectoriesFollowPageAndShelfContinuations() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("explorePagination") == "1")
        assumeTrue(!MediaSession.snapshot().signedIn)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val generation = MediaSession.snapshot().generation
        val repository = MediaExploreRepository(GlobalContext.get().get<MediaClient>())
        val output = JSONObject().put("timeUtc", java.time.Instant.now().toString())
        val reads = JSONArray()
        output.put("reads", reads)
        var growingShelves = 0
        try {
            withTimeout(120_000) {
                for (category in ExploreCategory.entries) {
                    val first = repository.explore(category)
                    val ids = first.videos.mapTo(mutableSetOf()) { it.id }
                    reads.put(JSONObject().put("category", category.name).put("phase", "initial")
                        .put("videos", ids.size).put("sections", first.sections.size)
                        .put("hasContinuation", first.nextKey != null))
                    var cursor = first.nextKey
                    val visited = mutableSetOf<String>()
                    repeat(3) { index ->
                        val key = cursor ?: return@repeat
                        assertTrue("Page cursor cycle for $category", visited.add(key))
                        val page = repository.exploreNext(category, key)
                        ids += page.videos.map { it.id }
                        reads.put(JSONObject().put("category", category.name).put("phase", "page-${index + 1}")
                            .put("videos", page.videos.size).put("uniqueVideos", ids.size)
                            .put("hasContinuation", page.nextKey != null))
                        cursor = page.nextKey
                    }
                    if (category !in listOf(ExploreCategory.Sports, ExploreCategory.News)) continue
                    val shelf = first.sections.firstOrNull { it.nextKey != null } ?: continue
                    val shelfIds = shelf.videos.mapTo(mutableSetOf()) { it.id }
                    val initialCount = shelfIds.size
                    cursor = shelf.nextKey
                    visited.clear()
                    repeat(4) { index ->
                        val key = cursor ?: return@repeat
                        assertTrue("Shelf cursor cycle for $category", visited.add(key))
                        val next = repository.exploreShelf(category, key)
                        shelfIds += next.videos.map { it.id }
                        reads.put(JSONObject().put("category", category.name).put("phase", "shelf-${index + 1}")
                            .put("title", shelf.title).put("videos", next.videos.size)
                            .put("uniqueVideos", shelfIds.size).put("hasContinuation", next.nextKey != null))
                        cursor = next.nextKey
                    }
                    if (shelfIds.size > initialCount) growingShelves++
                }
                assertTrue("Expected a public shelf continuation to add videos", growingShelves > 0)
                assertEquals(generation, MediaSession.snapshot().generation)
                assertFalse(MediaSession.snapshot().signedIn)
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "explore-pagination-network.json").writeText(output.toString(2))
            instrumentation.sendStatus(2, Bundle().apply { putString("explorePagination", output.toString()) })
        }
    }
}
