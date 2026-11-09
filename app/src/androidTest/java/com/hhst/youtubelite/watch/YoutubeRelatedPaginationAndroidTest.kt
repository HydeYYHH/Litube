package com.hhst.youtubelite.watch

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.ContentRepositories
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Explicitly invoked real-network acceptance for the watch related-feed continuation. */
class YoutubeRelatedPaginationAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(com.hhst.youtubelite.extractor.ExtractionTestActivity::class.java)

    @Test fun relatedFeedPagesBeyondTheFirstPage() = runBlocking(Dispatchers.IO) {
        val videoId = InstrumentationRegistry.getArguments().getString("relatedVideoId").orEmpty().trim()
        assumeTrue("Supply -e relatedVideoId <id> for real-network acceptance", videoId.isNotEmpty())
        val repository = GlobalContext.get().get<ContentRepositories>().watch
        val first = repository.watch(videoId)
        assertTrue("first page must carry related items", first.related.isNotEmpty())
        val cursor = first.nextKey
        assertTrue("first page must offer a continuation cursor", cursor != null)
        val second = repository.next(videoId, cursor!!)
        assertTrue("continuation must return items", second.items.isNotEmpty())
        val firstIds = first.related.map { it.id }.toSet()
        val fresh = second.items.count { it.id !in firstIds }
        assertTrue("continuation must add mostly fresh items (fresh=$fresh of ${second.items.size})",
            fresh * 2 >= second.items.size)
        val evidence = mapOf(
            "videoId" to videoId,
            "firstRelated" to first.related.size,
            "firstNextKeyPresent" to (cursor != null),
            "secondRelated" to second.items.size,
            "secondFresh" to fresh,
            "secondNextKeyPresent" to (second.nextKey != null),
        )
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "related-pagination").apply { mkdirs() }
        File(out, "evidence.json").writeText(evidence.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
    }
}
