@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.engine.CastSource
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.player.service.PlaybackCommandRouter
import com.hhst.youtubelite.player.surface.GestureMath.GestureZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private lateinit var engine: FakeEngine
    private lateinit var viewModel: PlayerViewModel
    private var previousCommandHandler: PlaybackCommandRouter.Handler? = null

    @Before
    fun setMainDispatcher() {
        previousCommandHandler = PlaybackCommandRouter.handler
        // viewModelScope (init collectors, cast-handoff waiter) runs on Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        PlaybackCommandRouter.handler = previousCommandHandler
        Dispatchers.resetMain()
    }

    @Test
    fun gesturePrefKey_picksEmbeddedOrFullscreen() {
        assertEquals(
            PreferenceKeys.GESTURE_TAP_WINDOWED,
            PlayerViewModel.gesturePrefKey(GestureZone.TAP, fullscreen = false),
        )
        assertEquals(
            PreferenceKeys.GESTURE_TAP_FULLSCREEN,
            PlayerViewModel.gesturePrefKey(GestureZone.TAP, fullscreen = true),
        )
        assertEquals(
            PreferenceKeys.GESTURE_SEEK_WINDOWED,
            PlayerViewModel.gesturePrefKey(GestureZone.SEEK, fullscreen = false),
        )
        assertEquals(
            PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN,
            PlayerViewModel.gesturePrefKey(GestureZone.FULLSCREEN_SWIPE, fullscreen = true),
        )
    }

    // -- queue navigation priority (pure rules) --

    @Test
    fun queueNav_activeQueueWinsOverPagePlaylist() {
        // The local queue owns "next" even when the page advertises a playlist;
        // "previous" only comes from the queue (or page back as fallback).
        val nav = PlayerViewModel.queueNav(
            queueActive = true,
            queueHasNext = true,
            inQueueAndHasPrevious = false,
            pageHasPlaylist = true,
            pageCanGoBack = false,
        )
        assertEquals(PlayerViewModel.QueueNav(next = true, previous = false), nav)
    }

    @Test
    fun queueNav_pageBackIsPreviousOnlyFallback() {
        val nav = PlayerViewModel.queueNav(
            queueActive = true,
            queueHasNext = false,
            inQueueAndHasPrevious = false,
            pageHasPlaylist = false,
            pageCanGoBack = true,
        )
        assertEquals(PlayerViewModel.QueueNav(next = false, previous = true), nav)
    }

    @Test
    fun queueNav_withoutQueue_pagePlaylistOwnsBoth() {
        val nav = PlayerViewModel.queueNav(
            queueActive = false,
            queueHasNext = true,
            inQueueAndHasPrevious = true,
            pageHasPlaylist = true,
            pageCanGoBack = true,
        )
        assertEquals(PlayerViewModel.QueueNav(next = true, previous = true), nav)
    }

    // -- cast handoff readiness (pure predicate of startPlayback's wait) --

    @Test
    fun loadSettledFor_whenNewVideoPrepared() {
        val snapshot = PlaybackSnapshot(videoId = "abc", prepared = true)
        assertTrue(PlayerViewModel.loadSettledFor(snapshot, "abc"))
    }

    @Test
    fun castHandoff_notReadyWhileStillLoading() {
        val snapshot = PlaybackSnapshot(videoId = "abc", prepared = false)
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, "abc"))
    }

    @Test
    fun castHandoff_neverCastsPreviousVideo() {
        val snapshot = PlaybackSnapshot(videoId = "old", prepared = true)
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, "new"))
    }

    @Test
    fun castHandoff_readyOnExtractionError() {
        val snapshot = PlaybackSnapshot(videoId = "abc", error = "extract failed")
        assertTrue(PlayerViewModel.loadSettledFor(snapshot, "abc"))
    }

    @Test
    fun castHandoff_staleErrorDoesNotSettleNewVideo() {
        val snapshot = PlaybackSnapshot(videoId = "old", error = "extract failed")
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, "new"))
    }

    @Test
    fun castHandoff_neverWithoutVideoId() {
        val snapshot = PlaybackSnapshot(videoId = "abc", prepared = true)
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, null))
    }

    // -- Page ownership and native playback isolation --

    @Test
    fun acceptsPageCallback_rejectsOtherTabAndOlderDocument() {
        val owner = PageOrigin(tabId = 1L, documentGeneration = 4L)
        assertTrue(PlayerViewModel.acceptsPageCallback(owner, PageOrigin.HOST))
        assertTrue(
            PlayerViewModel.acceptsPageCallback(
                owner,
                PageOrigin(tabId = 1L, documentGeneration = 4L),
            ),
        )
        assertTrue(
            PlayerViewModel.acceptsPageCallback(
                owner,
                PageOrigin(tabId = 1L, documentGeneration = 5L),
            ),
        )
        assertFalse(
            PlayerViewModel.acceptsPageCallback(
                owner,
                PageOrigin(tabId = 2L, documentGeneration = 9L),
            ),
        )
        assertFalse(
            PlayerViewModel.acceptsPageCallback(
                owner,
                PageOrigin(tabId = 1L, documentGeneration = 3L),
            ),
        )
        assertTrue(
            PlayerViewModel.acceptsPageCallback(
                owner = null,
                incoming = PageOrigin(tabId = 2L, documentGeneration = 1L),
            ),
        )
    }

    @Test
    fun shouldExpandMiniOnReturn_watchOnly() {
        assertFalse(
            PlayerViewModel.shouldExpandMiniOnReturn(
                "https://m.youtube.com/shorts/aaaaaaaaaaa",
            ),
        )
        assertTrue(
            PlayerViewModel.shouldExpandMiniOnReturn(
                "https://m.youtube.com/watch?v=watchAAAAAA",
            ),
        )
        assertFalse(PlayerViewModel.shouldExpandMiniOnReturn("https://m.youtube.com/"))
    }

    private val watchId = "watchAAAAA1"
    private val shortsId = "shortszzzz1"
    private val watchUrl = "https://www.youtube.com/watch?v=$watchId"
    private val shortsUrl = "https://m.youtube.com/shorts/$shortsId"

    private fun bindViewModel() {
        engine = FakeEngine()
        val cache = MemJsonCache()
        val cast = CastController(ContextWrapper(null), OkHttpClient())
        viewModel = PlayerViewModel(
            engine,
            QueueRepository(cache),
            ExtensionManager(MemoryPrefStore()),
            cache,
            cast,
        )
    }

    @Test
    fun compactLayout_preservesPlaybackAndOnlyCollapsesTheOwningWatchPage() {
        bindViewModel()
        val owner = PageOrigin(tabId = 1L, documentGeneration = 2L)
        viewModel.playVideo(watchUrl, owner)
        engine.settle(watchId, positionMs = 42_000L)
        viewModel.setCompactPlayer(true)
        assertTrue(viewModel.isPlayerCompact(owner))
        assertFalse(viewModel.isPlayerCompact(PageOrigin(2L, 2L)))
        assertFalse(viewModel.isPlayerCompact(PageOrigin(1L, 1L)))
        assertFalse(viewModel.uiState.value.mini)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertTrue(engine.snapshot.value.isPlaying)

        viewModel.setFullscreen(true)
        assertFalse(viewModel.isPlayerCompact(owner))
        viewModel.setFullscreen(false)
        assertTrue(viewModel.isPlayerCompact(owner))
        viewModel.setCompactPlayer(false)
        assertFalse(viewModel.isPlayerCompact(owner))
        assertTrue(engine.snapshot.value.isPlaying)

        viewModel.setCompactPlayer(true)
        viewModel.enterMiniPlayer()
        assertFalse(viewModel.isPlayerCompact(owner))
        viewModel.onMiniClose()
        assertFalse(viewModel.isPlayerCompact(owner))
    }

    @Test
    fun shortsPausesLongVideoPreservesPositionAndRejectsLatePlayCallbacks() {
        bindViewModel()
        viewModel.playVideo(watchUrl)
        engine.settle(watchId, positionMs = 42_000L)
        viewModel.onShortsOpened()
        viewModel.playVideo(shortsUrl)
        viewModel.playVideo(watchUrl, PageOrigin(1, 1))
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(watchId, viewModel.uiState.value.videoId)
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.mini)
        viewModel.onShortsClosed()
        viewModel.onReturnToWatch(watchUrl)
        viewModel.playVideo(watchUrl)
        assertTrue(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.isPlaying)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test
    fun hidePlayer_fromOtherTabDoesNotHideCurrentPlayback() {
        bindViewModel()
        val owner = PageOrigin(tabId = 1L, documentGeneration = 1L)
        viewModel.playVideo(watchUrl, owner)
        engine.settle(watchId)
        viewModel.setPlayerLayout(10, 180, PageOrigin(tabId = 2L, documentGeneration = 1L))
        assertNull(viewModel.uiState.value.pageHeightDp)
        viewModel.setPlayerLayout(10, 180, owner)
        assertEquals(180, viewModel.uiState.value.pageHeightDp)
        viewModel.hidePlayer(PageOrigin(tabId = 2L, documentGeneration = 1L))
        assertTrue(viewModel.uiState.value.visible)
        viewModel.hidePlayer(owner)
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test
    fun onReturnToWatch_shortsCannotRestoreNativeSurface() {
        bindViewModel()
        viewModel.playVideo(shortsUrl)
        viewModel.onReturnToWatch(shortsUrl)
        assertFalse(viewModel.uiState.value.visible)
        assertTrue(engine.playCalls.isEmpty())
    }

    @Test
    fun recastPublishedSource_withoutSessionIsNoOp() {
        bindViewModel()
        engine.onCastManifestReload?.invoke()
        assertTrue(engine.playCalls.isEmpty())
    }

    @Test
    fun onMiniClose_cancelsLongVideoWithoutRestoringIt() {
        bindViewModel()
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        viewModel.enterMiniPlayer()
        viewModel.onMiniClose()
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    /**
     * Records playback commands and publishes test-driven snapshots. Only
     * [settle] marks a snapshot prepared, so recast waiters resume exactly
     * when the test decides.
     */
    private class FakeEngine : PlaybackApi {
        private val _snapshot = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

        val playCalls = mutableListOf<String>()
        var pauseLocalCount = 0

        /** Publishes a settled (prepared) and playing engine state for [videoId]. */
        fun settle(videoId: String, positionMs: Long = 0L) {
            _snapshot.value = _snapshot.value.copy(
                videoId = videoId,
                positionMs = positionMs,
                prepared = true,
                error = null,
                isPlaying = true,
            )
        }

        override var onEnded: (() -> Unit)? = null
        override var onCastError: (() -> Unit)? = null
        override var onCastManifestReload: (() -> Unit)? = null

        override fun play(urlOrId: String) {
            playCalls += urlOrId
            _snapshot.value = _snapshot.value.copy(
                videoId = VideoId.parse(urlOrId),
                url = urlOrId,
                prepared = false,
                isPlaying = false,
            )
        }
        override fun retry() = Unit
        override fun stop() = Unit
        override fun setVideoSurface(surface: SurfaceView?) = Unit
        override fun playOrPause() = Unit
        override fun play() = Unit
        override fun pause() { _snapshot.value = _snapshot.value.copy(isPlaying = false) }
        override fun cancelPending(pausePlayback: Boolean) {
            _snapshot.value = _snapshot.value.copy(isPlaying = false, prepared = false)
        }

        override fun pauseLocal() {
            pauseLocalCount++
            _snapshot.value = _snapshot.value.copy(isPlaying = false)
        }

        override fun markReceiverIdentity(videoId: String, title: String, author: String?) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekRelative(offsetMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setSpeedHold(active: Boolean) = Unit
        override fun setLoopMode(mode: LoopMode) = Unit
        override fun setQuality(label: String?) = Unit
        override fun setSubtitle(trackKey: String?): Boolean = true
        override fun setAudioTrack(trackKey: String?) = Unit
        override fun currentCastSource(): CastSource? = null
        override fun refreshCastUrl(token: String, videoId: String?, itag: Int?): String? = null
        override fun attachCastPlayer(delegate: Player?): Boolean = false
        override fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) = Unit
        override fun cancelSponsorSkip() = Unit
        override fun skipSponsorChipSegment() = Unit
    }
}
