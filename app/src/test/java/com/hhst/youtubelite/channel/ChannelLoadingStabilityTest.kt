package com.hhst.youtubelite.channel

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.library.domain.PlaylistCollectionPage
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelLoadingStabilityTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun livePaginationAdvancesTheScreenCursorAndStopsAtTheEnd() = runTest {
        val base = FixtureChannelRepository()
        val video = FixtureChannelRepository.sampleLive().all.first().copy(liveNow = false)
        val requests = mutableListOf<String>()
        val repository = object : ChannelRepository by base {
            override suspend fun live(channelId: String) = ChannelLivePage(
                past = listOf(video.copy(id = "live-1")), nextKey = "live-2")
            override suspend fun liveNext(channelId: String, cursor: String): ChannelLivePage {
                requests += cursor
                return ChannelLivePage(past = listOf(video.copy(id = cursor)),
                    nextKey = if (cursor == "live-2") "live-3" else null)
            }
        }
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        model.onAction(ChannelAction.SelectTab(ChannelTab.Live))
        advanceUntilIdle()
        model.onAction(ChannelAction.LoadLiveNext)
        advanceUntilIdle()
        assertEquals("live-3", model.uiState.value.liveNextKey)
        model.onAction(ChannelAction.LoadLiveNext)
        advanceUntilIdle()
        model.onAction(ChannelAction.LoadLiveNext)
        advanceUntilIdle()
        assertEquals(listOf("live-2", "live-3"), requests)
        assertNull(model.uiState.value.liveNextKey)
        assertEquals(listOf("live-1", "live-2", "live-3"),
            (model.uiState.value.live as ChannelSection.Ready).value.all.map { it.id })
    }

    @Test fun repeatedVideoCursorCannotCreateAnAutomaticRequestLoop() = runTest {
        val base = FixtureChannelRepository()
        var calls = 0
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String) = base.channel(channelId).copy(videosNextKey = "repeat")
            override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
                calls++
                return ChannelVideosPage(FixtureChannelRepository.sampleVideos(), "repeat")
            }
        }
        val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        repeat(3) {
            model.onAction(ChannelAction.LoadVideosNext)
            advanceUntilIdle()
        }
        assertEquals(1, calls)
        assertNull((model.uiState.value.content as ChannelContent.Ready).page.videosNextKey)
    }

    @Test fun anObsoleteSearchAppendCannotChangeNewResultsOrTheirLoadingState() = runTest {
        for (fail in listOf(false, true)) {
            val base = FixtureChannelRepository()
            val oldResponse = CompletableDeferred<Unit>()
            val video = FixtureChannelRepository.sampleVideos().first()
            val repository = object : ChannelRepository by base {
                override val serverSearch = true
                override suspend fun searchVideos(channelId: String, query: String) = ChannelSearchResults(
                    listOf(video.copy(id = query)), nextKey = "$query-next")
                override suspend fun searchNext(channelId: String, cursor: String): ChannelSearchResults =
                    withContext(NonCancellable) {
                        oldResponse.await()
                        if (fail) throw java.io.IOException("old query failed")
                        ChannelSearchResults(listOf(video.copy(id = "obsolete")))
                    }
            }
            val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
            advanceUntilIdle()
            model.onAction(ChannelAction.QueryChanged("old"))
            model.onAction(ChannelAction.SubmitSearch)
            advanceUntilIdle()
            model.onAction(ChannelAction.LoadSearchNext)
            runCurrent()
            model.onAction(ChannelAction.QueryChanged("new"))
            model.onAction(ChannelAction.SubmitSearch)
            runCurrent()
            oldResponse.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("new"), (model.uiState.value.searchResults as ChannelSection.Ready).value.map { it.id })
            assertEquals("new-next", model.uiState.value.searchNextKey)
            assertEquals(AppendState.Idle, model.uiState.value.searchAppend)
        }
    }

    @Test fun emptyPagesWithChangingCursorsStayResumableAndAutomaticLoadingIsBounded() = runTest {
        val base = FixtureChannelRepository()
        val tabs = listOf(ChannelTab.Videos, ChannelTab.Shorts, ChannelTab.Live,
            ChannelTab.Posts, ChannelTab.Playlists, ChannelTab.Search)
        for (tab in tabs) {
            var calls = 0
            var recover = false
            fun nextCursor(): String? { calls++; return if (recover) null else "page-${calls + 1}" }
            val repository = object : ChannelRepository by base {
                override val serverSearch = true
                override suspend fun channel(channelId: String) = base.channel(channelId).copy(
                    videos = emptyList(), videosNextKey = "page-1", playlistsDeferred = true)
                override suspend fun shorts(channelId: String) = ChannelShortsPage(emptyList(), "page-1")
                override suspend fun shortsNext(channelId: String, cursor: String): ChannelShortsPage {
                    val next = nextCursor()
                    return ChannelShortsPage(if (recover) FixtureChannelRepository.sampleShorts() else emptyList(), next)
                }
                override suspend fun live(channelId: String) = ChannelLivePage(nextKey = "page-1")
                override suspend fun liveNext(channelId: String, cursor: String): ChannelLivePage {
                    val next = nextCursor()
                    return if (recover) FixtureChannelRepository.sampleLive().copy(nextKey = next) else ChannelLivePage(nextKey = next)
                }
                override suspend fun posts(channelId: String) = ChannelPostsPage(emptyList(), "page-1")
                override suspend fun postsNext(channelId: String, cursor: String): ChannelPostsPage {
                    val next = nextCursor()
                    return ChannelPostsPage(if (recover) base.posts(channelId).posts else emptyList(), next)
                }
                override suspend fun playlists(channelId: String) = PlaylistCollectionPage(emptyList(), "page-1")
                override suspend fun playlistsNext(channelId: String, cursor: String): PlaylistCollectionPage {
                    val next = nextCursor()
                    return PlaylistCollectionPage(if (recover) base.playlists(channelId).playlists else emptyList(), next)
                }
                override suspend fun searchVideos(channelId: String, query: String) = ChannelSearchResults(emptyList(), "page-1")
                override suspend fun searchNext(channelId: String, cursor: String): ChannelSearchResults {
                    val next = nextCursor()
                    return ChannelSearchResults(if (recover) FixtureChannelRepository.sampleVideos() else emptyList(), next)
                }
                override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
                    val next = nextCursor()
                    return ChannelVideosPage(if (recover) FixtureChannelRepository.sampleVideos() else emptyList(), next)
                }
            }
            val model = ChannelViewModel(repository, SavedStateHandle(), "blender")
            advanceUntilIdle()
            model.onAction(ChannelAction.SelectTab(tab))
            if (tab == ChannelTab.Search) {
                model.onAction(ChannelAction.QueryChanged("movie"))
                model.onAction(ChannelAction.SubmitSearch)
            }
            advanceUntilIdle()
            val load = when (tab) {
                ChannelTab.Videos -> ChannelAction.LoadVideosNext
                ChannelTab.Shorts -> ChannelAction.LoadShortsNext
                ChannelTab.Live -> ChannelAction.LoadLiveNext
                ChannelTab.Posts -> ChannelAction.LoadPostsNext
                ChannelTab.Playlists -> ChannelAction.LoadPlaylistsNext
                else -> ChannelAction.LoadSearchNext
            }
            fun append() = model.uiState.value.let {
                when (tab) {
                    ChannelTab.Videos -> it.videosAppend
                    ChannelTab.Shorts -> it.shortsAppend
                    ChannelTab.Live -> it.liveAppend
                    ChannelTab.Posts -> it.postsAppend
                    ChannelTab.Playlists -> it.playlistsAppend
                    else -> it.searchAppend
                }
            }
            // Emulate the UI's idle-only automatic tail observer.
            repeat(10) {
                if (append() == AppendState.Idle) model.onAction(load)
                advanceUntilIdle()
            }
            assertEquals("$tab must pause unproductive automatic requests", 3, calls)
            assertEquals(AppendState.Failed, append())
            recover = true
            model.onAction(load)
            advanceUntilIdle()
            assertEquals(4, calls)
            assertEquals(AppendState.Idle, append())
            val state = model.uiState.value
            val recovered = when (tab) {
                ChannelTab.Videos -> (state.content as ChannelContent.Ready).page.videos.isNotEmpty()
                ChannelTab.Shorts -> (state.shorts as ChannelSection.Ready).value.isNotEmpty()
                ChannelTab.Live -> (state.live as ChannelSection.Ready).value.all.isNotEmpty()
                ChannelTab.Posts -> (state.posts as ChannelSection.Ready).value.isNotEmpty()
                ChannelTab.Playlists -> (state.playlists as ChannelSection.Ready).value.isNotEmpty()
                else -> (state.searchResults as ChannelSection.Ready).value.isNotEmpty()
            }
            assertTrue("$tab must recover from its empty first page", recovered)
        }
    }
}
