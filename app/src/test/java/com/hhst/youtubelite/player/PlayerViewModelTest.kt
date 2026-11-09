@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.CastUiState
import com.hhst.youtubelite.cast.DlnaController
import com.hhst.youtubelite.cast.DlnaEngine
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MemoryAuthStore
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
import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    @Test fun swipePausesNeverBecomeAnExplicitShortPause() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        viewModel.holdShortsForSwipe()
        assertFalse(viewModel.uiState.value.shortPausedByUser)
        viewModel.resumeShortsAfterSwipe(watchId)
        assertFalse(viewModel.uiState.value.shortPausedByUser)
        viewModel.onPlayPause()
        assertTrue(viewModel.uiState.value.shortPausedByUser)
        viewModel.holdShortsForSwipe()
        viewModel.resumeShortsAfterSwipe(watchId)
        assertTrue(viewModel.uiState.value.shortPausedByUser)
        viewModel.onPlayPause()
        assertFalse(viewModel.uiState.value.shortPausedByUser)
    }

    @Test fun settlingOnANewShortDoesNotResumeThePreviousSwipe() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        viewModel.holdShortsForSwipe()
        viewModel.openShort(shortsId)
        viewModel.resumeShortsAfterSwipe(shortsId)
        assertEquals(0, engine.resumeCalls)
        assertEquals(shortsId, engine.snapshot.value.videoId)
        assertFalse(engine.snapshot.value.prepared)
        viewModel.holdShortsForSwipe()
        viewModel.resumeShortsAfterSwipe(shortsId)
        assertEquals(1, engine.resumeCalls)
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
        // One prefetch resolves the Next target when opening; ending must not re-request.
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

    private val watchId = "watchAAAAA1"
    private val shortsId = "shortszzzz1"
    private val watchUrl = "https://www.youtube.com/watch?v=$watchId"
    private val shortsUrl = "https://m.youtube.com/shorts/$shortsId"

    @Test fun attachingToAnAlreadyPreparedEngineInitializesNavigationBeforeCollecting() {
        bindViewModel(prepared = true)
        assertEquals(watchId, viewModel.uiState.value.videoId)
        assertTrue(viewModel.uiState.value.prepared)
        assertEquals(MediaKind.NONE, viewModel.currentMediaKind)
        assertFalse(viewModel.uiState.value.hasPrevious)
        viewModel.openVideo(watchId)
        assertTrue(engine.playCalls.isEmpty())
    }

    private fun bindViewModel(
        prepared: Boolean = false,
        identity: Flow<Long>? = null,
        ownerProvider: (() -> QueueOwner)? = null,
        dlna: DlnaController? = null,
        nextVideo: (suspend (String) -> String?)? = null,
    ) {
        engine = FakeEngine()
        if (prepared) engine.settle(watchId)
        val cache = MemJsonCache()
        cast = CastController(ContextWrapper(null), OkHttpClient())
        queue = if (ownerProvider != null) QueueRepository(cache, ownerProvider) else QueueRepository(cache)
        preferences = ExtensionManager(MemoryPrefStore())
        viewModel = if (identity == null) {
            PlayerViewModel(engine, queue, preferences, cache, cast, dlna = dlna, nextVideo = nextVideo)
        } else {
            PlayerViewModel(engine, queue, preferences, cache, cast, dlna = dlna, nextVideo = nextVideo, identityChanges = identity)
        }
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

    @Test fun identitySwitchActivatesTheNewQueueAndDropsTheOldNavigationContext() {
        val identity = MutableStateFlow(0L)
        var owner = QueueOwner.DEVICE
        bindViewModel(ownerProvider = { owner }, identity = identity)
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "Device entry"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.startPlaylist(listOf(QueueItem("ccccccccccc", VideoId.watchUrl("ccccccccccc"), "Playlist entry")))
        engine.settle("ccccccccccc")
        assertTrue(viewModel.uiState.value.hasPrevious)

        owner = QueueOwner.account("A")
        identity.value += 1

        assertEquals(QueueOwner.account("A"), viewModel.uiState.value.queueOwner)
        assertTrue(viewModel.uiState.value.queueItems.isEmpty())
        viewModel.skipToNext()
        viewModel.skipToPrevious()
        assertEquals(VideoId.watchUrl("ccccccccccc"), engine.playCalls.last())
        assertFalse(viewModel.uiState.value.hasPrevious)
    }

    @Test fun identitySwitchKeepsTheCurrentVideoAndSurfaceRunning() {
        val identity = MutableStateFlow(0L)
        var owner = QueueOwner.DEVICE
        bindViewModel(ownerProvider = { owner }, identity = identity)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        owner = QueueOwner.account("A")
        identity.value += 1
        assertTrue(viewModel.uiState.value.visible)
        assertEquals(watchId, viewModel.uiState.value.videoId)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun recoveringCredentialsActivatesTheAccountQueueWithoutRestartingPlayback() {
        val originalFactory = MediaSession.tokenFactory
        try {
            MediaSession.initialize(MemoryAuthStore())
            val http = object : HttpClient {
                override fun execute(request: HttpRequest) =
                    HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
            }
            MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
            MediaSession.completeSignIn(SignInClient("id", "secret", "device"), AccessToken("", "refresh", 0),
                listOf(Account("owner", "Owner", "", "", true, false, null)))
            val generation = MediaSession.snapshot().generation
            bindViewModel(ownerProvider = {
                val context = MediaSession.snapshot()
                QueueOwner.from(context.signedIn, context.accountId, context.onBehalfOfUser)
            })
            queue.setEnabled(true)
            queue.add(QueueItem(watchId, watchUrl, "Device entry"))
            viewModel.openVideo(watchId)
            engine.settle(watchId)
            viewModel.startPlaylist(listOf(QueueItem("ccccccccccc", VideoId.watchUrl("ccccccccccc"), "Device playlist")))
            engine.settle("ccccccccccc")
            assertTrue(viewModel.uiState.value.hasPrevious)
            val plays = engine.playCalls.toList()

            MediaSession.freshContext()

            assertEquals(generation, MediaSession.snapshot().generation)
            assertEquals(QueueOwner.account("owner"), viewModel.uiState.value.queueOwner)
            assertTrue(viewModel.uiState.value.queueItems.isEmpty())
            assertFalse(viewModel.uiState.value.hasPrevious)
            assertEquals("ccccccccccc", viewModel.uiState.value.videoId)
            assertEquals(plays, engine.playCalls)
            viewModel.skipToPrevious()
            assertEquals(plays, engine.playCalls)
        } finally {
            if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
            MediaSession.tokenFactory = originalFactory
            MediaSession.initialize(MemoryAuthStore())
        }
    }

    @Test fun aRepeatedGenerationWithTheSameQueueOwnerKeepsTheBrowsingContext() {
        val identity = MutableSharedFlow<Long>(replay = 1).apply { tryEmit(1L) }
        bindViewModel(identity = identity)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.openVideo("ccccccccccc")
        engine.settle("ccccccccccc")
        assertTrue(viewModel.uiState.value.hasPrevious)

        assertTrue(identity.tryEmit(1L))

        assertTrue(viewModel.uiState.value.hasPrevious)
        viewModel.skipToPrevious()
        assertEquals(watchUrl, engine.playCalls.last())
    }

    @Test fun aDragCommitFromThePreviousOwnerNeverReachesTheNewQueue() {
        val identity = MutableStateFlow(0L)
        var owner = QueueOwner.DEVICE
        bindViewModel(ownerProvider = { owner }, identity = identity)
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "First"))
        queue.add(QueueItem("bbbbbbbbbbb", VideoId.watchUrl("bbbbbbbbbbb"), "Second"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        val stale = viewModel.uiState.value.queueOwner

        owner = QueueOwner.account("A")
        identity.value += 1
        queue.add(QueueItem("ccccccccccc", VideoId.watchUrl("ccccccccccc"), "A entry"))
        queue.add(QueueItem("ddddddddddd", VideoId.watchUrl("ddddddddddd"), "A second"))

        viewModel.onQueueMove(0, 1, stale)
        assertEquals(listOf("ccccccccccc", "ddddddddddd"), viewModel.uiState.value.queueItems.map { it.videoId })
        viewModel.onQueueMove(0, 1, QueueOwner.account("A"))
        assertEquals(listOf("ddddddddddd", "ccccccccccc"), viewModel.uiState.value.queueItems.map { it.videoId })
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
        assertEquals(listOf(watchUrl + "&t=0"), engine.playCalls)
        assertEquals(9_000L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.isPlaying)
    }

    @Test fun shortsAlwaysPauseOutsideTheirHost() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        viewModel.onShortsStopped()
        assertFalse(engine.snapshot.value.isPlaying)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, false)
        viewModel.onShortsStopped()
        assertFalse(engine.snapshot.value.isPlaying)
    }

    @Test fun resumedShortContinuesWithoutReloadingItsMedia() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId, 9_000)
        viewModel.onShortsStopped()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        assertEquals(listOf(watchUrl + "&t=0"), engine.playCalls)
        assertEquals(9_000L, engine.snapshot.value.positionMs)
        assertTrue(engine.snapshot.value.isPlaying)
    }

    @Test fun aDepartingShortsHostCannotPauseTheNewWatchSession() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(shortsId)
        engine.settle(shortsId)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.onShortsStopped()
        viewModel.onShortsClosed()
        assertTrue(engine.snapshot.value.isPlaying)
        assertEquals(watchId, engine.snapshot.value.videoId)
        assertTrue(viewModel.uiState.value.visible)
    }

    @Test fun leavingShortsNeverCollapsesIntoTheMiniPlayer() {
        bindViewModel()
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        preferences.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, true)
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        // ContentActivity may resume before ShortsActivity stops: the browse hook
        // must end the shorts presentation, not park it as a mini player.
        viewModel.onBrowseOpened()
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.mini)
        assertFalse(engine.snapshot.value.isPlaying)
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
        assertFalse(engine.snapshot.value.isPlaying)
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

    @Test fun centerDownwardSwipeEntersTheMiniPlayerWhenEnabled() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        // Defaults to on, so it is set off first to cover the disabled branch.
        preferences.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, false)
        viewModel.onMiniSwipeRequested()
        assertFalse(viewModel.uiState.value.mini)
        preferences.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, true)
        viewModel.onMiniSwipeRequested()
        assertTrue(viewModel.uiState.value.mini)
        assertTrue(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.fullscreen)
    }

    @Test fun centerDownwardSwipeIgnoresShortsAndFullscreenSurfaces() {
        bindViewModel()
        preferences.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, true)
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.settle(watchId)
        viewModel.onMiniSwipeRequested()
        assertFalse(viewModel.uiState.value.mini)
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.setFullscreenManual(true)
        viewModel.onMiniSwipeRequested()
        assertFalse(viewModel.uiState.value.mini)
        assertTrue(viewModel.uiState.value.fullscreen)
    }

    @Test fun contentPullPreservesOrientationWhileTheButtonKeepsItsNormalBehavior() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.setFullscreenManual(true)
        assertTrue(viewModel.uiState.value.fullscreenKeepsOrientation)
        viewModel.setFullscreenManual(false)
        assertFalse(viewModel.uiState.value.fullscreen)
        assertTrue(viewModel.uiState.value.fullscreenKeepsOrientation)
        viewModel.onFullscreenToggle()
        assertTrue(viewModel.uiState.value.fullscreen)
        assertFalse(viewModel.uiState.value.fullscreenKeepsOrientation)
    }

    @Test fun swipeExitAfterButtonEntryKeepsOrientationUntilTheNextButtonAction() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.onFullscreenToggle()
        assertFalse(viewModel.uiState.value.fullscreenKeepsOrientation)
        viewModel.onFullscreenSwipeExit()
        assertFalse(viewModel.uiState.value.fullscreen)
        assertTrue(viewModel.uiState.value.fullscreenKeepsOrientation)
        viewModel.onFullscreenToggle()
        viewModel.onFullscreenToggle()
        assertFalse(viewModel.uiState.value.fullscreen)
        assertFalse(viewModel.uiState.value.fullscreenKeepsOrientation)
    }

    @Test fun manualFullscreenExitWaitsForPortraitBeforeAutoEnteringAgain() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.onPhysicalOrientation(90, systemAutoRotate = true, inPip = false)
        assertTrue(viewModel.uiState.value.fullscreen)
        viewModel.onFullscreenToggle()
        viewModel.onPhysicalOrientation(270, systemAutoRotate = true, inPip = false)
        assertFalse(viewModel.uiState.value.fullscreen)
        viewModel.onPhysicalOrientation(0, systemAutoRotate = true, inPip = false)
        viewModel.onPhysicalOrientation(90, systemAutoRotate = true, inPip = false)
        assertTrue(viewModel.uiState.value.fullscreen)
        viewModel.onPhysicalOrientation(0, systemAutoRotate = true, inPip = false)
        assertFalse(viewModel.uiState.value.fullscreen)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun downwardSwipeRestoresThePreFullscreenPolicyWhileUpwardSwipeKeepsDirection() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        for (pullEntry in listOf(false, true)) {
            if (pullEntry) viewModel.setFullscreenManual(true) else viewModel.onFullscreenToggle()
            viewModel.onFullscreenSwipeRestore()
            assertFalse(viewModel.uiState.value.fullscreen)
            assertFalse(viewModel.uiState.value.fullscreenKeepsOrientation)
            assertTrue(viewModel.uiState.value.fullscreenRestoresEntryOrientation)
            viewModel.onFullscreenSwipeRestore()
            assertFalse("A duplicate downward exit must never reenter fullscreen", viewModel.uiState.value.fullscreen)
            viewModel.onFullscreenToggle()
            assertFalse(viewModel.uiState.value.fullscreenRestoresEntryOrientation)
            viewModel.onFullscreenSwipeExit()
            assertTrue(viewModel.uiState.value.fullscreenKeepsOrientation)
            assertFalse(viewModel.uiState.value.fullscreenRestoresEntryOrientation)
        }
    }

    @Test fun openingWatchAfterAHiddenLandscapeEventAppliesAutoFullscreen() {
        bindViewModel()
        viewModel.onPhysicalOrientation(90, systemAutoRotate = true, inPip = false)
        assertFalse(viewModel.uiState.value.fullscreen)
        viewModel.openVideo(watchId)
        assertTrue(viewModel.uiState.value.fullscreen)
        viewModel.enterMiniPlayer()
        viewModel.onPhysicalOrientation(0, systemAutoRotate = true, inPip = false)
        assertTrue(viewModel.uiState.value.mini)
        assertFalse(viewModel.uiState.value.fullscreen)
        assertEquals(listOf(watchUrl), engine.playCalls)
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

    @Test fun endingAShortNeverConsumesAnActiveWatchQueue() {
        bindViewModel()
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, title = "Current"))
        queue.add(QueueItem("bbbbbbbbbbb", title = "Next watch video"))
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.onShortsOpened()
        viewModel.openShort(watchId)
        engine.finish()
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertEquals(MediaKind.SHORTS, viewModel.currentMediaKind)
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
        assertEquals(listOf(watchUrl), engine.playCalls)
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

    @Test fun playbackModeSelectionUsesTheRequestedValue() {
        bindViewModel()
        viewModel.onLoopMode(LoopMode.PAUSE_AT_END)
        assertEquals(LoopMode.PAUSE_AT_END, engine.snapshot.value.loopMode)
        viewModel.onLoopMode(LoopMode.PAUSE_AT_END)
        assertEquals(LoopMode.PAUSE_AT_END, engine.snapshot.value.loopMode)
        viewModel.onLoopMode(LoopMode.QUEUE_NEXT)
        assertEquals(LoopMode.QUEUE_NEXT, engine.snapshot.value.loopMode)
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
        assertEquals(listOf(watchUrl + "&t=0"), engine.playCalls)
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

    @Test fun explicitStopClearsPlaybackAndPresentationWithEitherBackgroundPreference() {
        bindViewModel()
        for (enabled in listOf(false, true)) {
            preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, enabled)
            viewModel.openVideo(watchId)
            engine.settle(watchId)
            viewModel.setFullscreen(true)
            viewModel.setLocked(true)
            PlaybackCommandRouter.handler!!.onStop()
            assertNull(engine.snapshot.value.videoId)
            assertFalse(engine.snapshot.value.prepared)
            assertFalse(engine.snapshot.value.userWantsPlay)
            assertFalse(engine.snapshot.value.isPlaying)
            assertNull(viewModel.uiState.value.videoId)
            assertFalse(viewModel.uiState.value.visible)
            assertFalse(viewModel.uiState.value.fullscreen)
            assertFalse(viewModel.uiState.value.locked)
            assertFalse(viewModel.uiState.value.mini)
        }
        assertEquals(2, engine.stopCalls)
        assertEquals(listOf(true, true), engine.cancelPendingCalls)
    }

    @Test fun explicitStopEndsAudioOnlyBackgroundAndReopeningLoadsFreshMedia() {
        bindViewModel()
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.playAudioOnlyInBackground()
        assertTrue(engine.audioOnlyEnabled)
        viewModel.stopPlayback()
        assertFalse(viewModel.audioOnlyBackground)
        assertFalse(engine.audioOnlyEnabled)
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(1, engine.stopCalls)
        viewModel.openVideo(watchId)
        assertEquals(listOf(watchUrl, watchUrl), engine.playCalls)
        assertTrue(engine.outputVisible)
        assertFalse(engine.snapshot.value.prepared)
    }

    @Test fun explicitStopClosesTheCastLinkAndRejectsItsLateDisconnectEcho() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        castState().value = CastUiState(linkConnected = true, linkUrl = "http://127.0.0.1/player")
        viewModel.stopPlayback()
        cast.onSessionEnded!!.invoke()
        cast.onSessionFailed!!.invoke()
        assertNull(cast.state.value.linkUrl)
        assertFalse(cast.state.value.linkConnected)
        assertEquals(0, engine.resumeCalls)
        assertEquals(1, engine.stopCalls)
        assertNull(engine.snapshot.value.videoId)
    }

    @Test fun explicitStopEndsTheChromecastSessionWithoutResumingLocalPlayback() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        installChromecastSession()
        viewModel.stopPlayback()
        assertFalse(cast.state.value.chromecastSession)
        assertFalse(viewModel.uiState.value.casting)
        assertEquals(0, engine.resumeCalls)
        assertEquals(1, engine.stopCalls)
        assertNull(engine.snapshot.value.videoId)
    }

    @Test fun explicitStopRejectsACastSourceThatFinishesAfterPendingPlaybackWasCancelled() {
        bindViewModel()
        viewModel.openVideo(watchId)
        val source = CompletableDeferred<CastSource?>()
        engine.castSourceLoader = { source.await() }
        installChromecastSession()
        cast.onSessionStarted!!.invoke()
        viewModel.stopPlayback()
        source.complete(CastSource(watchId, "Pending", 60, null, null))
        assertEquals(listOf(true), engine.cancelPendingCalls)
        assertEquals(0, engine.attachCastCalls)
        assertEquals(0, engine.resumeCalls)
        assertEquals(1, engine.stopCalls)
        assertNull(engine.snapshot.value.videoId)
        assertFalse(viewModel.uiState.value.visible)
        assertFalse(viewModel.uiState.value.casting)
    }

    @Test fun explicitStopAlsoEndsAChromecastHandshakeBeforeItReportsConnected() {
        bindViewModel()
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        installChromecastSession(connected = false)
        assertFalse(cast.state.value.chromecastSession)
        viewModel.stopPlayback()
        val pending = cast.javaClass.getDeclaredField("currentSession").apply { isAccessible = true }.get(cast)
        assertNull("A late handshake must no longer own a Cast session", pending)
        assertEquals(0, engine.resumeCalls)
        assertEquals(1, engine.stopCalls)
        assertNull(engine.snapshot.value.videoId)
    }

    @Test fun explicitStopRejectsADelayedAutoplayResult() {
        val next = CompletableDeferred<String?>()
        bindViewModel { next.await() }
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        engine.finish()
        viewModel.stopPlayback()
        next.complete("bbbbbbbbbbb")
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertNull(engine.snapshot.value.videoId)
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test fun explicitStopEndsTheDlnaReceiverWithoutAResumeEcho() = runTest {
        val remote = RecordingDlnaEngine()
        val dlna = DlnaController(ContextWrapper(null), remote, backgroundScope,
            initLibrary = {}, playerFactory = { _, _, _ -> null })
        bindViewModel(dlna = dlna)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        dlna.selectDevice(dlna.state.value.devices.single().id)
        assertTrue(dlna.startCasting(CastSource(watchId, "Receiver", 60, null, null), 0, "http://phone/stream"))
        assertTrue(viewModel.uiState.value.casting)
        viewModel.stopPlayback()
        runCurrent()
        dlna.onSessionEnded!!.invoke()
        assertFalse(dlna.state.value.session)
        assertFalse(viewModel.uiState.value.casting)
        assertEquals(1, remote.stopCalls)
        assertEquals(0, engine.resumeCalls)
        assertEquals(1, engine.stopCalls)
    }

    @Test fun explicitStopCancelsThePendingDlnaTransferBeforeItPublishesAProxy() = runTest {
        val remote = RecordingDlnaEngine()
        val dlna = DlnaController(ContextWrapper(null), remote, backgroundScope,
            initLibrary = {}, playerFactory = { _, _, _ -> null })
        bindViewModel(dlna = dlna)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        dlna.setDiscoveryEnabled(true)
        runCurrent()
        val source = CompletableDeferred<CastSource?>()
        var cancelled = false
        engine.muxedSourceLoader = {
            try { source.await() }
            finally { cancelled = true }
        }
        viewModel.onCastDevice(dlna.state.value.devices.single().id)
        assertFalse(cancelled)
        viewModel.stopPlayback()
        runCurrent()
        assertTrue("STOP must cancel the VM-owned transfer waiter", cancelled)
        source.complete(CastSource(watchId, "Late source", 60, null, null))
        runCurrent()
        assertEquals(0, remote.castCalls)
        assertFalse(dlna.state.value.session)
        assertEquals(0, engine.attachCastCalls)
        assertEquals(0, engine.resumeCalls)
        assertNull(engine.snapshot.value.videoId)
    }

    @Test fun ordinaryExitStillPreservesPlaybackWhenBackgroundPlayIsEnabled() {
        bindViewModel()
        preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        viewModel.openVideo(watchId)
        engine.settle(watchId)
        viewModel.stopOnExit()
        assertFalse(viewModel.uiState.value.visible)
        assertTrue(engine.snapshot.value.prepared)
        assertTrue(engine.snapshot.value.isPlaying)
        assertEquals(watchId, engine.snapshot.value.videoId)
        assertEquals(0, engine.stopCalls)
        assertEquals(listOf(false), engine.cancelPendingCalls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun castState() = cast.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        .get(cast) as MutableStateFlow<CastUiState>

    private fun installChromecastSession(connected: Boolean = true) {
        // A session that has not opened a socket still exercises the controller's
        // real endSession callback and remote/proxy teardown without network IO.
        val session = CastV2Session.forDefaultMediaReceiver("127.0.0.1", 8009,
            object : CastV2Session.Listener {
                override fun onConnected() = Unit
                override fun onMediaStatus(playerState: String?, positionMs: Long, durationMs: Long, idleReason: String?) = Unit
                override fun onLaunchError(reason: String) = Unit
                override fun onChannelError(reason: String) = Unit
                override fun onClosed() = Unit
            })
        cast.javaClass.getDeclaredField("currentSession").apply { isAccessible = true }.set(cast, session)
        castState().value = CastUiState(chromecastSession = connected, deviceName = "Receiver")
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

    @Test fun openingANewVideoRestoresOutputVisibilityAfterTheActivityStartsHidden() {
        bindViewModel()
        viewModel.setVideoVisible(false)
        viewModel.openVideo(watchId)
        assertTrue(engine.outputVisible)
    }

    /** Records playback commands; only [settle] marks the snapshot prepared so recast waiters resume when the test decides. */
    private class FakeEngine : PlaybackApi {
        private val _snapshot = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

        val playCalls = mutableListOf<String>()
        var resumeCalls = 0
        var stopCalls = 0
        val cancelPendingCalls = mutableListOf<Boolean>()
        var audioOnlyEnabled = false
            private set
        var castSourceLoader: (suspend () -> CastSource?)? = null
        var muxedSourceLoader: (suspend () -> CastSource?)? = null
        var attachCastCalls = 0
        var outputVisible = true
            private set
        override fun setVideoVisible(visible: Boolean) { outputVisible = visible }
        override fun setAudioOnly(enabled: Boolean) { audioOnlyEnabled = enabled }

        fun fail(report: com.hhst.youtubelite.diagnostics.PlaybackFailureReport) {
            _snapshot.value = _snapshot.value.copy(error = report.summary, failureReport = report,
                positionMs = _snapshot.value.positionMs + 1, prepared = false, isPlaying = false)
        }

        fun finish() {
            _snapshot.value = _snapshot.value.copy(ended = true, isPlaying = false)
            onEnded?.invoke()
        }

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
                userWantsPlay = true,
            )
        }
        override fun retry() = Unit
        override fun stop() { stopCalls++; _snapshot.value = PlaybackSnapshot() }
        override fun setVideoSurface(surface: SurfaceView?) = Unit
        override fun playOrPause() { if (_snapshot.value.userWantsPlay) pause() else play() }
        override fun play() { resumeCalls++; _snapshot.value = _snapshot.value.copy(isPlaying = true, userWantsPlay = true) }
        override fun pause() { _snapshot.value = _snapshot.value.copy(isPlaying = false, userWantsPlay = false) }
        override fun cancelPending(pausePlayback: Boolean) {
            cancelPendingCalls += pausePlayback
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
        override suspend fun prepareCastSource(): CastSource? = castSourceLoader?.invoke()
        override fun currentMuxedCastSource(): CastSource? = null
        override suspend fun prepareMuxedCastSource(): CastSource? = muxedSourceLoader?.invoke()
        override fun refreshCastUrl(token: String, videoId: String?, itag: Int?): String? = null
        override fun attachCastPlayer(delegate: Player?): Boolean { attachCastCalls++; return false }
        override fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) = Unit
        override fun cancelSponsorSkip() = Unit
        override fun skipSponsorNow() = Unit
        override fun skipSponsorChipSegment() = Unit
    }

    private class RecordingDlnaEngine : DlnaEngine {
        var stopCalls = 0
        var castCalls = 0
        override suspend fun search(timeoutMs: Long) = listOf(
            DLNACast.Device("http://192.168.1.2/desc", "Receiver", "192.168.1.2", true))
        override suspend fun cast(device: DLNACast.Device, url: String, title: String, mimeType: String): Boolean {
            castCalls++
            return true
        }
        override suspend fun play() = true
        override suspend fun pause() = true
        override suspend fun stop(): Boolean { stopCalls++; return true }
        override suspend fun seek(positionMs: Long) = true
        override suspend fun progress(): Pair<Long, Long> = 0L to 60_000L
        override suspend fun transportState() = DLNACast.PlaybackState.PLAYING
        override fun clearProgressCache() = Unit
    }
}
