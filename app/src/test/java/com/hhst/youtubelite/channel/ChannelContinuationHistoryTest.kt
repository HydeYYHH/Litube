package com.hhst.youtubelite.channel

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.library.domain.PlaylistCollectionPage
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelContinuationHistoryTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val tabs = listOf(ChannelTab.Videos, ChannelTab.Shorts, ChannelTab.Live,
        ChannelTab.Posts, ChannelTab.Playlists, ChannelTab.Search)

    /** Every section deliberately uses the same wire tokens: their histories must stay separate. */
    private class CyclingRepository : ChannelRepository by FixtureChannelRepository() {
        private val base = FixtureChannelRepository()
        private val video = FixtureChannelRepository.sampleVideos().first()
        private val short = FixtureChannelRepository.sampleShorts().first()
        private val live = FixtureChannelRepository.sampleLive().all.first().copy(liveNow = false)
        private val playlist = FixturePlaylistRepository.summaries().first()
        val calls = mutableMapOf<ChannelTab, MutableList<String>>()
        val failingAppends = mutableSetOf<ChannelTab>()
        val failingFirstReads = mutableSetOf<ChannelTab>()
        var repeatSelf = false

        private fun first(tab: ChannelTab) {
            if (tab in failingFirstReads) throw IOException("first page failed: $tab")
        }

        private fun next(tab: ChannelTab, cursor: String): String {
            calls.getOrPut(tab) { mutableListOf() } += cursor
            if (failingAppends.remove(tab)) throw IOException("append failed: $tab")
            return if (repeatSelf) cursor else if (cursor == "A") "B" else "A"
        }

        override val serverSearch = true
        override suspend fun channel(channelId: String): ChannelPage {
            first(ChannelTab.Videos)
            return base.channel(channelId).copy(videos = listOf(video.copy(id = "Videos-first")),
                videosNextKey = "A", playlistsDeferred = true)
        }
        override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
            if (continuation == null) first(ChannelTab.Videos)
            return ChannelVideosPage(listOf(video.copy(id = "Videos-${continuation ?: "first"}")),
                continuation?.let { next(ChannelTab.Videos, it) } ?: "A")
        }
        override suspend fun shorts(channelId: String): ChannelShortsPage {
            first(ChannelTab.Shorts)
            return ChannelShortsPage(listOf(short.copy(id = "Shorts-first")), "A")
        }
        override suspend fun shortsNext(channelId: String, cursor: String) = ChannelShortsPage(
            listOf(short.copy(id = "Shorts-$cursor")), next(ChannelTab.Shorts, cursor))
        override suspend fun live(channelId: String): ChannelLivePage {
            first(ChannelTab.Live)
            return ChannelLivePage(past = listOf(live.copy(id = "Live-first")), nextKey = "A")
        }
        override suspend fun liveNext(channelId: String, cursor: String) = ChannelLivePage(
            past = listOf(live.copy(id = "Live-$cursor")), nextKey = next(ChannelTab.Live, cursor))
        override suspend fun posts(channelId: String): ChannelPostsPage {
            first(ChannelTab.Posts)
            return ChannelPostsPage(base.posts(channelId).posts.take(1).map {
                it.copy(identity = it.identity.copy(postId = "Posts-first"))
            }, "A")
        }
        override suspend fun postsNext(channelId: String, cursor: String) = ChannelPostsPage(
            base.posts(channelId).posts.take(1).map { it.copy(identity = it.identity.copy(postId = "Posts-$cursor")) },
            next(ChannelTab.Posts, cursor))
        override suspend fun playlists(channelId: String): PlaylistCollectionPage {
            first(ChannelTab.Playlists)
            return PlaylistCollectionPage(listOf(playlist.copy(id = "Playlists-first")), "A")
        }
        override suspend fun playlistsNext(channelId: String, cursor: String) = PlaylistCollectionPage(
            listOf(playlist.copy(id = "Playlists-$cursor")), next(ChannelTab.Playlists, cursor))
        override suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults {
            first(ChannelTab.Search)
            return ChannelSearchResults(listOf(video.copy(id = "Search-first")), "A")
        }
        override suspend fun searchNext(channelId: String, cursor: String) = ChannelSearchResults(
            listOf(video.copy(id = "Search-$cursor")), next(ChannelTab.Search, cursor))
    }

    private fun ChannelViewModel.open(tab: ChannelTab) {
        onAction(ChannelAction.SelectTab(tab))
        if (tab == ChannelTab.Search) {
            onAction(ChannelAction.QueryChanged("movie"))
            onAction(ChannelAction.SubmitSearch)
        }
    }

    private fun ChannelViewModel.append(tab: ChannelTab) = onAction(when (tab) {
        ChannelTab.Videos -> ChannelAction.LoadVideosNext
        ChannelTab.Shorts -> ChannelAction.LoadShortsNext
        ChannelTab.Live -> ChannelAction.LoadLiveNext
        ChannelTab.Posts -> ChannelAction.LoadPostsNext
        ChannelTab.Playlists -> ChannelAction.LoadPlaylistsNext
        else -> ChannelAction.LoadSearchNext
    })

    private fun ChannelViewModel.refresh(tab: ChannelTab) = onAction(when (tab) {
        ChannelTab.Videos -> ChannelAction.Retry
        ChannelTab.Search -> ChannelAction.RetrySearch
        else -> ChannelAction.RetrySection(tab)
    })

    private fun ChannelViewModel.cursor(tab: ChannelTab) = uiState.value.let {
        when (tab) {
            ChannelTab.Videos -> (it.content as ChannelContent.Ready).page.videosNextKey
            ChannelTab.Shorts -> it.shortsNextKey
            ChannelTab.Live -> it.liveNextKey
            ChannelTab.Posts -> it.postsNextKey
            ChannelTab.Playlists -> it.playlistsNextKey
            else -> it.searchNextKey
        }
    }

    private fun ChannelViewModel.rows(tab: ChannelTab): List<String> = uiState.value.let {
        when (tab) {
            ChannelTab.Videos -> (it.content as ChannelContent.Ready).page.videos.map { row -> row.id }
            ChannelTab.Shorts -> (it.shorts as ChannelSection.Ready).value.map { row -> row.id }
            ChannelTab.Live -> (it.live as ChannelSection.Ready).value.all.map { row -> row.id }
            ChannelTab.Posts -> (it.posts as ChannelSection.Ready).value.map { row -> row.identity.postId }
            ChannelTab.Playlists -> (it.playlists as ChannelSection.Ready).value.map { row -> row.id }
            else -> (it.searchResults as ChannelSection.Ready).value.map { row -> row.id }
        }
    }

    @Test fun eachSectionStopsAtACursorCycleAndKeepsTheFinalPagesRows() = runTest {
        val repository = CyclingRepository()
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        for (tab in tabs) {
            model.open(tab)
            advanceUntilIdle()
            repeat(4) { model.append(tab); advanceUntilIdle() }
            assertEquals("$tab must stop before re-requesting A", listOf("A", "B"), repository.calls[tab])
            assertNull("$tab cursor", model.cursor(tab))
            assertEquals(listOf("$tab-first", "$tab-A", "$tab-B"), model.rows(tab))
        }
    }

    @Test fun selfRepeatingCursorsStillContributeTheirReturnedRows() = runTest {
        val repository = CyclingRepository().apply { repeatSelf = true }
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        for (tab in tabs) {
            model.open(tab)
            advanceUntilIdle()
            repeat(2) { model.append(tab); advanceUntilIdle() }
            assertEquals(listOf("A"), repository.calls[tab])
            assertEquals(listOf("$tab-first", "$tab-A"), model.rows(tab))
            assertNull(model.cursor(tab))
        }
    }

    @Test fun failedAppendDoesNotConsumeItsCursor() = runTest {
        val repository = CyclingRepository().apply { failingAppends += tabs }
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        for (tab in tabs) {
            model.open(tab)
            advanceUntilIdle()
            model.append(tab)
            advanceUntilIdle()
            assertEquals("A", model.cursor(tab))
            repeat(3) { model.append(tab); advanceUntilIdle() }
            assertEquals(listOf("A", "A", "B"), repository.calls[tab])
            assertEquals(listOf("$tab-first", "$tab-A", "$tab-B"), model.rows(tab))
            assertNull(model.cursor(tab))
        }
    }

    @Test fun failedRefreshRetainsHistoryAndSuccessfulRefreshStartsANewCycle() = runTest {
        for (tab in tabs) {
            val repository = CyclingRepository()
            val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
            advanceUntilIdle()
            model.open(tab)
            advanceUntilIdle()
            model.append(tab)
            advanceUntilIdle()
            val rows = model.rows(tab)
            repository.failingFirstReads += tab
            model.refresh(tab)
            advanceUntilIdle()
            assertEquals(rows, model.rows(tab))
            assertEquals("B", model.cursor(tab))
            repeat(3) { model.append(tab); advanceUntilIdle() }
            assertEquals(listOf("A", "B"), repository.calls[tab])
            assertNull(model.cursor(tab))

            repository.failingFirstReads -= tab
            model.refresh(tab)
            advanceUntilIdle()
            assertEquals("A", model.cursor(tab))
            repeat(3) { model.append(tab); advanceUntilIdle() }
            assertEquals(listOf("A", "B", "A", "B"), repository.calls[tab])
            assertEquals(listOf("$tab-first", "$tab-A", "$tab-B"), model.rows(tab))
            assertNull(model.cursor(tab))
        }
    }

    @Test fun failedWholePageRefreshPreservesTheRetainedSectionsHistory() = runTest {
        val repository = CyclingRepository()
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        model.open(ChannelTab.Shorts)
        advanceUntilIdle()
        model.append(ChannelTab.Shorts)
        advanceUntilIdle()
        repository.failingFirstReads += ChannelTab.Videos
        model.onAction(ChannelAction.Retry)
        advanceUntilIdle()
        repeat(3) { model.append(ChannelTab.Shorts); advanceUntilIdle() }
        assertEquals(listOf("A", "B"), repository.calls[ChannelTab.Shorts])
        assertEquals(listOf("Shorts-first", "Shorts-A", "Shorts-B"), model.rows(ChannelTab.Shorts))
        assertNull(model.cursor(ChannelTab.Shorts))
    }

    @Test fun accountRefreshStartsAnIndependentCycleForEverySection() = runTest {
        val repository = CyclingRepository()
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        repeat(2) { cycle ->
            if (cycle > 0) { model.refreshSession(); advanceUntilIdle() }
            for (tab in tabs) {
                model.open(tab)
                advanceUntilIdle()
                repeat(3) { model.append(tab); advanceUntilIdle() }
                assertEquals(List(cycle + 1) { listOf("A", "B") }.flatten(), repository.calls[tab])
                assertNull(model.cursor(tab))
            }
        }
    }

    @Test fun aDeferredVideosRefreshRejectsAnObsoleteAppendBeforeItCanConsumeTheNewCycle() = runTest {
        val base = FixtureChannelRepository()
        val oldAppend = CompletableDeferred<Unit>()
        val video = FixtureChannelRepository.sampleVideos().first()
        var firstReads = 0
        var appendReads = 0
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String) = base.channel(channelId).copy(
                videos = emptyList(), videosDeferred = true)
            override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
                if (continuation == null) return ChannelVideosPage(
                    listOf(video.copy(id = "first-${++firstReads}")), "A")
                if (++appendReads == 1) return withContext(NonCancellable) {
                    oldAppend.await()
                    ChannelVideosPage(listOf(video.copy(id = "obsolete")), "B")
                }
                return ChannelVideosPage(listOf(video.copy(id = "current-A")), "A")
            }
        }
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        model.open(ChannelTab.Videos)
        advanceUntilIdle()
        model.append(ChannelTab.Videos)
        runCurrent()
        model.onAction(ChannelAction.RetrySection(ChannelTab.Videos))
        runCurrent()
        oldAppend.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first-2"), model.rows(ChannelTab.Videos))
        assertEquals("A", model.cursor(ChannelTab.Videos))
        model.append(ChannelTab.Videos)
        advanceUntilIdle()
        assertEquals(listOf("first-2", "current-A"), model.rows(ChannelTab.Videos))
        assertEquals(2, appendReads)
        assertNull(model.cursor(ChannelTab.Videos))
    }
}
