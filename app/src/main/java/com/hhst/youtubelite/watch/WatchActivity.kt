package com.hhst.youtubelite.watch

import com.hhst.youtubelite.comments.presentation.bindCommentNotices
import com.hhst.youtubelite.navigation.openContentLink
import android.content.Context
import com.hhst.youtubelite.data.media.MediaSession
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color as ComposeColor
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
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
import com.hhst.youtubelite.watch.presentation.WatchScreen
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

class WatchActivity : com.hhst.youtubelite.player.PlaybackActivity() {
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

    /** Test seams: recorded payloads plus launch suppression for instrumentation runs. */
    var lastDownloadSpec: DownloadSingleSpec? = null
        private set
    var lastQueuedItem: QueueItem? = null
        private set
    var launchDownload: Boolean = true
    private var fixtures = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: playerViewModel.uiState.value.videoId.orEmpty()
        val scenario = if (BuildConfig.DEBUG) WatchScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else WatchScenario.Content
        watchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == WatchViewModel::class.java)
                return WatchViewModel(if (fixtures) FixtureWatchRepository(scenario) else sources.watch, extras.createSavedStateHandle(), videoId, scenario, if (fixtures) null else sources.votes, if (fixtures) null else sources.videoActions, if (fixtures) null else sources.saves) as T
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
                return CommentsViewModel(if (fixtures) FixtureCommentsRepository(commentsScenario) else sources.comments(), extras.createSavedStateHandle(), videoId) as T
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
                    if (fixtures) null else com.hhst.youtubelite.watch.chat.MediaLiveChatGateway(sources.client),
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
                try {
                    kotlinx.coroutines.flow.combine(watchViewModel.uiState, playerViewModel.uiState,
                        com.hhst.youtubelite.data.media.MediaSession.changes, inPip) { watch, player, _, pip ->
                        val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
                        chatViewModel.updateSession(fixtures || session.signedIn, session.generation)
                        (watch.content as? WatchContent.Ready)?.page?.video?.takeIf {
                            watch.commentsOpen && !watch.searchOpen && !player.fullscreen && !pip && it.live
                        }?.chatKey
                    }.distinctUntilChanged().collect { key ->
                        if (key == null) chatViewModel.close() else chatViewModel.open(key)
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
                            .putExtra(EXTRA_START_SECONDS, request.startSeconds ?: 0L))
                        is WatchNavigationRequest.SeekTo -> playerViewModel.onSeek(request.seconds * 1000)
                        is WatchNavigationRequest.OpenLink -> openDescriptionLink(request.url)
                        is WatchNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            Intent(this@WatchActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        WatchNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        is WatchNavigationRequest.Share -> shareGateway.share(request.videoId)
                        is WatchNavigationRequest.Download -> {
                            val video = (watchViewModel.uiState.value.content as? WatchContent.Ready)?.page?.video
                            // Fixture ids map onto their public stand-ins, the same way sharing resolves them.
                            val source = VideoShare.urlFor(request.videoId) ?: return@collect
                            val spec = DownloadEntries.single(
                                source,
                                video?.title.orEmpty(),
                                video?.channel,
                                null,
                            )
                            lastDownloadSpec = spec
                            if (spec != null && launchDownload) DownloadEntries.show(this@WatchActivity, spec)
                        }
                        is WatchNavigationRequest.Queue -> {
                            val video = (watchViewModel.uiState.value.content as? WatchContent.Ready)?.page?.video
                            val canonicalId = VideoShare.urlFor(request.videoId)?.substringAfterLast('/') ?: return@collect
                            val item = QueueItem(
                                videoId = canonicalId,
                                title = video?.title.orEmpty().ifBlank { request.videoId },
                                author = video?.channel,
                            )
                            lastQueuedItem = item
                            playerViewModel.onQueueAdd(item)
                            android.widget.Toast.makeText(
                                this@WatchActivity, getString(R.string.queue_item_added), android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        is WatchNavigationRequest.OpenWith -> shareGateway.openWith(request.videoId)
                        // Typed exits for fixture instrumentation and writes the WatchViewModel runs
                        // itself with server confirmation in production (votes, subscribe, saves);
                        // PageMenu/Comments mirror VM overlay state. No `else`: a new request type
                        // must fail to compile here.
                        WatchNavigationRequest.PageMenu, is WatchNavigationRequest.Comments,
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
                intent.getLongExtra(EXTRA_START_SECONDS, 0).takeIf { savedInstanceState == null && it > 0 })
            val watchTimeReporter = com.hhst.youtubelite.data.media.WatchTimeReporter(sources.client, lifecycleScope)
            suspend fun recordLocalHistory() {
                val playback = playerViewModel.uiState.value
                val video = (watchViewModel.uiState.value.content as? WatchContent.Ready)?.page?.video
                val generation = MediaSession.snapshot().generation
                if (playback.isPlaying && !MediaSession.signedIn() && !MediaSession.privateBrowsing() && video != null && video.id == playback.videoId) {
                    val entry = com.hhst.youtubelite.library.domain.HistoryEntry(video.id, video.id, video.title,
                        video.channel, video.channelId, video.artwork, "",
                        progress = if (playback.durationMs > 0) (playerViewModel.positionState.value.toFloat() / playback.durationMs).coerceIn(0f, 1f) else null,
                        section = "On this device")
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MediaSession.withAnonymousSession(generation) {
                                if (!MediaSession.privateBrowsing()) sources.localLibrary.record(entry)
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    } catch (error: Exception) {
                        com.hhst.youtubelite.diagnostics.AppLog.event(
                            com.hhst.youtubelite.diagnostics.AppLog.Category.STORAGE, "local_history.write_failed", failure = error)
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    kotlinx.coroutines.flow.combine(
                        playerViewModel.uiState.map { it.isPlaying to it.videoId }.distinctUntilChanged(),
                        watchViewModel.uiState.map { (it.content as? WatchContent.Ready)?.page?.video?.id }.distinctUntilChanged(),
                    ) { playback, video -> playback to video }.collect { recordLocalHistory() }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    watchViewModel.uiState.map { (it.content as? WatchContent.Ready)?.page?.video }
                        .distinctUntilChanged()
                        .collect { video ->
                            if (video != null) watchTimeReporter.watch(video.id, video.tracking) else watchTimeReporter.stop()
                        }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        kotlinx.coroutines.delay(5_000)
                        recordLocalHistory()
                        watchTimeReporter.tick(playerViewModel.positionState.value,
                            playerViewModel.uiState.value.durationMs, playerViewModel.uiState.value.isPlaying,
                            playerViewModel.uiState.value.videoId)
                    }
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
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val state by watchViewModel.uiState.collectAsStateWithLifecycle()
            val fullscreen = playerState.fullscreen || pip
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
            Box(Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val hideWatchControls = state.searchOpen || state.overlay is WatchOverlay.ItemMenu ||
                        state.overlay is WatchOverlay.Save || state.overlay is WatchOverlay.Feedback ||
                        (state.commentsOpen && !fullscreen && !WatchLayout.sidebar(maxWidth.value, maxHeight.value))
                    WatchScreen(
                        state = state,
                        onAction = watchViewModel::onAction,
                        onBack = back,
                        modifier = Modifier.fillMaxSize().then(
                            if (hideWatchControls) Modifier.clearAndSetSemantics {} else Modifier,
                        ),
                        fullscreen = fullscreen,
                        menuContent = {},
                        sidebarCovered = state.commentsOpen && !fullscreen && !state.searchOpen,
                    )
                }
                if (fullscreen) {
                    Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                        WatchPlayer(Modifier.fillMaxSize(), back, pip)
                    }
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
                    val chatVideo = (state.content as? WatchContent.Ready)?.page?.video
                        ?.takeIf { it.live && it.chatKey != null }
                    if (chatVideo != null) {
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
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
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
        val id = intent.getStringExtra(EXTRA_VIDEO_ID) ?: playerViewModel.uiState.value.videoId ?: return
        watchViewModel.selectVideo(id)
        commentsViewModel.selectVideo(id)
        if (!fixtures) playerViewModel.openVideo(id, intent.getLongExtra(EXTRA_START_SECONDS, 0).takeIf { it > 0 })
    }

    override fun onResume() {
        super.onResume()
        if (!fixtures) playerViewModel.openVideo(watchViewModel.uiState.value.videoId)
        playerViewModel.refreshPipAvailability()
        val prefs = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extension.ExtensionManager>()
        watchViewModel.refreshVoteCounts(prefs.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_SHOW_LIKES),
            prefs.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_DISPLAY_DISLIKES))
    }

    /**
     * Routes a description link: same-video links (with or without a timestamp)
     * seek the existing player instead of rebuilding the page, YouTube links go
     * through the app's content-link boundary, and anything else gets an explicit
     * external browser open. [com.hhst.youtubelite.navigation.ContentLink.parse]
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

        fun intent(context: Context, videoId: String, scenario: WatchScenario? = null) =
            Intent(context, WatchActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }

        /** Debug-only entry used by instrumentation tests to pin panel states. */
        fun commentsIntent(context: Context, videoId: String, comments: CommentsScenario, open: Boolean = true) =
            intent(context, videoId)
                .putExtra(EXTRA_COMMENTS_SCENARIO, comments.argument)
                .putExtra(EXTRA_OPEN_COMMENTS, open)
    }
}
