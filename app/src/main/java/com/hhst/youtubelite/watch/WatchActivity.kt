package com.hhst.youtubelite.watch

import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import com.hhst.youtubelite.navigation.openContentLink
import android.content.Context
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.toReportingVideo
import android.content.Intent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt
import android.graphics.Color
import com.hhst.youtubelite.ui.showMessage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.R
import com.hhst.youtubelite.comments.data.CommentsScenario
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.WatchAction
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchNavigationRequest
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.SearchPanel
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.openCommentLink
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.downloader.ui.DownloadEntries
import com.hhst.youtubelite.downloader.ui.DownloadSingleSpec
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.share.VideoShare
import com.hhst.youtubelite.watch.domain.WatchLayout
import com.hhst.youtubelite.watch.domain.WatchOverlay
import com.hhst.youtubelite.watch.presentation.WatchPlayer
import com.hhst.youtubelite.watch.presentation.withWatchPresentation
import com.hhst.youtubelite.watch.presentation.WatchScreen
import com.hhst.youtubelite.watch.presentation.watchPageAlpha
import com.hhst.youtubelite.watch.presentation.watchPageProgress
import com.hhst.youtubelite.watch.presentation.watchPageContent
import com.hhst.youtubelite.watch.presentation.WatchVideoMenu
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import com.hhst.youtubelite.ui.renderNotice
import com.hhst.youtubelite.ui.observeAccountChanges

