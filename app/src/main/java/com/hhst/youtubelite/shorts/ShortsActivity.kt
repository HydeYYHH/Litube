package com.hhst.youtubelite.shorts

import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.data.media.toReportingVideo
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsNavigationRequest
import com.hhst.youtubelite.shorts.domain.ShortsOverlay
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import com.hhst.youtubelite.shorts.presentation.RecentCommentsViewModel
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import com.hhst.youtubelite.ui.renderNotice

class ShortsActivity : ComponentActivity() {
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

    /** Navigates to another in-app screen, carrying this screen's debug fixture scene with it. */
    private fun launchNavigation(intent: android.content.Intent) {
        if (com.hhst.youtubelite.data.media.ContentRepositories.fixtures(this.intent))
            intent.putExtra(com.hhst.youtubelite.data.media.ContentRepositories.EXTRA_FIXTURE_SCENE, true)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // The shorts feed and subscription states are identity-scoped server-side.
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            val generation = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
            shortsViewModel.updateSession(generation)
            commentModels.updateSession(generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    if (fixtures) null else org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.shorts.data.ShortsMediaPreloader>()) as T
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
                        // Fixture-only typed exits: the feedback-only menu hides in production
                        // (state.live) and rate/subscribe writes run in-VM there. No `else`: a new
                        // request type must fail to compile here.
                        is ShortsNavigationRequest.Rate, is ShortsNavigationRequest.Subscribe,
                        is ShortsNavigationRequest.MenuAction -> Unit
                    }
                }
            }
        }
        setAppContent {
            val playerViewModel: PlayerViewModel = org.koin.compose.koinInject()
            DisposableEffect(playerViewModel, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> playerViewModel.onShortsOpened()
                        Lifecycle.Event.ON_STOP -> if (fixtures) playerViewModel.onShortsClosed() else playerViewModel.onShortsStopped()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playerViewModel.onShortsOpened()
                onDispose {
                    lifecycle.removeObserver(observer)
                    if (!isChangingConfigurations) playerViewModel.onShortsClosed()
                }
            }
            val state by shortsViewModel.uiState.collectAsStateWithLifecycle()
            // Authoritative engine identity: gates the per-page video slot below.
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
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
                LaunchedEffect(state.currentItemId, lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        state.currentItemId?.let(playerViewModel::openShort)
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
                // The settled item's hydrated metadata feeds the session's watch reporting.
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
            ShortsScreen(state, shortsViewModel::onAction, Modifier.fillMaxSize(),
                videoContent = if (fixtures) null else { itemId ->
                    // Only the short the engine actually loaded may render; every
                    // other page keeps its cover, so a stale frame can never leak
                    // into a new page while the next one resolves.
                    if (playerState.videoId == itemId) {
                        com.hhst.youtubelite.watch.presentation.WatchPlayer(Modifier.fillMaxSize(), { finish() }, pip = true)
                    }
                },
                playbackControls = if (fixtures) null else { itemId ->
                    if (playerState.videoId == itemId) {
                        com.hhst.youtubelite.shorts.presentation.ShortPlaybackControls(
                            playing = playerState.isPlaying,
                            loading = !playerState.prepared || playerState.loading,
                            error = playerState.error,
                            onRetry = playerViewModel::onRetry,
                            onToggle = playerViewModel::onPlayPause,
                            onHoldStart = playerViewModel::onSpeedHoldStart,
                            onHoldEnd = playerViewModel::onSpeedHoldEnd,
                        ) {}
                    }
                },
                isVideoReady = { playerState.videoId == it && playerState.firstFrameRendered },
                onSwipeStart = if (fixtures) null else playerViewModel::holdShortsForSwipe,
                onSwipeCancel = if (fixtures) null else playerViewModel::resumeShortsAfterSwipe)
            if (state.overlay is ShortsOverlay.Comments && activeComments != null) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("comments-panel-host")) {
                    CommentsPanel(
                        state = commentsState,
                        commentCount = commentsState.total,
                        onAction = activeComments::onAction,
                        onClose = closeComments,
                    )
                }
            }
            if (state.overlay is ShortsOverlay.Menu) {
                MenuBottomSheet(
                    items = FeedMenuKind.Short.actions.map { action -> MenuSheetItem(action.icon(), action.label) },
                    onDismiss = { shortsViewModel.onAction(ShortsAction.CloseMenu) },
                    onSelect = { index -> shortsViewModel.onAction(ShortsAction.MenuSelected(FeedMenuKind.Short.actions[index])) },
                    paneTitle = "Shorts actions",
                )
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

    private fun FeedMenuAction.icon() = when (this) {
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
