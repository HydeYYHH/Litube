@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
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

    @Test fun nativeEntryLoadsOnceAndRestoresThePreservedEngine() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId, 42_000)
        engine.pause()
        viewModel.enterMiniPlayer()
        viewModel.openVideo(watchId)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.isPlaying)
        assertFalse(viewModel.uiState.value.mini)
    }

    @Test fun dismissedFailureStaysClosedUntilAnotherFailureAndCanBeReopened() {
        bindViewModel()
        viewModel.openVideo(watchId)
        val report = com.hhst.youtubelite.diagnostics.PlaybackFailureReport("first", watchId, "Failed", "diagnostics")
        engine.fail(report)
        assertTrue(viewModel.uiState.value.failureDialogVisible)
        viewModel.dismissPlaybackFailure()
        engine.fail(report)
        assertFalse(viewModel.uiState.value.failureDialogVisible)
        assertEquals(report, viewModel.uiState.value.failureReport)
        val loads = engine.playCalls.size
        viewModel.openShort(watchId)
        assertEquals(loads, engine.playCalls.size)
        assertFalse(viewModel.uiState.value.failureDialogVisible)
        viewModel.onErrorDetails()
        assertTrue(viewModel.uiState.value.failureDialogVisible)
        viewModel.dismissPlaybackFailure()
        engine.fail(report.copy(id = "second"))
        assertTrue(viewModel.uiState.value.failureDialogVisible)
        engine.settle(watchId)
        assertNull(viewModel.uiState.value.failureReport)
        assertFalse(viewModel.uiState.value.failureDialogVisible)
    }

    @Test fun nativeAutoplayUsesTheServiceOncePerPlayback() {
        var calls = 0
        bindViewModel { calls++; "bbbbbbbbbbb" }
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        engine.finish()
        assertEquals(1, calls)
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
    }

    @Test fun pausedOrReplacedPlaybackRejectsDelayedNativeAutoplay() {
        val answer = kotlinx.coroutines.CompletableDeferred<String?>()
        bindViewModel { answer.await() }
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        engine.finish()
        PlaybackCommandRouter.handler!!.onPause()
        answer.complete("bbbbbbbbbbb")
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun explicitPlaylistPreservesItsHeadAndOverridesAnOldQueue() {
        bindViewModel()
        queue.setEnabled(true)
        queue.add(QueueItem("ccccccccccc", title = "Old queue"))
        val items = listOf(watchId, "bbbbbbbbbbb", "ddddddddddd").map { QueueItem(it, title = it) }
        viewModel.startPlaylist(items, startId = "bbbbbbbbbbb")
        engine.settle("bbbbbbbbbbb")
        viewModel.skipToPrevious()
        assertEquals(watchUrl, engine.playCalls.last())
        assertEquals(1, queue.state.value.items.size)
        engine.settle(watchId)
        viewModel.skipToNext()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
    }

    @Test fun autoplayPreferenceStopsServiceRequestsWithoutBreakingManualQueue() {
        var calls = 0
        bindViewModel { calls++; "bbbbbbbbbbb" }
        preferences.setEnabled(PreferenceKeys.ENABLE_AUTOPLAY, false)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        // One prefetch resolves the Next button's target; ending the video
        // must not issue another autoplay request with the pref off.
        val prefetched = calls
        engine.finish()
        assertEquals(prefetched, calls)
        assertEquals(listOf(watchUrl), engine.playCalls)
        viewModel.onQueueAdd(QueueItem("bbbbbbbbbbb", title = "Next"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.skipToNext()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
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

    // -- Playback lifecycle --

    private val watchId = "watchAAAAA1"
    private val shortsId = "shortszzzz1"
    private val watchUrl = "https://www.youtube.com/watch?v=$watchId"
    private val shortsUrl = "https://m.youtube.com/shorts/$shortsId"

    private fun bindViewModel(nextVideo: (suspend (String) -> String?)? = null) {
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
            nextVideo = nextVideo,
        )
    }

    @Test fun leavingForShortsPausesAndReopeningTheVideoPreservesPosition() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId, 42_000L)
        viewModel.onShortsOpened()
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(engine.snapshot.value.isPlaying)
        viewModel.onShortsClosed()
        viewModel.openVideo(watchId)
        assertTrue(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.mini)
        assertFalse(engine.snapshot.value.isPlaying)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun queueTakesPriorityOverServiceAutoplayAndPreviousAtTheHeadDoesNotChangeVideo() {
        var requests = 0
        bindViewModel { requests++; "ccccccccccc" }
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "First"))
        queue.add(QueueItem("bbbbbbbbbbb", VideoId.watchUrl("bbbbbbbbbbb"), "Second"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.skipToPrevious()
        assertEquals(listOf(watchUrl), engine.playCalls)
        engine.finish()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
        assertEquals(0, requests)
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
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.enterMiniPlayer()
        viewModel.onMiniClose()
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun reopeningPreservesPausedPlaybackAndPosition() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId, 42_000)
        engine.pause()
        viewModel.openVideo(watchId)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.isPlaying)
        engine.settle(watchId, 120_000)
        engine.finish()
        viewModel.openVideo(watchId)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(120_000L, engine.snapshot.value.positionMs)
        assertTrue(engine.snapshot.value.ended)
    }

    @Test fun shortReentryPreservesManualPauseAndAvoidsReload() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId, 9_000)
        engine.pause()
        viewModel.onShortsStopped()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(9_000L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.isPlaying)
    }

    @Test fun shortsBackgroundPlaybackHonorsThePreference() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        viewModel.onShortsStopped()
        assertTrue(engine.snapshot.value.isPlaying)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, false)
        viewModel.onShortsStopped()
        assertFalse(engine.snapshot.value.isPlaying)
    }

    @Test fun leavingShortsNeverCollapsesIntoTheMiniPlayer() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        preferences.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, true)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        // ContentActivity can resume before ShortsActivity stops: the browse
        // hook must end the shorts presentation, not park it as a mini player.
        viewModel.onBrowseOpened()
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.mini)
        assertTrue(engine.snapshot.value.isPlaying)
    }

    @Test fun shortsStopClearsFloatingStateAndBrowseKeepsItClosed() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        viewModel.onShortsStopped()
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.mini)
        assertTrue(engine.snapshot.value.isPlaying)
        viewModel.onBrowseOpened()
        assertFalse(viewModel.uiState.value.mini)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, false)
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        viewModel.onShortsStopped()
        assertFalse(viewModel.uiState.value.mini)
        assertFalse(engine.snapshot.value.isPlaying)
    }

    @Test fun endingAShortNeverRequestsWatchAutoplay() {
        var calls = 0
        bindViewModel { calls++; "bbbbbbbbbbb" }
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        engine.finish()
        assertEquals(0, calls)
    }

    @Test fun manualNextUsesRelatedVideosEvenWhenAutoplayIsOff() {
        var calls = 0
        bindViewModel { calls++; "bbbbbbbbbbb" }
        preferences.setEnabled(PreferenceKeys.ENABLE_AUTOPLAY, false)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        assertTrue(viewModel.uiState.value.hasNext)
        viewModel.skipToNext()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
        assertEquals(1, calls)
    }

    @Test fun relatedMissKeepsTheCurrentPlaybackAndDisablesNext() {
        var calls = 0
        bindViewModel { calls++; null }
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        // The per-video prefetch already resolved the miss: the button must
        // show its honest disabled state without the user probing it.
        assertFalse(viewModel.uiState.value.hasNext)
        viewModel.skipToNext()
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(1, calls)
        assertFalse(viewModel.uiState.value.hasNext)
    }

    @Test fun aStaleQueueNeverSeizesAPlainWatch() {
        bindViewModel { "ccccccccccc" }
        queue.setEnabled(true)
        queue.add(QueueItem("ddddddddddd", title = "Leftover"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        // Still only the requested video: the leftover queue never hijacked it.
        assertEquals(listOf(watchUrl), engine.playCalls)
        // Ending the video must not drop into the stale queue either.
        engine.finish()
        assertEquals(listOf(watchUrl, VideoId.watchUrl("ccccccccccc")), engine.playCalls)
    }

    @Test fun previousRestartsTheCurrentVideoPastTheThreshold() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId, 42_000)
        assertTrue(viewModel.uiState.value.hasPrevious)
        viewModel.skipToPrevious()
        assertEquals(0L, engine.snapshot.value.positionMs)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun previousWalksBackThisSessionsTrail() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.openVideo("bbbbbbbbbbb")
        engine.settle("bbbbbbbbbbb")
        viewModel.openVideo("ccccccccccc")
        engine.settle("ccccccccccc")
        viewModel.skipToPrevious()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
        engine.settle("bbbbbbbbbbb")
        viewModel.skipToPrevious()
        assertEquals(watchUrl, engine.playCalls.last())
        engine.settle(watchId)
        assertFalse(viewModel.uiState.value.hasPrevious)
    }

    @Test fun loopOneDoesNotBlockManualQueueNext() {
        bindViewModel()
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "First"))
        queue.add(QueueItem("bbbbbbbbbbb", VideoId.watchUrl("bbbbbbbbbbb"), "Second"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        engine.setLoopMode(LoopMode.LOOP_ONE)
        viewModel.skipToNext()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
    }

    @Test fun shortsExposeNoWatchNavigation() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        assertFalse(viewModel.uiState.value.hasNext)
        assertFalse(viewModel.uiState.value.hasPrevious)
        viewModel.skipToNext()
        viewModel.skipToPrevious()
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun endingAnInactiveHoldPreservesSpeedAndStoppingRestoresAnActiveHoldOnce() {
        bindViewModel()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        engine.setSpeed(1.5f)
        viewModel.onSpeedHoldEnd()
        assertEquals(1.5f, engine.snapshot.value.speed)
        viewModel.onSpeedHoldStart()
        assertEquals(2f, engine.snapshot.value.speed)
        viewModel.onShortsStopped()
        assertEquals(1.5f, engine.snapshot.value.speed)
        viewModel.onSpeedHoldEnd()
        assertEquals(1.5f, engine.snapshot.value.speed)
    }

    @Test fun pipDismissalStopsLocalPlaybackWithEitherBackgroundPreference() {
        bindViewModel()
        for (enabled in listOf(false, true)) {
            preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, enabled)
            viewModel.openVideo(watchId)
            engine.settle(watchId)
            viewModel.onPipDismissed()
            assertFalse(viewModel.uiState.value.visible)
            assertFalse(engine.snapshot.value.isPlaying)
        }
        assertEquals(2, engine.stopCalls)
    }

    @Test fun pipDismissalKeepsTheConnectedCastAndOnlyPausesLocalPlayback() {
        bindViewModel()
        viewModel.openVideo(watchId)
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

        fun fail(report: com.hhst.youtubelite.diagnostics.PlaybackFailureReport) {
            _snapshot.value = _snapshot.value.copy(error = report.summary, failureReport = report,
                positionMs = _snapshot.value.positionMs + 1, prepared = false, isPlaying = false)
        }

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
                failureReport = null,
                isPlaying = true,
                userWantsPlay = true,
                ended = false,
            )
        }

        override var onEnded: (() -> Unit)? = null
        override var onCastError: (() -> Unit)? = null
        override var onCastManifestReload: (() -> Unit)? = null
        override var onWatchContext: ((videoId: String, tracking: com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking?, sessionGeneration: Long) -> Unit)? = null

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
        override fun play() { _snapshot.value = _snapshot.value.copy(isPlaying = true, userWantsPlay = true) }
        override fun pause() { _snapshot.value = _snapshot.value.copy(isPlaying = false, userWantsPlay = false) }
        override fun cancelPending(pausePlayback: Boolean) {
            if (pausePlayback) _snapshot.value = _snapshot.value.copy(isPlaying = false)
        }

        override fun pauseLocal() {
            _snapshot.value = _snapshot.value.copy(isPlaying = false)
        }

        override fun markReceiverIdentity(videoId: String, title: String, author: String?) = Unit
        override fun seekTo(positionMs: Long) { _snapshot.value = _snapshot.value.copy(positionMs = positionMs, ended = false) }
        override fun seekRelative(offsetMs: Long) = Unit
        override fun setSpeed(speed: Float) { _snapshot.value = _snapshot.value.copy(speed = speed) }
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