class WatchActivity : com.hhst.youtubelite.player.PlaybackActivity(), com.hhst.youtubelite.ui.components.WatchSheetHost {
    override var sheetPlayerBottomPx by mutableIntStateOf(0)
        private set
    override val sheetWindowHeightPx: Int get() = window.decorView.height
    private var transitionVersion by mutableIntStateOf(0)
    private var watchWindowFocused by mutableStateOf(false)
    private var miniHandoffPending by mutableStateOf(false)
    private var localMiniCollapse by mutableStateOf(false)
    private var afterMiniCollapse: ((Rect) -> Unit)? = null
    private var miniHandoffState by mutableStateOf<com.hhst.youtubelite.player.PlayerUiState?>(null)
    private var playerWindowBounds: Rect? = null
    private var entryBackdrop: android.graphics.Bitmap? = null
    private var waitForEntryDraw = true
    private var appliedWindowColor: Int? = null
    lateinit var watchViewModel: WatchViewModel
        private set
    lateinit var commentsViewModel: CommentsViewModel
        private set
    lateinit var chatViewModel: com.hhst.youtubelite.watch.chat.LiveChatViewModel
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    var lastNavigationRequest: WatchNavigationRequest? = null
        private set
    var lastCommentRequest: CommentNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the card; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) watchViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }

    private val chatAuthorizationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { /* Returning keeps the draft. Only an explicit Send can post it. */ }

    /** Test seams: recorded payloads plus launch suppression for instrumentation runs. */
    var lastDownloadSpec: DownloadSingleSpec? = null
        private set
    var lastQueuedItem: QueueItem? = null
        private set
    var launchDownload: Boolean = true
    private var fixtures = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = com.hhst.youtubelite.ui.theme.AppAppearance.dark ?:
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES)
        applyWatchWindowBackground(if (dark) Color.BLACK else Color.WHITE)
        val barStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(barStyle, barStyle)
        entryBackdrop = com.hhst.youtubelite.navigation.WatchTransition.backdrop(intent)
        waitForEntryDraw = savedInstanceState == null
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: playerViewModel.uiState.value.videoId.orEmpty()
        val scenario = if (BuildConfig.DEBUG) WatchScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else WatchScenario.Content
        // Debug seam: attach a chat continuation to the fixture page so instrumentation can
        // drive the live-chat routing without a real stream.
        val fixtureChatKey = if (BuildConfig.DEBUG && fixtures) intent.getStringExtra(EXTRA_CHAT_KEY) else null
        // A tapped card's summary paints the header until the page read lands.
        val provisional = com.hhst.youtubelite.watch.domain.WatchProvisionalStore.consume(videoId)
        watchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == WatchViewModel::class.java)
                return WatchViewModel(if (fixtures) FixtureWatchRepository(scenario, fixtureChatKey) else sources.watch, extras.createSavedStateHandle(), videoId, scenario, if (fixtures) null else sources.votes, if (fixtures) null else sources.videoActions, if (fixtures) null else sources.saves, provisional, loadOnStart = fixtures, avatars = if (fixtures) null else sources.avatars) as T
            }
        })[WatchViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        val commentsScenario = if (BuildConfig.DEBUG) CommentsScenario.parse(intent.getStringExtra(EXTRA_COMMENTS_SCENARIO)) else CommentsScenario.Content
        commentsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == CommentsViewModel::class.java)
                return CommentsViewModel(if (fixtures) FixtureCommentsRepository(commentsScenario) else sources.comments(), extras.createSavedStateHandle(), videoId, loadOnStart = fixtures) as T
            }
        })[CommentsViewModel::class.java]
        bindCommentNotices(commentsViewModel)
        if (!fixtures) observeAccountChanges {
            watchViewModel.refreshSession()
            commentsViewModel.refreshSession()
        }
        chatViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == com.hhst.youtubelite.watch.chat.LiveChatViewModel::class.java)
                return com.hhst.youtubelite.watch.chat.LiveChatViewModel(
                    if (fixtures) null else com.hhst.youtubelite.watch.chat.MediaLiveChatGateway(sources.client,
                        com.hhst.youtubelite.watch.chat.ChatWebSessionStore(com.hhst.youtubelite.data.media.PreferencesAuthStore(this@WatchActivity))),
                    signedIn = fixtures || com.hhst.youtubelite.data.media.MediaSession.signedIn()) as T
            }
        })[com.hhst.youtubelite.watch.chat.LiveChatViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.actionNotices.collect { notice -> renderNotice(notice) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.authorizationRequests.collect { request ->
                    if (chatViewModel.authorizationIsCurrent(request)) chatAuthorizationLauncher.launch(
                        Intent(this@WatchActivity, com.hhst.youtubelite.account.ChatWebSignInActivity::class.java))
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    kotlinx.coroutines.flow.combine(watchViewModel.uiState, playerViewModel.uiState,
                        com.hhst.youtubelite.data.media.MediaSession.changes, inPip) { watch, player, _, pip ->
                        val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
                        chatViewModel.updateSession(fixtures || session.signedIn, session.generation)
                        com.hhst.youtubelite.watch.chat.liveChatTarget(
                            (watch.content as? WatchContent.Ready)?.page?.video, player.isLive,
                            watch.commentsOpen, watch.searchOpen, player.fullscreen, pip,
                        )
                    }.distinctUntilChanged().collect { target ->
                        when {
                            target == null -> chatViewModel.close()
                            target.replay -> chatViewModel.openReplay(target.key) {
                                playerViewModel.positionState.value
                            }
                            else -> chatViewModel.open(target.key, target.messageParams)
                        }
                    }
                } finally { chatViewModel.close() }
            }
        }
        if (savedInstanceState == null && BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_OPEN_COMMENTS, false)) {
            watchViewModel.onAction(WatchAction.OpenComments(videoId))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commentsViewModel.navigationRequests.collect { request ->
                    lastCommentRequest = request
                    when (request) {
                        is CommentNavigationRequest.OpenAuthor -> launchNavigation(ChannelActivity.intent(this@WatchActivity, request.channelId))
                        is CommentNavigationRequest.OpenLink -> openCommentLink(request.link, playerViewModel)
                        // Comment writes and menus are handled in the CommentsViewModel (with server
                        // confirmation in production); the host only hands author opens off to the
                        // channel page. No `else`: a new request type must fail to compile here.
                        is CommentNavigationRequest.SubmitComment, is CommentNavigationRequest.SubmitReply,
                        is CommentNavigationRequest.EditComment, is CommentNavigationRequest.DeleteComment,
                        is CommentNavigationRequest.LikeComment, is CommentNavigationRequest.DislikeComment,
                        is CommentNavigationRequest.ReportComment, is CommentNavigationRequest.OpenCommentMenu,
                        is CommentNavigationRequest.OpenReplyMenu -> Unit
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is WatchNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@WatchActivity, request.channelId))
                        is WatchNavigationRequest.Search -> launchNavigation(SearchResultsActivity.intent(this@WatchActivity, request.query))
                        is WatchNavigationRequest.Watch -> launchNavigation(intent(this@WatchActivity, request.videoId)
                            .apply { request.startSeconds?.let { putExtra(EXTRA_START_SECONDS, it) } })
                        is WatchNavigationRequest.SeekTo -> playerViewModel.onSeek(request.seconds * 1000)
                        is WatchNavigationRequest.OpenLink -> openDescriptionLink(request.url)
                        is WatchNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            Intent(this@WatchActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        WatchNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        is WatchNavigationRequest.Share -> shareGateway.share(request.videoId)
                        is WatchNavigationRequest.Download -> {
                            val page = (watchViewModel.uiState.value.content as? WatchContent.Ready)?.page
                            val video = page?.video?.takeIf { it.id == request.videoId }
                            val related = page?.related?.firstOrNull { it.id == request.videoId }
                            val source = VideoShare.urlFor(request.videoId) ?: return@collect
                            val spec = DownloadEntries.single(
                                source,
                                video?.title ?: related?.title.orEmpty(),
                                video?.channel ?: related?.channel,
                                null,
                            )
                            lastDownloadSpec = spec
                            if (spec != null && launchDownload) DownloadEntries.show(this@WatchActivity, spec)
                        }
                        is WatchNavigationRequest.Queue -> {
                            val page = (watchViewModel.uiState.value.content as? WatchContent.Ready)?.page
                            val video = page?.video?.takeIf { it.id == request.videoId }
                            val related = page?.related?.firstOrNull { it.id == request.videoId }
                            val canonicalId = VideoShare.urlFor(request.videoId)?.substringAfterLast('/') ?: return@collect
                            val item = QueueItem(
                                videoId = canonicalId,
                                title = (video?.title ?: related?.title).orEmpty().ifBlank { request.videoId },
                                author = video?.channel ?: related?.channel,
                            )
                            lastQueuedItem = item
                            playerViewModel.onQueueAdd(item)
                            showMessage(getString(R.string.queue_item_added))
                        }
                        is WatchNavigationRequest.OpenWith -> shareGateway.openWith(request.videoId)
                        // Typed exits for fixture instrumentation and writes the WatchViewModel runs
                        // itself with server confirmation in production (votes, subscribe, saves);
                        // PageMenu/Comments mirror VM overlay state. No `else`: a new request type
                        // must fail to compile here.
                        is WatchNavigationRequest.Comments -> commentsViewModel.startLoading()
                        WatchNavigationRequest.PageMenu,
                        is WatchNavigationRequest.Like, is WatchNavigationRequest.Dislike,
                        is WatchNavigationRequest.Subscribe, is WatchNavigationRequest.SaveToWatchLater,
                        is WatchNavigationRequest.SaveToPlaylist, is WatchNavigationRequest.CreatePlaylist,
                        is WatchNavigationRequest.Feedback -> Unit
                    }
                }
            }
        }
        if (!fixtures) {
            (application as com.hhst.youtubelite.App).startMediaRuntime()
            playerViewModel.openVideo(watchViewModel.uiState.value.videoId,
                if (savedInstanceState == null) intent.startSecondsExtra() else null)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Let initial media IO settle before optional page/comments/vote reads.
                    // A terminal error releases data immediately; automatic recovery stays loading.
                    // Stalls retain the bounded wait, and explicit page actions can start sooner.
                    kotlinx.coroutines.withTimeoutOrNull(3_000) {
                        playerViewModel.uiState.first {
                            it.videoId == videoId && (it.firstFrameRendered || it.error != null && !it.loading)
                        }
                    }
                    watchViewModel.startLoading()
                    commentsViewModel.startLoading()
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            // Progress reporting is session-owned (PlaybackWatchReporting): this activity only
            // publishes the loaded page's metadata, so reporting keeps running after the
            // activity stops while playback continues in PiP or the mini player.
            val reporting = org.koin.java.KoinJavaComponent.getKoin()
                .get<com.hhst.youtubelite.data.media.PlaybackWatchReporting>()
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Only real pages publish: a Loading transition (reload, video switch)
                    // must not stop the reporting of the still-playing session.
                    watchViewModel.uiState.map { (it.content as? WatchContent.Ready)?.page?.video }
                        .distinctUntilChanged()
                        .collect { video -> if (video != null) reporting.publish(video.toReportingVideo()) }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    playerViewModel.uiState.collect { playback ->
                        val id = playback.videoId
                        if (id != null && id != watchViewModel.uiState.value.videoId) {
                            watchViewModel.selectVideo(id)
                            commentsViewModel.selectVideo(id)
                        }
                    }
                }
            }
        }
        if (!fixtures) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    commentsViewModel.uiState,
                    watchViewModel.uiState.map { it.videoId to (it.content is WatchContent.Ready) }.distinctUntilChanged(),
                ) { comments, _ -> comments }.collect { commentsState ->
                    val content = commentsState.content
                    val first = (content as? CommentsContent.Ready)?.threads?.firstOrNull()
                    watchViewModel.updateCommentsPreview(commentsState.videoId, when (content) {
                        CommentsContent.Loading -> com.hhst.youtubelite.watch.domain.WatchCommentsStatus.Loading
                        CommentsContent.Empty -> com.hhst.youtubelite.watch.domain.WatchCommentsStatus.Empty
                        CommentsContent.Disabled -> com.hhst.youtubelite.watch.domain.WatchCommentsStatus.Disabled
                        CommentsContent.Failed -> com.hhst.youtubelite.watch.domain.WatchCommentsStatus.Failed
                        is CommentsContent.Ready -> com.hhst.youtubelite.watch.domain.WatchCommentsStatus.Ready
                    }, commentsState.total, com.hhst.youtubelite.watch.domain.WatchComment(
                        first?.author?.displayName.orEmpty(), first?.body.orEmpty(), first?.author?.artwork.orEmpty()))
                }
            }
        }
        setAppContent {
            val pip by inPip.collectAsStateWithLifecycle()
            val playerViewModel: PlayerViewModel = koinInject()
            val livePlayerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val playerState = livePlayerState.withWatchPresentation(miniHandoffState)
            val state by watchViewModel.uiState.collectAsStateWithLifecycle()
            com.hhst.youtubelite.ui.design.WriteProgressOverlay(watchViewModel.writes)
            val fullscreen = playerState.fullscreen || pip
            val windowBackground = if (fullscreen) androidx.compose.ui.graphics.Color.Black
                else com.hhst.youtubelite.ui.design.DesignTokens.Background
            // Android can expose the window while a fullscreen/PiP resize is
            // between content draws. Keep that backing color in the same mode.
            SideEffect {
                applyWatchWindowBackground(windowBackground.toArgb())
                applySystemBarStyle()
            }
            val commentsState by commentsViewModel.uiState.collectAsStateWithLifecycle()
            val commentsListState = rememberSaveable(commentsState.videoId, commentsState.sort, saver = LazyListState.Saver) { LazyListState() }
            val commentsPosition = remember(commentsListState) {
                commentsListState.firstVisibleItemIndex to commentsListState.firstVisibleItemScrollOffset
            }
            LaunchedEffect(commentsListState) {
                if (commentsPosition.first > 0 || commentsPosition.second > 0) {
                    snapshotFlow { commentsListState.layoutInfo.totalItemsCount }.first { it > 0 }
                    repeat(2) { withFrameNanos {} }
                    val itemCount = commentsListState.layoutInfo.totalItemsCount
                    if (itemCount > 0) commentsListState.scrollToItem(
                        commentsPosition.first.coerceAtMost(itemCount - 1), commentsPosition.second,
                    )
                }
            }
            LaunchedEffect(fullscreen, state.commentsOpen) {
                if (fullscreen && state.commentsOpen) {
                    commentsViewModel.onAction(CommentAction.CloseMenu)
                    watchViewModel.onAction(WatchAction.CloseComments)
                }
            }
            LaunchedEffect(fullscreen, state.overlay) {
                if (fullscreen && (state.overlay is WatchOverlay.ItemMenu || state.overlay is WatchOverlay.Save ||
                        state.overlay is WatchOverlay.Feedback)) {
                    watchViewModel.onAction(WatchAction.CloseOverlay)
                }
            }
            val closeComments = {
                commentsViewModel.onAction(CommentAction.CloseMenu)
                watchViewModel.onAction(WatchAction.CloseComments)
            }
            val back: () -> Unit = {
                when {
                    state.overlay is WatchOverlay.ItemMenu || state.overlay is WatchOverlay.Save ||
                        state.overlay is WatchOverlay.Feedback ->
                        watchViewModel.onAction(WatchAction.CloseOverlay)
                    state.searchOpen -> watchViewModel.onAction(WatchAction.CloseSearch)
                    state.commentsOpen && !fullscreen && commentsState.overlay != CommentsOverlay.None ->
                        commentsViewModel.onAction(CommentAction.CloseMenu)
                    state.commentsOpen && !fullscreen -> closeComments()
                    else -> when (PlayerUi.nextBackStep(playerState.locked, fullscreen)) {
                        PlayerUi.BackStep.Unlock -> playerViewModel.setLocked(false)
                        PlayerUi.BackStep.ExitFullscreen -> playerViewModel.setFullscreen(false)
                        PlayerUi.BackStep.BrowserBack -> finish()
                    }
                }
            }
            BackHandler(onBack = back)
            var revealingSource by remember { mutableStateOf(false) }
            var miniDragProgress by remember { mutableFloatStateOf(0f) }
            val contentFullscreenSwipe = remember { com.hhst.youtubelite.player.surface.FullscreenSwipeHandle() }
            val contentPull = com.hhst.youtubelite.watch.presentation.watchFullscreenPull(
                contentFullscreenSwipe, enabled = !fullscreen && !pip && !playerState.mini && !playerState.locked &&
                    !state.searchOpen && !state.commentsOpen && state.overlay == WatchOverlay.None,
                playerBottomPx = { sheetPlayerBottomPx },
            )
            var fullscreenProgress by remember { mutableFloatStateOf(if (fullscreen) 1f else 0f) }
            val entryBounds = remember(transitionVersion) {
                com.hhst.youtubelite.navigation.WatchTransition.origin(intent)
                    .takeIf { savedInstanceState == null || transitionVersion > 0 }
            }
            val entryProgress = remember(transitionVersion) { mutableFloatStateOf(if (entryBounds == null) 1f else 0f) }
            val playerDrawn = remember(transitionVersion) { mutableStateOf(!waitForEntryDraw) }
            val miniRestoreFrame = remember(transitionVersion) {
                com.hhst.youtubelite.navigation.WatchTransition.miniRestoreFrame(intent)
            }
            val miniPicturePaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
            val miniBridgeHost = LocalView.current
            val entering by remember(entryProgress, playerDrawn) {
                androidx.compose.runtime.derivedStateOf { !playerDrawn.value || entryProgress.floatValue < 1f }
            }
            val pageProgress = {
                watchPageProgress(if (playerDrawn.value) entryProgress.floatValue else 0f,
                    if (revealingSource) miniDragProgress else 0f)
            }
            val sourceImage = remember(transitionVersion) { entryBackdrop?.asImageBitmap() }
            Box(Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)) {
                if ((revealingSource || entering) && sourceImage != null) androidx.compose.foundation.Image(
                    sourceImage, contentDescription = null, modifier = Modifier.fillMaxSize().testTag("watch-mini-backdrop"),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                // The first root draw can precede the player's slot/layout. The
                // backdrop omits the mini, so bridge that gap at its source geometry.
                // Keep this local to the entrance; a later reverse reveal must not
                // bring back an old mini at its original position.
                if (!playerDrawn.value && miniRestoreFrame != null &&
                    miniRestoreFrame.frame.videoId == playerState.videoId && !miniRestoreFrame.frame.image.isRecycled) Canvas(
                    Modifier.fillMaxSize().testTag("watch-mini-entry-bridge"),
                ) {
                    val bounds = miniRestoreFrame.bounds.run { android.graphics.RectF(left, top, right, bottom) }
                    val video = miniRestoreFrame.videoBounds.run { android.graphics.RectF(left, top, right, bottom) }
                    val radius = com.hhst.youtubelite.player.surface.MiniPlayerLayout.CORNER_DP * density
                    val clip = android.graphics.Path().apply {
                        addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW)
                    }
                    val canvas = drawContext.canvas.nativeCanvas
                    val saved = canvas.save()
                    val position = IntArray(2).also(miniBridgeHost::getLocationInWindow)
                    canvas.translate(-position[0].toFloat(), -position[1].toFloat())
                    canvas.clipPath(clip)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    canvas.drawBitmap(miniRestoreFrame.frame.image, null, video, miniPicturePaint)
                    canvas.restoreToCount(saved)
                }
                // Only the picture morphs. The backdrop and labels follow its clock,
                // without growing a second, page-sized rectangle from the card.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageProgress() }
                    .background(com.hhst.youtubelite.ui.design.DesignTokens.Background))
                // The page reports its player slot geometry; the real player
                // lives in one stable overlay host that interpolates between
                // that slot and fullscreen, so a mode switch keeps the
                // composition, the SurfaceView and the prepared media.
                val playerSlotBounds = remember { mutableStateOf<Rect?>(null) }
                val panelsCover = state.searchOpen || state.commentsOpen ||
                    state.overlay is WatchOverlay.ItemMenu || state.overlay is WatchOverlay.Save ||
                    state.overlay is WatchOverlay.Feedback
                BoxWithConstraints(Modifier.fillMaxSize()
                    .watchPageContent { pageProgress() * (1f - fullscreenProgress) }
                    .then(if (revealingSource) Modifier.clearAndSetSemantics {} else Modifier)
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                    val hideWatchControls = state.searchOpen || state.overlay is WatchOverlay.ItemMenu ||
                        state.overlay is WatchOverlay.Save || state.overlay is WatchOverlay.Feedback ||
                        (state.commentsOpen && !fullscreen && !WatchLayout.sidebar(maxWidth.value, maxHeight.value))
                    WatchScreen(
                        state = state,
                        onAction = watchViewModel::onAction,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        modifier = Modifier.fillMaxSize().then(contentPull).then(
                            if (hideWatchControls) Modifier.clearAndSetSemantics {} else Modifier,
                        ),
                        fullscreen = fullscreen,
                        menuContent = {},
                        playerSlot = { slotModifier ->
                            Box(slotModifier.onGloballyPositioned { playerSlotBounds.value = it.boundsInRoot() })
                        },
                        sidebarCovered = state.commentsOpen && !fullscreen && !state.searchOpen,
                        drawBackground = false,
                        keepPlayerSlotInFullscreen = true,
                    )
                }
                val miniStore: com.hhst.youtubelite.player.surface.MiniPlayerStore = koinInject()
                val hostDensity = androidx.compose.ui.platform.LocalDensity.current
                // The browse host docks inside safeDrawing above its 56 dp
                // navigation area. Match both safe edges before handing over,
                // including a saved mini placement near the top of the window.
                val miniTopInset = with(hostDensity) {
                    WindowInsets.safeDrawing.getTop(this).toDp().value.roundToInt()
                }
                val miniBottomInset = with(hostDensity) {
                    56 + WindowInsets.safeDrawing.getBottom(this).toDp().value.toInt()
                }
                val slot = playerSlotBounds.value
                com.hhst.youtubelite.player.surface.MiniPlayerWindow(
                    screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp,
                    bottomInsetDp = miniBottomInset,
                    topInsetDp = miniTopInset,
                    store = miniStore,
                    showStroke = playerState.controlsVisible,
                    onBackgroundTap = playerViewModel::onToggleControls,
                    mini = playerState.mini && !pip && (!miniHandoffPending || localMiniCollapse),
                    fillsWindow = fullscreen,
                    fullscreenSwipeEnabled = !pip,
                    fullscreenSwipeHandle = contentFullscreenSwipe,
                    embeddedLeftDp = slot?.let { with(hostDensity) { it.left.toDp().value.toInt() } } ?: 0,
                    embeddedTopDp = slot?.let { with(hostDensity) { it.top.toDp().value.toInt() } } ?: 0,
                    embeddedWidthDp = slot?.let { with(hostDensity) { it.width.toDp().value.toInt() } } ?: 0,
                    embeddedHeightDp = slot?.let { with(hostDensity) { it.height.toDp().value.toInt() } } ?: 0,
                    onFullscreenSwipe = { target ->
                        if (target) {
                            if (!playerViewModel.uiState.value.fullscreen) playerViewModel.onFullscreenToggle()
                        } else playerViewModel.onFullscreenSwipeExit()
                    },
                    onContentFullscreenPull = playerViewModel::setFullscreenManual,
                    onFullscreenSwipeRestore = playerViewModel::onFullscreenSwipeRestore,
                    onMiniSwipe = playerViewModel::onMiniSwipeRequested,
                    onBoundsChanged = {
                        playerWindowBounds = it
                        sheetPlayerBottomPx = if (fullscreen || playerState.mini || pip) 0 else it.bottom.toInt()
                    },
                    onMiniDragChanged = { revealingSource = it },
                    onMiniDragProgress = { miniDragProgress = it },
                    onMiniSettled = { bounds ->
                        afterMiniCollapse?.also { afterMiniCollapse = null }?.invoke(bounds)
                    },
                    onMiniSwipeFinished = {
                        playerViewModel.prepareWindowHandoff {
                            if (!isFinishing && playerViewModel.uiState.value.mini) {
                                finish()
                                @Suppress("DEPRECATION")
                                overridePendingTransition(0, 0)
                            }
                        }
                    },
                    initialBounds = entryBounds,
                    entryKey = transitionVersion,
                    entryAnimationReady = watchWindowFocused,
                    onEntryProgress = { entryProgress.floatValue = it },
                    onFullscreenProgress = { fullscreenProgress = it },
                    // Entrances without a thumbnail have no travel clock.
                    // Reveal once their real player bounds have drawn instead
                    // of exposing a page with a temporary zero-height slot.
                    onReadyForDisplay = { playerDrawn.value = true },
                    onDismiss = playerViewModel::onMiniClose,
                    modifier = Modifier.then(if (panelsCover) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    WatchPlayer(Modifier.fillMaxSize(), { onBackPressedDispatcher.onBackPressed() }, pip, presentationState = miniHandoffState,
                        chromeAlpha = { watchPageAlpha(pageProgress()) })
                }
                if (state.searchOpen) Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    val query = state.draftQuery.trim()
                    val rows = com.hhst.youtubelite.ui.components.rememberSearchRows(if (fixtures) null else sources.suggestions, query)
                    SearchPanel(
                        query = state.draftQuery,
                        suggestions = rows?.suggestions ?: if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live"),
                        history = rows?.history.orEmpty(),
                        onRemoveHistory = rows?.onRemove,
                        onQueryChanged = { watchViewModel.onAction(WatchAction.QueryChanged(it)) },
                        onSubmit = { watchViewModel.onAction(WatchAction.SubmitSearch(it)); rows?.onSubmit?.invoke(it) },
                        onClose = { watchViewModel.onAction(WatchAction.CloseSearch) },
                        onVoiceSearch = { watchViewModel.onAction(WatchAction.VoiceSearch) },
                    )
                }
                if (state.commentsOpen && !fullscreen && !state.searchOpen) {
                    val chatTarget = com.hhst.youtubelite.watch.chat.liveChatTarget(
                        (state.content as? WatchContent.Ready)?.page?.video, playerState.isLive,
                        commentsOpen = true, searchOpen = false, fullscreen = false, pip = false,
                    )
                    if (chatTarget != null) {
                        val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding()
                            .testTag("live-chat-panel-host")) {
                            com.hhst.youtubelite.watch.chat.LiveChatPanel(
                                state = chatState,
                                onAction = chatViewModel::onAction,
                                onClose = closeComments,
                            )
                        }
                    } else {
                        val commentCount = when (commentsState.content) {
                            CommentsContent.Empty -> "0"
                            CommentsContent.Disabled -> ""
                            else -> commentsState.total.ifBlank { (state.content as? WatchContent.Ready)?.page?.video?.commentCount.orEmpty() }
                        }
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                            .then(if (state.overlay is WatchOverlay.ItemMenu) Modifier.clearAndSetSemantics {} else Modifier)
                            .testTag("comments-panel-host")) {
                            CommentsPanel(
                                state = commentsState,
                                commentCount = commentCount,
                                onAction = commentsViewModel::onAction,
                                onClose = closeComments,
                                listState = commentsListState,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    WatchVideoMenu(state, watchViewModel::onAction)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prepared = com.hhst.youtubelite.navigation.WatchTransition.hasPreparedBackdrop(intent)
        if (prepared) entryBackdrop = com.hhst.youtubelite.navigation.WatchTransition.backdrop(intent)
        // Selecting a recommendation reuses this already visible host. Keep
        // its browse destination without replaying the browse entrance.
        waitForEntryDraw = prepared
        transitionVersion++
        val id = intent.getStringExtra(EXTRA_VIDEO_ID) ?: playerViewModel.uiState.value.videoId ?: return
        val provisional = com.hhst.youtubelite.watch.domain.WatchProvisionalStore.consume(id)
        watchViewModel.selectVideo(id, provisional)
        commentsViewModel.selectVideo(id)
        if (!fixtures) playerViewModel.openVideo(id, intent.startSecondsExtra())
    }

    override fun onResume() {
        super.onResume()
        miniHandoffPending = false
        localMiniCollapse = false
        afterMiniCollapse = null
        miniHandoffState = null
        val playback = playerViewModel.uiState.value
        if (!fixtures || playback.visible && playback.mini && playback.videoId == watchViewModel.uiState.value.videoId)
            playerViewModel.openVideo(watchViewModel.uiState.value.videoId)
        playerViewModel.refreshPipAvailability()
        val prefs = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extension.ExtensionManager>()
        watchViewModel.refreshVoteCounts(prefs.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_SHOW_LIKES),
            prefs.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_DISPLAY_DISLIKES))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        watchWindowFocused = hasFocus
    }

    private fun applyWatchWindowBackground(color: Int) {
        if (appliedWindowColor == color) return
        appliedWindowColor = color
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
    }

    @Suppress("DEPRECATION")
    override fun applySystemBarStyle() {
        val fullscreen = playerViewModel.uiState.value.withWatchPresentation(miniHandoffState).fullscreen || inPip.value
        if (!fullscreen) {
            super.applySystemBarStyle()
            return
        }
        // Native resume/appearance callbacks and presentation changes share
        // this entry, so a temporarily visible fullscreen/PiP bar stays black.
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
    }

    override fun finish() {
        handoffToMini(collapseBeforeNavigation = true) { prepared ->
            super.finish()
            if (prepared) {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        val target = intent.component?.takeIf { it.packageName == packageName }?.className
        val browse = target != null && runCatching {
            com.hhst.youtubelite.ui.ContentActivity::class.java.isAssignableFrom(
                Class.forName(target, false, classLoader))
        }.getOrDefault(false)
        if (browse) handoffToMini { prepared ->
            super.startActivity(intent, if (prepared) android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle() else options)
        } else super.startActivity(intent, options)
    }

    /** Back keeps the live output through its collapse; new pages own their entry. */
    private fun handoffToMini(collapseBeforeNavigation: Boolean = false, navigate: (Boolean) -> Unit) {
        if (miniHandoffPending) return
        val state = playerViewModel.uiState.value
        val preferences = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extension.ExtensionManager>()
        val bounds = playerWindowBounds
        if (!state.visible || state.mini || playerViewModel.currentMediaKind != com.hhst.youtubelite.player.MediaKind.WATCH ||
            inPip.value || bounds == null ||
            !preferences.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) {
            navigate(false)
            return
        }
        miniHandoffPending = true
        if (collapseBeforeNavigation) {
            localMiniCollapse = true
            afterMiniCollapse = { settledBounds ->
                val settled = playerViewModel.uiState.value
                if (settled.videoId != state.videoId || !settled.visible || !settled.mini) {
                    miniHandoffPending = false
                    localMiniCollapse = false
                } else {
                    // Keep decoding into the original surface for the entire
                    // visible collapse. The cross-window snapshot is only
                    // needed once the picture has reached its mini placement.
                    miniHandoffState = settled
                    playerViewModel.prepareWindowHandoff {
                        val current = playerViewModel.uiState.value
                        if (!isDestroyed && !isFinishing && localMiniCollapse &&
                            current.videoId == state.videoId && current.visible && current.mini) {
                            com.hhst.youtubelite.navigation.WatchTransition.miniBounds = settledBounds
                            navigate(true)
                        } else if (!isDestroyed && !isFinishing) {
                            miniHandoffPending = false
                            localMiniCollapse = false
                            miniHandoffState = null
                        }
                    }
                }
            }
            playerViewModel.enterMiniPlayer()
            return
        }
        miniHandoffState = state
        playerViewModel.prepareWindowHandoff {
            if (isDestroyed || isFinishing) return@prepareWindowHandoff
            val current = playerViewModel.uiState.value
            if (current.videoId != state.videoId) {
                miniHandoffPending = false
                miniHandoffState = null
                return@prepareWindowHandoff
            }
            val prepared = current.visible && !current.mini
            if (prepared) {
                // Back can arrive while entry is still expanding. PixelCopy
                // completes asynchronously, so hand off the current rectangle.
                com.hhst.youtubelite.navigation.WatchTransition.beginMiniEntry(playerWindowBounds ?: bounds)
                playerViewModel.enterMiniPlayer()
            }
            navigate(prepared)
        }
    }

    /**
     * Routes a description link: same-video links seek the existing player instead of
     * rebuilding the page, YouTube links go through the app's content-link boundary,
     * anything else opens an external browser. [com.hhst.youtubelite.navigation.ContentLink.parse]
     * already rejected lookalike hosts, credentials, and non-https schemes.
     */
    private fun openDescriptionLink(url: String) {
        val link = com.hhst.youtubelite.navigation.ContentLink.parse(url)
        val currentId = playerViewModel.uiState.value.videoId
        if (link is com.hhst.youtubelite.navigation.ContentLink.Video && link.id == currentId) {
            if (playerViewModel.uiState.value.isLive != true) playerViewModel.onSeek((link.seconds ?: 0L) * 1000)
            return
        }
        if (link != null && openContentLink(url)) return
        if (link == null && android.net.Uri.parse(url).scheme?.lowercase() in setOf("http", "https")) {
            runCatching {
                val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val browser = packageManager.queryIntentActivities(probe, 0).firstOrNull {
                    it.activityInfo.packageName != packageName
                }?.activityInfo?.packageName ?: error("No browser available")
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
            }.onFailure { renderNotice(com.hhst.youtubelite.core.AppNotice.Message("Couldn't open this link.")) }
        }
    }

    companion object {
        const val EXTRA_START_SECONDS = "watch_start_seconds"
        const val EXTRA_VIDEO_ID = "watch_video_id"
        const val EXTRA_SCENARIO = "watch_scenario"
        const val EXTRA_COMMENTS_SCENARIO = "watch_comments_scenario"
        const val EXTRA_OPEN_COMMENTS = "watch_open_comments"
        const val EXTRA_CHAT_KEY = "watch_chat_key"

        fun intent(context: Context, videoId: String, scenario: WatchScenario? = null) =
            Intent(context, WatchActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }

        /** Explicit start seconds from the launch intent: `hasExtra` separates "begin
            at 0" from "unspecified" (progress resume). Negative values are ignored. */
        fun Intent.startSecondsExtra(): Long? =
            if (hasExtra(EXTRA_START_SECONDS)) getLongExtra(EXTRA_START_SECONDS, 0).takeIf { it >= 0 } else null

        /** Debug-only entry used by instrumentation tests to pin panel states. */
        fun commentsIntent(context: Context, videoId: String, comments: CommentsScenario, open: Boolean = true) =
            intent(context, videoId)
                .putExtra(EXTRA_COMMENTS_SCENARIO, comments.argument)
                .putExtra(EXTRA_OPEN_COMMENTS, open)
    }
}
