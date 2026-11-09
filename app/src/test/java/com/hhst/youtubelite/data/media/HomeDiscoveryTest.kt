package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.HomeCatalog
import com.liskovsoft.youtubeapi.videoinfo.V2.BrowsePage
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaGroup
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HomeDiscoveryTest {
    private fun video(id: String) = MediaItem("video", id, id, "Author", "https://example.com/$id.jpg", "1:00",
        actions = emptyList())
    private fun chart(id: String) = MediaItem("playlist", id, id, "Charts", "https://example.com/$id.jpg", null)
    private fun page(section: HomeSection, key: String? = null): BrowsePage {
        val title = when (section) {
            HomeSection.MUSIC -> "Top charts"
            HomeSection.GAMING -> "Trending videos"
            HomeSection.SPORTS -> "Trending"
            HomeSection.NEWS -> "Top news"
            else -> error("Unexpected section")
        }
        val items = if (section == HomeSection.MUSIC) listOf(chart("chart")) else listOf(video(section.name))
        return BrowsePage(items, listOf(MediaGroup(title, items, key)), emptyList(), "page-key")
    }

    @Test fun allInterleavesOnlyPopularVideosAndPreservesSourceOrder() = runTest {
        val result = HomeDiscovery { section, _, generation ->
            assertEquals(7L, generation)
            page(section)
        }.load(7)
        assertEquals(HomeCatalog.Popular, result.catalog)
        assertEquals(listOf("GAMING", "SPORTS", "NEWS"), result.items.map { it.id })
        assertTrue(result.items.all { it is FeedItem.Video })
        assertTrue(result.items.filterIsInstance<FeedItem.Video>().all { it.notInterestedToken == null && it.menuEndpoint == null })
        assertNull(result.nextKey)
        assertFalse(result.partialFailure)
    }

    @Test fun allSourcesStartBeforeWaitingForAnyPeer() = runTest {
        val ready = CompletableDeferred<Unit>()
        var started = 0
        val result = HomeDiscovery { section, _, _ ->
            if (++started == 3) ready.complete(Unit)
            ready.await()
            page(section)
        }.load(1)
        assertEquals(3, started)
        assertEquals(3, result.items.size)
    }

    @Test fun musicFindsChartsOnTheNextDirectoryPageAndUsesTheShelfCursor() = runTest {
        val musicCalls = mutableListOf<String?>()
        val discovery = HomeDiscovery { section, key, _ ->
            if (section != HomeSection.MUSIC) page(section) else {
                musicCalls += key
                when (key) {
                    null -> BrowsePage(emptyList(), emptyList(), emptyList(), "directory-next")
                    "directory-next" -> page(section, "chart-next")
                    "chart-next" -> BrowsePage(listOf(chart("second-chart")), emptyList(), emptyList(), null)
                    else -> error("Wrong continuation")
                }
            }
        }
        val first = discovery.load(2, HomeCatalog.MusicCharts)
        val next = discovery.load(2, HomeCatalog.MusicCharts, first.nextKey)
        assertEquals(listOf(null, "directory-next", "chart-next"), musicCalls)
        assertEquals("second-chart", (next.items.single() as FeedItem.Playlist).summary.id)
        assertNull(next.nextKey)
    }

    @Test fun partialFailureKeepsOtherSourcesAndRetriesOnlyTheMissingSource() = runTest {
        var fail = true
        val calls = mutableListOf<HomeSection>()
        val discovery = HomeDiscovery { section, _, _ ->
            calls += section
            if (section == HomeSection.GAMING && fail) throw IOException("offline")
            page(section)
        }
        val first = discovery.load(3)
        assertTrue(first.partialFailure)
        assertEquals(2, first.items.size)
        fail = false
        calls.clear()
        val next = discovery.load(3, continuation = first.nextKey)
        assertEquals(listOf(HomeSection.GAMING), calls)
        assertEquals(listOf("GAMING"), next.items.map { it.id })
        assertFalse(next.partialFailure)
        assertNull(next.nextKey)
    }

    @Test fun totalFailureAndCancellationAreNotConvertedIntoEmptySuccess() = runTest {
        val failure = runCatching { HomeDiscovery { _, _, _ -> throw IOException("offline") }.load(4) }.exceptionOrNull()
        assertTrue(failure is IOException)
        val cancellation = runCatching { HomeDiscovery { _, _, _ -> throw CancellationException() }.load(4) }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
    }

    @Test fun repeatedShelfCursorsStopAndNeverUseTheDirectoryCursor() = runTest {
        val discovery = HomeDiscovery { section, key, _ -> page(section, key ?: "same") }
        val first = discovery.load(5)
        assertNotNull(first.nextKey)
        assertNull(discovery.load(5, continuation = first.nextKey).nextKey)
    }

    @Test fun staleIdentityAndMalformedCursorsFailBeforeRequestsStart() = runTest {
        var calls = 0
        val discovery = HomeDiscovery { section, _, _ -> calls++; page(section, "next") }
        val first = discovery.load(6)
        calls = 0
        assertTrue(runCatching { discovery.load(7, continuation = first.nextKey) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { discovery.load(6, continuation = "discovery:invalid") }.exceptionOrNull() is MediaException)
        assertEquals(0, calls)
    }

    @Test fun unrelatedMusicPlaylistsAreNeverRelabeledAsCharts() = runTest {
        val result = HomeDiscovery { section, _, _ ->
            if (section == HomeSection.MUSIC) BrowsePage(listOf(chart("unrelated")),
                listOf(MediaGroup("Chill tunes", listOf(chart("unrelated")), null)), emptyList(), null)
            else page(section)
        }
        val failure = runCatching { result.load(8, HomeCatalog.MusicCharts) }.exceptionOrNull()
        assertTrue(failure is MediaException)
    }
    @Test fun unequalSourceLengthsInterleaveAndDuplicatesAppearOnlyOnce() = runTest {
        val discovery = HomeDiscovery { section, _, _ ->
            val ids = when (section) {
                HomeSection.GAMING -> listOf("g1", "common", "g2")
                HomeSection.SPORTS -> listOf("common", "s1")
                else -> listOf("n1")
            }
            val base = page(section)
            base.copy(groups = listOf(base.groups.single().copy(items = ids.map(::video))))
        }
        assertEquals(listOf("g1", "common", "n1", "s1", "g2"), discovery.load(9).items.map { it.id })
    }

    @Test fun categoryFilterReadsOnlyItsOwnShelfAndRejectsAnotherFiltersCursor() = runTest {
        val calls = mutableListOf<HomeSection>()
        val discovery = HomeDiscovery { section, _, _ -> calls += section; page(section, "next") }
        val first = discovery.load(10, HomeCatalog.Sports)
        assertEquals(listOf(HomeSection.SPORTS), calls)
        calls.clear()
        assertTrue(runCatching { discovery.load(10, HomeCatalog.News, first.nextKey) }.exceptionOrNull() is MediaException)
        assertTrue(calls.isEmpty())
    }

}
