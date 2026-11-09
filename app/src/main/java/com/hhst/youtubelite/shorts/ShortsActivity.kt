package com.hhst.youtubelite.shorts

import com.hhst.youtubelite.ui.design.labelResource
import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.comments.data.CommentsScenario
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.openCommentLink
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.data.media.toReportingVideo
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.surface.OptionDialog
import com.hhst.youtubelite.player.surface.OptionRow
import com.hhst.youtubelite.player.surface.PlayerDialog
import com.hhst.youtubelite.player.surface.PlayerDialogHost
import com.hhst.youtubelite.player.surface.PlayerPlaybackActions
import com.hhst.youtubelite.player.surface.PlayerSurfaceCallbacks
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.presentation.NewPlaylistSheet
import com.hhst.youtubelite.saves.presentation.SaveSheetLive
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsMenuAction
import com.hhst.youtubelite.shorts.domain.ShortsNavigationRequest
import com.hhst.youtubelite.shorts.domain.ShortsOverlay
import com.hhst.youtubelite.shorts.presentation.ShortsDescriptionSheet
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import com.hhst.youtubelite.shorts.presentation.RecentCommentsViewModel
import com.hhst.youtubelite.ui.components.audioTrackLabel
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.MenuSheetLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import com.hhst.youtubelite.ui.renderNotice
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class ShortsActivity : ComponentActivity() {
    private var tabNavigation = false
    private var tabBackwards = false

    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
        val animatedTab = options == null && intent.component?.packageName == packageName &&
            intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_NAVIGATION, false)
        val animation = if (animatedTab) {
            val pair = if (com.hhst.youtubelite.ui.design.readMotionScale(contentResolver) > 0f)
                com.hhst.youtubelite.ui.NativeActivity.tabAnimations(intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_BACKWARD, false))
            else 0 to 0
            android.app.ActivityOptions.makeCustomAnimation(this, pair.first, pair.second).toBundle()
        } else options
        super.startActivity(intent, animation)
    }

    private fun tabCloseAnimation(): Pair<Int, Int>? {
        if (!tabNavigation || isTaskRoot) return null
        if (com.hhst.youtubelite.ui.design.readMotionScale(contentResolver) <= 0f) return 0 to 0
        return com.hhst.youtubelite.ui.NativeActivity.tabAnimations(!tabBackwards)
    }

    private fun applyTabCloseTransition() {
        if (Build.VERSION.SDK_INT < 34) return
        val animation = tabCloseAnimation()
        if (animation == null) clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        else overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, animation.first, animation.second)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabNavigation = intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_NAVIGATION, false)
        tabBackwards = intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_BACKWARD, false)
        applyTabCloseTransition()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("navigation_tab_entry", tabNavigation)
        outState.putBoolean("navigation_tab_backward", tabBackwards)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        val animation = if (Build.VERSION.SDK_INT < 34) tabCloseAnimation() else null
        applyTabCloseTransition()
        super.finish()
        if (animation != null) overridePendingTransition(animation.first, animation.second)
    }

    lateinit var shortsViewModel: ShortsViewModel
        private set
    lateinit var commentsViewModel: CommentsViewModel
        private set
    var lastNavigationRequest: ShortsNavigationRequest? = null
        private set

    private var commentsScenario = CommentsScenario.Content
    private val commentModels by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer { RecentCommentsViewModel(createSavedStateHandle()) }
        })[RecentCommentsViewModel::class.java]
    }
    val recentCommentModels: Map<String, CommentsViewModel> get() = commentModels.models

    val shareGateway: ShareGateway by lazy { ShareGateway(this) }

    private fun launchNavigation(intent: android.content.Intent) {
        if (com.hhst.youtubelite.data.media.ContentRepositories.fixtures(this.intent))
            intent.putExtra(com.hhst.youtubelite.data.media.ContentRepositories.EXTRA_FIXTURE_SCENE, true)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        applyTabCloseTransition()
        // The shorts feed and subscription states are identity-scoped server-side.
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            val generation = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
            shortsViewModel.updateSession(generation)
            commentModels.updateSession(generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabNavigation = savedInstanceState?.getBoolean("navigation_tab_entry")
            ?: intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_NAVIGATION, false)
        tabBackwards = savedInstanceState?.getBoolean("navigation_tab_backward")
            ?: intent.getBooleanExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_BACKWARD, false)
        applyTabCloseTransition()
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        if (!fixtures) (application as com.hhst.youtubelite.App).startMediaRuntime()
        if (!fixtures) {
            val player = org.koin.java.KoinJavaComponent.getKoin().get<PlayerViewModel>()
            com.hhst.youtubelite.player.PlaybackSession.register(player)
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.hhst.youtubelite.data.media.PlaybackWatchReporting>().attach {
                    val state = player.uiState.value
                    com.hhst.youtubelite.data.media.PlaybackSample(state.videoId, player.positionState.value,
                        state.durationMs, state.isPlaying, state.discontinuityEpoch)
                }
        }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val startItemId = intent.getStringExtra(EXTRA_START_ITEM)
        val scenario = if (BuildConfig.DEBUG) ShortsScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ShortsScenario.Content
        commentsScenario = if (BuildConfig.DEBUG) CommentsScenario.parse(intent.getStringExtra(EXTRA_COMMENTS_SCENARIO)) else CommentsScenario.Content
        shortsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ShortsViewModel::class.java)
                return ShortsViewModel(if (fixtures) FixtureShortsRepository(scenario, intent.getStringExtra(EXTRA_CHANNEL_ID)) else sources.shorts(startItemId, intent.getStringExtra(EXTRA_CHANNEL_ID)), extras.createSavedStateHandle(), scenario, startItemId, if (fixtures) null else sources.videoActions,
                    if (fixtures) null else org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.shorts.data.ShortsMediaPreloader>(),
                    if (fixtures) null else sources.saves) as T
            }
        })[ShortsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shortsViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shortsViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ShortsNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@ShortsActivity, request.channelId))
                        is ShortsNavigationRequest.Share -> shareGateway.share(request.itemId)
                        is ShortsNavigationRequest.MenuAction -> if (!fixtures) {
                            when (request.action) {
                                FeedMenuAction.Share -> shareGateway.share(request.itemId)
                                FeedMenuAction.Download -> {
                                    val item = (shortsViewModel.uiState.value.content as? com.hhst.youtubelite.shorts.domain.ShortsContent.Ready)
                                        ?.page?.items?.firstOrNull { it.id == request.itemId }
                                    com.hhst.youtubelite.ui.ActivityMediaActionExecutor(this@ShortsActivity).execute(
                                        com.hhst.youtubelite.ui.MediaActionItem(request.itemId, item?.title.orEmpty(), item?.author.orEmpty(), item?.artwork.orEmpty()),
                                        com.hhst.youtubelite.ui.MediaActionKind.Download)
                                }
                                else -> Unit
                            }
                        }
                        is ShortsNavigationRequest.Rate, is ShortsNavigationRequest.Subscribe -> Unit
                    }
                }
            }
        }
        setAppContent {
            val playerViewModel: PlayerViewModel = org.koin.compose.koinInject()
            DisposableEffect(playerViewModel, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> playerViewModel.onShortsOpened()
                        Lifecycle.Event.ON_PAUSE -> playerViewModel.onShortsStopped()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) playerViewModel.onShortsOpened()
                onDispose {
                    lifecycle.removeObserver(observer)
                    if (!isChangingConfigurations) playerViewModel.onShortsClosed()
                }
            }
            val state by shortsViewModel.uiState.collectAsStateWithLifecycle()
            shortsViewModel.saveSheet?.let { com.hhst.youtubelite.ui.design.WriteProgressOverlay(it.writes) }
            // Authoritative engine identity: gates the per-page video slot below.
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            // Shorts menu rows open the shared player dialogs; the window half of
            // the bridge stays inert because Shorts own their chrome.
            var shortsPlayerDialog by remember { mutableStateOf<PlayerDialog?>(null) }
            var descriptionItem by remember { mutableStateOf<String?>(null) }
            var playbackOrderOpen by remember { mutableStateOf(false) }
            val shortsCallbacks = remember(playerViewModel) {
                object : PlayerSurfaceCallbacks, PlayerPlaybackActions by playerViewModel {
                    override fun onGestureEnd() = playerViewModel.onGestureEnd()
                    override fun onBack() {}
                    override fun onBrightness(delta: Float) {}
                    override fun onVolume(delta: Float) {}
                    override fun onPip() {}
                    override fun onShare() {}
                    override fun shareCastLink() {}
                }
            }
            val onShortsMenuSelected: (ShortsMenuAction, String) -> Unit = { action, itemId ->
                shortsViewModel.onAction(ShortsAction.CloseMenu)
                when (action) {
                    ShortsMenuAction.Description -> descriptionItem = itemId
                    ShortsMenuAction.SaveToPlaylist -> shortsViewModel.onAction(ShortsAction.OpenSaveToPlaylist(itemId))
                    ShortsMenuAction.Captions -> shortsPlayerDialog = PlayerDialog.Captions
                    ShortsMenuAction.AudioTrack -> shortsPlayerDialog = PlayerDialog.Audio
                    ShortsMenuAction.PlaybackSpeed -> shortsPlayerDialog = PlayerDialog.Speed
                    ShortsMenuAction.PlaybackOrder -> playbackOrderOpen = true
                    ShortsMenuAction.Quality -> shortsPlayerDialog = PlayerDialog.Quality
                    ShortsMenuAction.Download -> downloadShort(itemId)
                    ShortsMenuAction.Share -> shareGateway.share(itemId)
                }
            }
            // Comments exist only while their panel is open: no per-item model,
            // and no comment request, is created for a page the user only swipes past.
            val commentItem = (state.overlay as? ShortsOverlay.Comments)?.itemId
            val activeComments = remember(commentItem) { commentItem?.let { commentsFor(it) } }
            if (activeComments != null) commentsViewModel = activeComments
            // Closing or retargeting the panel cancels the previous model's
            // in-flight reads; its loaded pages stay for the next open.
            var openTarget by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(commentItem) {
                val previous = openTarget
                openTarget = commentItem
                if (previous != null && previous != commentItem) {
                    recentCommentModels[previous]?.cancelTransient()
                }
            }
            val fallbackComments = remember { kotlinx.coroutines.flow.MutableStateFlow(com.hhst.youtubelite.comments.domain.CommentsUiState()) }
            val commentsState by (activeComments?.uiState ?: fallbackComments).collectAsStateWithLifecycle()
            LaunchedEffect(activeComments) {
                if (activeComments == null) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    activeComments.navigationRequests.collect { request ->
                        if (request is CommentNavigationRequest.OpenAuthor) {
                            launchNavigation(ChannelActivity.intent(this@ShortsActivity, request.channelId))
                        }
                        if (request is CommentNavigationRequest.OpenLink) openCommentLink(request.link,
                            org.koin.java.KoinJavaComponent.getKoin().get<PlayerViewModel>())
                    }
                }
            }
            val closeComments = { activeComments?.onAction(CommentAction.CloseMenu); shortsViewModel.onAction(ShortsAction.CloseComments) }
            BackHandler(enabled = state.overlay != ShortsOverlay.None) {
                when (state.overlay) {
                    is ShortsOverlay.Comments -> {
                        if (commentsState.overlay != CommentsOverlay.None) activeComments?.onAction(CommentAction.CloseMenu)
                        else closeComments()
                    }
                    is ShortsOverlay.Menu -> shortsViewModel.onAction(ShortsAction.CloseMenu)
                    ShortsOverlay.None -> Unit
                }
            }
            if (!fixtures) {
                LaunchedEffect(playerState.videoId, playerState.firstFrameRendered, state.currentItemId) {
                    if (playerState.firstFrameRendered && playerState.videoId == state.currentItemId) {
                        playerState.videoId?.let(shortsViewModel::onPlaybackReady)
                    }
                }
                LaunchedEffect(state.currentItemId, lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        state.currentItemId?.let(playerViewModel::openShort)
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
                val reporting = remember {
                    org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.PlaybackWatchReporting>()
                }
                LaunchedEffect(reporting, lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        shortsViewModel.uiState
                            .map { s -> s.currentItemId?.let { s.details[it] } }
                            .distinctUntilChanged()
                            .collect { detail ->
                                // Unhydrated items keep the previous arming; playback
                                // attribution stays keyed on the sampler's video id.
                                if (detail != null) reporting.publish(detail.toReportingVideo())
                            }
                    }
                }
            }
            // Startup telemetry: entry, media prepare and first frame are
            // logged separately so the seed read (logged by the repository)
            // can be compared against the media pipeline it must not block.
            if (!fixtures) {
                val entryAt = remember { android.os.SystemClock.elapsedRealtime() }
                // The entry item: the seed when one was passed, otherwise the
                // first item the feed settles on.
                var entryItem by remember { mutableStateOf(state.currentItemId) }
                LaunchedEffect(state.currentItemId) {
                    if (entryItem == null) entryItem = state.currentItemId
                }
                val preloader = remember { org.koin.java.KoinJavaComponent.getKoin()
                    .get<com.hhst.youtubelite.shorts.data.ShortsMediaPreloader>() }
                LaunchedEffect(Unit) {
                    com.hhst.youtubelite.diagnostics.AppLog.event(
                        com.hhst.youtubelite.diagnostics.AppLog.Category.PLAYER, "shorts_entry",
                        mapOf("seeded" to (entryItem != null)))
                }
                var preparedLogged by remember { mutableStateOf(false) }
                LaunchedEffect(playerState.videoId, playerState.prepared, entryItem) {
                    if (!preparedLogged && entryItem != null && playerState.prepared &&
                        playerState.videoId == com.hhst.youtubelite.extractor.VideoId.parse(entryItem!!)) {
                        preparedLogged = true
                        com.hhst.youtubelite.diagnostics.AppLog.event(
                            com.hhst.youtubelite.diagnostics.AppLog.Category.PLAYER, "shorts_media_prepared",
                            mapOf("video_id" to entryItem, "ms" to android.os.SystemClock.elapsedRealtime() - entryAt))
                    }
                }
                // One event per video that reaches its first frame: latency from
                // selection to frame with the prefetch-hit/cold split the swipe
                // run reports on. Keyed on the engine's id so a frame that lands
                // after the pager moved on still counts.
                val settleStamps = remember {
                    object : LinkedHashMap<String, Long>(16, 0.75f, true) {
                        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 24
                    }
                }
                LaunchedEffect(state.currentItemId) {
                    state.currentItemId?.let { settleStamps[it] = android.os.SystemClock.elapsedRealtime() }
                }
                val frameLogged = remember { mutableSetOf<String>() }
                // Collect the state flow directly: a per-video frame must never
                // be lost to recomposition coalescing during fast swipes.
                LaunchedEffect(playerViewModel) {
                    playerViewModel.uiState.collect { s ->
                        val item = s.videoId ?: return@collect
                        if (!s.firstFrameRendered) return@collect
                        if (frameLogged.size >= 64) frameLogged.remove(frameLogged.first())
                        if (!frameLogged.add(item)) return@collect
                        val warmed = preloader.prefetchedBytes(item)
                        val settledAt = settleStamps[item]
                        com.hhst.youtubelite.diagnostics.AppLog.event(
                            com.hhst.youtubelite.diagnostics.AppLog.Category.PLAYER, "shorts_first_frame",
                            mapOf("video_id" to item,
                                "ms" to (settledAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: -1L),
                                "mode" to if (warmed > 0) "prefetch" else "cold", "prefetch_bytes" to warmed,
                                "entry" to (item == entryItem),
                                "settled" to (item == shortsViewModel.uiState.value.currentItemId)))
                    }
                }
            }
            val feedPage = (state.content as? com.hhst.youtubelite.shorts.domain.ShortsContent.Ready)?.page
            val saveSheetOpen = shortsViewModel.saveSheet?.state?.collectAsStateWithLifecycle()?.value != null
            val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle(
                lifecycle = lifecycle, minActiveState = Lifecycle.State.CREATED,
            )
            val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
            LaunchedEffect(playerState.ended, playerState.prepared, playerState.videoId, state.currentItemId,
                playerState.loopMode, feedPage?.items?.size, feedPage?.nextKey, state.overlay, resumed,
                playbackOrderOpen, shortsPlayerDialog, descriptionItem, saveSheetOpen) {
                if (!fixtures && resumed && playerState.prepared && playerState.ended && playerState.loopMode == LoopMode.QUEUE_NEXT &&
                    !playbackOrderOpen && shortsPlayerDialog == null && descriptionItem == null && !saveSheetOpen) {
                    playerState.videoId?.let(shortsViewModel::onPlaybackEnded)
                }
            }
            ShortsScreen(state, shortsViewModel::onAction, Modifier.fillMaxSize(),
                videoContent = if (fixtures) null else { itemId ->
                    // Only the short the engine actually loaded may render; every
                    // other page keeps its cover, so a stale frame can never leak
                    // into a new page while the next one resolves.
                    if (playerState.videoId == itemId) {
                        // Shorts owns loading/error feedback, including the swipe gate.
                        com.hhst.youtubelite.watch.presentation.WatchPlayer(Modifier.fillMaxSize(), { finish() },
                            pip = true, showPipLoadingIndicator = false)
                    }
                },
                playbackControls = if (fixtures) null else { itemId, active ->
                    if (playerState.videoId == itemId) {
                        com.hhst.youtubelite.shorts.presentation.ShortPlaybackControls(
                            playing = playerState.isPlaying,
                            active = active,
                            paused = playerState.shortPausedByUser || playerState.ended,
                            loading = !playerState.prepared || playerState.loading,
                            ready = playerState.firstFrameRendered,
                            error = playerState.error,
                            notice = playerState.notice?.let { getString(R.string.player_payment_required) },
                            onRetry = playerViewModel::onRetry,
                            onDetails = if (playerState.failureReport == null) null else playerViewModel::onErrorDetails,
                            onToggle = playerViewModel::onPlayPause,
                            onHoldStart = playerViewModel::onSpeedHoldStart,
                            onHoldEnd = playerViewModel::onSpeedHoldEnd,
                        ) {}
                    }
                },
                // Surface resets and same-video recovery must not cover a real retained frame.
                isVideoReady = { playerState.videoId == it && playerState.hasRenderedVideoFrame },
                onSwipeStart = if (fixtures) null else playerViewModel::holdShortsForSwipe,
                onSwipeCancel = if (fixtures) null else playerViewModel::resumeShortsAfterSwipe)
            if (state.overlay is ShortsOverlay.Comments && activeComments != null) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).testTag("comments-panel-host")) {
                    CommentsPanel(
                        state = commentsState,
                        commentCount = commentsState.total,
                        onAction = activeComments::onAction,
                        onClose = closeComments,
                    )
                }
            }
            if (state.overlay is ShortsOverlay.Menu) {
                val menuItemId = (state.overlay as? ShortsOverlay.Menu)?.itemId
                if (fixtures) {
                    val actions = FeedMenuKind.Short.actions
                    MenuBottomSheet(
                        items = actions.map { action -> MenuSheetItem(action.icon(), stringResource(action.labelResource)) },
                        onDismiss = { shortsViewModel.onAction(ShortsAction.CloseMenu) },
                        onSelect = { index -> shortsViewModel.onAction(ShortsAction.MenuSelected(actions[index])) },
                        paneTitle = stringResource(R.string.ui_shorts_actions),
                    )
                } else if (menuItemId != null) {
                    val rows = shortsMenuRows(state.details[menuItemId]?.description)
                    MenuBottomSheet(
                        items = rows.map { action ->
                            MenuSheetItem(action.iconOrNull(), shortsMenuLabel(action, playerState),
                                iconResource = action.iconResource(playerState))
                        },
                        onDismiss = { shortsViewModel.onAction(ShortsAction.CloseMenu) },
                        onSelect = { index -> onShortsMenuSelected(rows[index], menuItemId) },
                        paneTitle = stringResource(R.string.ui_shorts_actions),
                    )
                }
            }
            shortsPlayerDialog?.let { dialog ->
                PlayerDialogHost(dialog, playerState, shortsCallbacks, onDismiss = { shortsPlayerDialog = null })
            }
            if (playbackOrderOpen) OptionDialog(
                title = stringResource(R.string.settings_playback),
                rows = listOf(LoopMode.PAUSE_AT_END, LoopMode.LOOP_ONE, LoopMode.QUEUE_NEXT).map {
                    OptionRow(it, stringResource(PlayerUi.loopLabelRes(it)))
                },
                selected = playerState.loopMode,
                onPick = { mode -> mode?.let(playerViewModel::onLoopMode); playbackOrderOpen = false },
                onDismiss = { playbackOrderOpen = false },
                tag = "shorts-playback-order",
            )
            descriptionItem?.let { itemId ->
                state.details[itemId]?.let { detail ->
                    ShortsDescriptionSheet(
                        title = detail.item.title,
                        author = detail.item.author,
                        description = detail.description.orEmpty(),
                        onDismiss = { descriptionItem = null },
                    )
                }
            }
            shortsViewModel.saveSheet?.let { sheet ->
                val sheetState by sheet.state.collectAsStateWithLifecycle()
                sheetState?.let { current ->
                    if (current.newPlaylist) NewPlaylistSheet(
                        draft = NewPlaylistDraft(current.title, current.description),
                        onDismiss = sheet::dismiss,
                        onDraftChange = { sheet.draftChanged(it.title, it.description) },
                        onSubmit = sheet::submitNewPlaylist,
                        pending = current.pending,
                    ) else SaveToPlaylistSheet(
                        playlists = emptyList(),
                        onDismiss = sheet::dismiss,
                        onSelect = {},
                        onNewPlaylist = sheet::openNewPlaylist,
                        live = SaveSheetLive(
                            choices = current.choices,
                            failed = current.choicesFailed,
                            pending = current.pending,
                            onToggle = sheet::toggle,
                            onRetry = sheet::retry,
                        ),
                    )
                }
            }
        }
    }

    private val boundCommentModels = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun commentsFor(itemId: String): CommentsViewModel {
        val model = commentModels.getOrCreate(itemId) { createComments(itemId, it) }
        val retainedIds = recentCommentModels.keys
        boundCommentModels.keys.toList().filterNot(retainedIds::contains).forEach { id ->
            boundCommentModels.remove(id)?.cancel()
        }
        if (itemId !in boundCommentModels) boundCommentModels[itemId] = bindCommentNotices(model)
        return model
    }

    private fun createComments(itemId: String, state: androidx.lifecycle.SavedStateHandle): CommentsViewModel {
        return CommentsViewModel(
            if (com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) FixtureCommentsRepository(commentsScenario)
            else org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>().comments(),
            state,
            itemId,
        )
    }

    override fun onDestroy() {
        boundCommentModels.values.forEach { it.cancel() }
        boundCommentModels.clear()
        super.onDestroy()
    }

    private fun downloadShort(itemId: String) {
        val item = (shortsViewModel.uiState.value.content as? com.hhst.youtubelite.shorts.domain.ShortsContent.Ready)
            ?.page?.items?.firstOrNull { it.id == itemId }
        com.hhst.youtubelite.ui.ActivityMediaActionExecutor(this).execute(
            com.hhst.youtubelite.ui.MediaActionItem(itemId, item?.title.orEmpty(), item?.author.orEmpty(), item?.artwork.orEmpty()),
            com.hhst.youtubelite.ui.MediaActionKind.Download,
        )
    }

    private fun shortsMenuRows(description: String?): List<ShortsMenuAction> = buildList {
        if (!description.isNullOrBlank()) add(ShortsMenuAction.Description)
        if (shortsViewModel.saveSheet != null) add(ShortsMenuAction.SaveToPlaylist)
        add(ShortsMenuAction.Captions)
        add(ShortsMenuAction.AudioTrack)
        add(ShortsMenuAction.PlaybackOrder)
        add(ShortsMenuAction.PlaybackSpeed)
        add(ShortsMenuAction.Quality)
        add(ShortsMenuAction.Download)
        add(ShortsMenuAction.Share)
    }

    private fun ShortsMenuAction.iconOrNull(): AppIcon? = when (this) {
        ShortsMenuAction.Description -> AppIcon.Info
        ShortsMenuAction.SaveToPlaylist -> AppIcon.Bookmark
        ShortsMenuAction.Download -> AppIcon.Download
        ShortsMenuAction.Share -> AppIcon.Share
        else -> null
    }

    private fun ShortsMenuAction.iconResource(state: PlayerUiState): Int? = when (this) {
        ShortsMenuAction.Captions -> R.drawable.ic_subtitles_on
        ShortsMenuAction.AudioTrack -> R.drawable.ic_track
        ShortsMenuAction.PlaybackSpeed -> R.drawable.ic_playback_speed
        ShortsMenuAction.PlaybackOrder -> PlayerUi.loopIcon(state.loopMode)
        ShortsMenuAction.Quality -> R.drawable.ic_quality
        else -> null
    }

    @androidx.compose.runtime.Composable
    private fun shortsMenuLabel(action: ShortsMenuAction, state: PlayerUiState): String = when (action) {
        ShortsMenuAction.Description -> stringResource(R.string.video_description)
        ShortsMenuAction.SaveToPlaylist -> stringResource(R.string.save_to_playlist)
        ShortsMenuAction.Captions -> stringResource(R.string.subtitles) + when {
            !state.subtitleEnabled && state.subtitleTracks.isNotEmpty() -> " · " + stringResource(R.string.subtitles_off)
            else -> state.subtitleTracks
                .firstOrNull { it.key == state.subtitleKey }?.takeIf { state.subtitleEnabled }
                ?.let { " · " + PlayerUi.languageLabel(it.language) }.orEmpty()
        }
        ShortsMenuAction.AudioTrack -> stringResource(R.string.audio_track) + state.audioTracks
            .firstOrNull { it.key == state.audioTrackKey }?.let { " · " + audioTrackLabel(it) }.orEmpty()
        ShortsMenuAction.PlaybackSpeed -> stringResource(R.string.info_playback_speed) + " · " + PlayerUi.speedLabel(state.speed)
        ShortsMenuAction.PlaybackOrder -> stringResource(PlayerUi.loopLabelRes(state.loopMode))
        ShortsMenuAction.Quality -> {
            // Same real-value chain as the info dialog: pinned label, else what is on
            // screen, else the auto mode itself.
            val current = state.qualityLabel ?: PlayerUi.playingQuality(state.activeQuality, state.videoHeight)
                ?: stringResource(R.string.player_quality_auto)
            stringResource(R.string.player_info_quality) + " · " + current
        }
        ShortsMenuAction.Download -> stringResource(R.string.download)
        ShortsMenuAction.Share -> stringResource(R.string.share)
    }

    private fun FeedMenuAction.icon() = when (this) {
        FeedMenuAction.Download -> AppIcon.Download
        FeedMenuAction.Share -> AppIcon.Share
        FeedMenuAction.NotInterested -> AppIcon.Block
        FeedMenuAction.SendFeedback -> AppIcon.Feedback
        else -> AppIcon.More
    }

    companion object {
        const val EXTRA_SCENARIO = "shorts_scenario"
        const val EXTRA_COMMENTS_SCENARIO = "shorts_comments_scenario"
        const val EXTRA_CHANNEL_ID = "shorts_channel_id"
        const val EXTRA_START_ITEM = "shorts_start_item"

        fun intent(context: Context, startItemId: String? = null, scenario: ShortsScenario? = null, channelId: String? = null) =
            Intent(context, ShortsActivity::class.java)
                .putExtra(EXTRA_START_ITEM, startItemId)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
