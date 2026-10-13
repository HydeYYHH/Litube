@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.browser.WatchPage
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.CastUiState
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.engine.CastSource
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
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
    private lateinit var queue: QueueRepository
    private lateinit var preferences: ExtensionManager
    private lateinit var cast: CastController
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
        cast = CastController(ContextWrapper(null), OkHttpClient())
        queue = QueueRepository(cache)
        preferences = ExtensionManager(MemoryPrefStore())
        viewModel = PlayerViewModel(
            engine,
            queue,
            preferences,
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

    @Test fun documentReloadPreservesPausedPlaybackAndPosition() {
        bindViewModel()
        viewModel.playVideo(watchUrl, PageOrigin(1, 1))
        engine.settle(watchId, 42_000)
        engine.pause()
        viewModel.playVideo(watchUrl, PageOrigin(1, 2))
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.isPlaying)
        engine.settle(watchId, 120_000)
        engine.finish()
        viewModel.playVideo(watchUrl, PageOrigin(1, 3))
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(120_000L, engine.snapshot.value.positionMs)
        assertTrue(engine.snapshot.value.ended)
    }

    @Test fun pipDismissalStopsLocalPlaybackWithEitherBackgroundPreference() {
        bindViewModel()
        for (enabled in listOf(false, true)) {
            preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, enabled)
            viewModel.playVideo(watchUrl)
            engine.settle(watchId)
            viewModel.onPipDismissed()
            assertFalse(viewModel.uiState.value.visible)
            assertFalse(engine.snapshot.value.isPlaying)
        }
        assertEquals(2, engine.stopCalls)
    }

    @Test fun ordinaryAutoplayAdvancesOnceOnlyWithExplicitEnabledTarget() {
        bindViewModel()
        val page = FakeWatchPage()
        viewModel.watchPage = page
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        engine.finish()
        engine.onEnded?.invoke()
        assertEquals(1, page.scripts.size)
        page.callbacks.removeAt(0)(autoplayResult())
        assertEquals(2, page.scripts.size)
        assertTrue(page.scripts.last().contains("__watchAutoplayAdvance"))
        val request = Regex(",([0-9]+)\\)").find(page.scripts.last())!!.groupValues[1].toLong()
        assertTrue(viewModel.canAutoplayAdvance(watchId, request, page.pageOrigin))
        assertFalse(viewModel.canAutoplayAdvance(watchId, request, PageOrigin(2, 1)))
        viewModel.onSeek(0)
        assertFalse(viewModel.canAutoplayAdvance(watchId, request, page.pageOrigin))
    }

    @Test fun notificationPlayAndPauseDiscardPendingAutoplayEvenBeforeTheSnapshotChanges() {
        bindViewModel()
        val page = FakeWatchPage()
        viewModel.watchPage = page
        for (command in listOf<() -> Unit>(
            { PlaybackCommandRouter.handler!!.onPlay() },
            { PlaybackCommandRouter.handler!!.onPause() },
        )) {
            page.scripts.clear(); page.callbacks.clear()
            viewModel.playVideo(watchUrl)
            engine.settle(watchId)
            engine.finish()
            val callback = page.callbacks.removeAt(0)
            command()
            assertTrue(engine.snapshot.value.ended)
            callback(autoplayResult())
            assertEquals(1, page.scripts.size)
        }
    }

    @Test fun pipDismissalKeepsTheConnectedCastAndOnlyPausesLocalPlayback() {
        bindViewModel()
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        @Suppress("UNCHECKED_CAST")
        val state = cast.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(cast) as MutableStateFlow<CastUiState>
        state.value = state.value.copy(linkConnected = true, linkUrl = "http://127.0.0.1/player")
        viewModel.onPipDismissed()
        assertTrue(cast.state.value.linkConnected)
        assertEquals("http://127.0.0.1/player", cast.state.value.linkUrl)
        assertEquals(0, engine.stopCalls)
        assertFalse(engine.snapshot.value.isPlaying)
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test fun autoplayOffUnknownAndPauseAtEndNeverAdvance() {
        bindViewModel()
        val page = FakeWatchPage()
        viewModel.watchPage = page
        for (result in listOf(autoplayResult("false"), autoplayResult("null"), "null")) {
            page.scripts.clear()
            viewModel.playVideo(watchUrl)
            engine.settle(watchId)
            engine.finish()
            assertEquals(1, page.scripts.size)
            page.callbacks.removeAt(0)(result)
            assertEquals(1, page.scripts.size)
        }
        page.scripts.clear()
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        engine.setLoopMode(LoopMode.PAUSE_AT_END)
        engine.finish()
        assertTrue(page.scripts.isEmpty())
    }

    @Test fun delayedAutoplayIsDiscardedAfterReplayPageReplacementOrClose() {
        bindViewModel()
        val page = FakeWatchPage()
        viewModel.watchPage = page
        for (cancel in listOf<() -> Unit>(
            { engine.seekTo(0); engine.finish() },
            { page.pageOrigin = PageOrigin(1, page.pageOrigin.documentGeneration + 1) },
            { viewModel.onMiniClose() },
        )) {
            page.scripts.clear(); page.callbacks.clear()
            viewModel.playVideo(watchUrl)
            engine.settle(watchId)
            engine.finish()
            val oldCallback = page.callbacks.removeAt(0)
            cancel()
            val queries = page.scripts.size
            oldCallback(autoplayResult())
            assertEquals(queries, page.scripts.size)
        }
    }

    @Test fun queueAndPlaylistRetainPriorityOverOrdinaryAutoplay() {
        bindViewModel()
        val page = FakeWatchPage()
        viewModel.watchPage = page
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "First"))
        queue.add(QueueItem("bbbbbbbbbbb", VideoId.watchUrl("bbbbbbbbbbb"), "Second"))
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        engine.finish()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
        assertTrue(page.scripts.isEmpty())
        queue.clear(); queue.setEnabled(false)
        viewModel.playVideo(watchUrl)
        engine.settle(watchId)
        viewModel.onPageHasPlaylist(true)
        engine.finish()
        assertEquals(1, page.scripts.size)
        assertTrue(page.scripts.single().contains("__playlistNav"))
    }

    private fun autoplayResult(enabled: String = "true") =
        """{"videoId":"$watchId","enabled":$enabled,"url":"https://m.youtube.com/watch?v=bbbbbbbbbbb"}"""

    private class FakeWatchPage : WatchPage {
        var pageOrigin = PageOrigin(1, 1)
        val scripts = mutableListOf<String>()
        val callbacks = mutableListOf<(String?) -> Unit>()
        override fun origin() = pageOrigin
        override fun evaluate(js: String, onResult: (String?) -> Unit) { scripts += js; callbacks += onResult }
        override fun canGoBack() = false
        override fun goBack() = Unit
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
        var stopCalls = 0

        fun finish() {
            _snapshot.value = _snapshot.value.copy(ended = true, isPlaying = false)
            onEnded?.invoke()
        }

        /** Publishes a settled (prepared) and playing engine state for [videoId]. */
        fun settle(videoId: String, positionMs: Long = 0L) {
            _snapshot.value = _snapshot.value.copy(
                videoId = videoId,
                positionMs = positionMs,
                prepared = true,
                error = null,
                isPlaying = true,
                ended = false,
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
        override fun stop() { stopCalls++; _snapshot.value = PlaybackSnapshot() }
        override fun setVideoSurface(surface: SurfaceView?) = Unit
        override fun playOrPause() = Unit
        override fun play() = Unit
        override fun pause() { _snapshot.value = _snapshot.value.copy(isPlaying = false) }
        override fun cancelPending(pausePlayback: Boolean) {
            _snapshot.value = _snapshot.value.copy(isPlaying = false, prepared = false)
        }

        override fun pauseLocal() {
            _snapshot.value = _snapshot.value.copy(isPlaying = false)
        }

        override fun markReceiverIdentity(videoId: String, title: String, author: String?) = Unit
        override fun seekTo(positionMs: Long) { _snapshot.value = _snapshot.value.copy(positionMs = positionMs, ended = false) }
        override fun seekRelative(offsetMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setSpeedHold(active: Boolean) = Unit
        override fun setLoopMode(mode: LoopMode) { _snapshot.value = _snapshot.value.copy(loopMode = mode) }
        override fun setQuality(label: String?) = Unit
        override fun setSubtitle(trackKey: String?): Boolean = true
        override fun setAudioTrack(trackKey: String?) = Unit
        override fun currentCastSource(): CastSource? = null
        override fun refreshCastUrl(token: String, videoId: String?, itag: Int?): String? = null
        override fun attachCastPlayer(delegate: Player?): Boolean = false
        override fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) = Unit
        override fun cancelSponsorSkip() = Unit
        override fun skipSponsorNow() = Unit
        override fun skipSponsorChipSegment() = Unit
    }
}
