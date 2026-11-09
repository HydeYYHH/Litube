package com.hhst.youtubelite.home

import com.hhst.youtubelite.ui.observeAccountChanges

import com.hhst.youtubelite.navigation.openContentLink
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.downloader.core.DownloadShareParser
import com.hhst.youtubelite.home.data.FixtureHomeRepository
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import com.hhst.youtubelite.home.presentation.HomeViewModel
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.post.PostDetailActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import com.hhst.youtubelite.ui.renderNotice

class HomeActivity : com.hhst.youtubelite.ui.ContentActivity() {
    private val postCards = com.hhst.youtubelite.post.PostCardActionHost(this)
    private var deferUntilReturn = false
    private val homeReady = CompletableDeferred<HomeViewModel>()
    private var warmedCandidate: Pair<Long, String>? = null

    private fun prewarmVideo(id: String) {
        val candidate = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation to id
        if (warmedCandidate == candidate) return
        warmedCandidate = candidate
        org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extractor.Extractor>().warm(id)
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.hhst.youtubelite.shorts.data.ShortsMediaPreloader>().preload(id)
    }

    lateinit var homeViewModel: HomeViewModel
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    var lastNavigationRequest: HomeNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the banner; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) homeViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            postCards.bind()
            observeAccountChanges {
                if (::homeViewModel.isInitialized) refreshHomeSession()
            }
        }
        deferUntilReturn = savedInstanceState == null && intent.getBooleanExtra(EXTRA_DEFER_HOME, false)
        if (!deferUntilReturn) initializeHome(savedInstanceState)
        // Register once per Activity, including a synthetic parent whose Home
        // ViewModel is created only after its playback child exits.
        lifecycleScope.launch {
            val model = homeReady.await()
            val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.actionNotices.collect { renderNotice(it) } }
                launch { model.navigationRequests.collect { handleNavigation(it, fixtures) } }
            }
        }
    }

    // A synthetic parent installs this composition once on return; LaunchedEffect
    // owns/cancels its collectors, so subsequent resumes cannot register another.
    @android.annotation.SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun initializeHome(savedInstanceState: Bundle?) {
        if (::homeViewModel.isInitialized) return
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (fixtures) HomeScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else HomeScenario.Loading
        homeViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == HomeViewModel::class.java)
                return HomeViewModel(if (fixtures) FixtureHomeRepository(scenario) else sources.home, extras.createSavedStateHandle(), scenario, loadOnStart = !fixtures, if (fixtures) null else sources.saves, if (fixtures) null else sources.avatars,
                    if (fixtures) null else sources.snapshots,
                    { com.hhst.youtubelite.data.media.MediaSession.snapshot().accountId ?: "visitor" },
                    reportSignal = if (fixtures) null else {
                        { org.koin.java.KoinJavaComponent.getKoin()
                            .get<com.hhst.youtubelite.data.media.PlaybackWatchReporting>().anonymousReportSignal.value }
                    },
                    warm = if (fixtures) {
                        {}
                    } else {
                        // Browsing time pays for the tap: capture the session and warm the head
                        // bytes playback reads so the first frame does not wait on the network.
                        { id ->
                            prewarmVideo(id)
                        }
                    }) as T
            }
        })[HomeViewModel::class.java]
        // A deferred parent may have missed identity notifications before its VM existed.
        if (!fixtures) refreshHomeSession()
        if (fixtures && intent.hasExtra(EXTRA_SIGNED_IN)) homeViewModel.setSignedInPreview(intent.getBooleanExtra(EXTRA_SIGNED_IN, false))
        setAppContent {
            LaunchedEffect(fixtures, lifecycle) {
                if (fixtures) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    try {
                        homeViewModel.uiState.map { it.content is HomeContent.Feed && it.overlay == HomeOverlay.None }
                            .distinctUntilChanged().collectLatest { ready ->
                                if (!ready) return@collectLatest
                                val prefs = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extension.ExtensionManager>()
                                if (prefs.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.DATA_SAVER)) return@collectLatest
                                try {
                                    // Prepare the list while home settles; media bytes still wait for the
                                    // idle window. Navigation can claim the same read.
                                    val mediaAt = android.os.SystemClock.elapsedRealtime() + 1_500
                                    val page = sources.shortsEntry.prepare() ?: return@collectLatest
                                    kotlinx.coroutines.delay((mediaAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                                    page.items.firstOrNull()?.id?.let { id ->
                                        org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.shorts.data.ShortsMediaPreloader>().preload(id)
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Speculation must not fail home. */ }
                            }
                    } finally {
                        // Give the destination a short window to claim the same in-flight read.
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(300)
                            sources.shortsEntry.cancelUnclaimed()
                        }
                    }
                }
            }
            val state by homeViewModel.uiState.collectAsStateWithLifecycle()
            com.hhst.youtubelite.ui.design.WriteProgressOverlay(homeViewModel.writes)
            BackHandler {
                if (state.overlay != HomeOverlay.None) homeViewModel.onAction(HomeAction.CloseOverlay)
                else moveTaskToBack(true)
            }
            HomeScreen(state, homeViewModel::onAction, Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                searchRows = com.hhst.youtubelite.ui.components.rememberSearchRows(
                    if (fixtures || state.overlay != com.hhst.youtubelite.home.domain.HomeOverlay.Search) null else sources.suggestions,
                    state.query.trim()),
                onWarmCandidate = if (fixtures) null else ::prewarmVideo,
                postInteractions = if (fixtures) null else postCards.interactions())
        }
        if (savedInstanceState == null) receiveLink(intent)
        homeReady.complete(homeViewModel)
    }

    private fun handleNavigation(request: HomeNavigationRequest, fixtures: Boolean) {
        lastNavigationRequest = request
        when (request) {
            is HomeNavigationRequest.OpenLink -> openContentLink(request.url)
            HomeNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
            is HomeNavigationRequest.Search -> launchNavigation(SearchResultsActivity.intent(this, request.query))
            is HomeNavigationRequest.Watch -> launchNavigation(WatchActivity.intent(this, request.videoId))
            is HomeNavigationRequest.Shorts -> launchNavigation(
                com.hhst.youtubelite.shorts.ShortsActivity.intent(this, request.itemId))
            is HomeNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this, request.channelId))
            is HomeNavigationRequest.Playlist -> launchNavigation(
                com.hhst.youtubelite.playlist.PlaylistActivity.intent(this, request.playlistId))
            is HomeNavigationRequest.OpenPost -> launchNavigation(PostDetailActivity.intent(this, request.channelId, request.postId))
            is HomeNavigationRequest.MenuAction -> if (request.action == FeedMenuAction.Share) {
                shareGateway.share(request.itemId)
            } else if (!fixtures && request.action in setOf(FeedMenuAction.Queue, FeedMenuAction.Download)) {
                val feed = (homeViewModel.uiState.value.content as? HomeContent.Feed)?.page
                val video = feed?.items?.filterIsInstance<FeedItem.Video>()?.firstOrNull { it.id == request.itemId }
                com.hhst.youtubelite.ui.ActivityMediaActionExecutor(this).execute(
                    com.hhst.youtubelite.ui.MediaActionItem(request.itemId, video?.title.orEmpty(), video?.author.orEmpty(), video?.artwork.orEmpty()),
                    if (request.action == FeedMenuAction.Queue) com.hhst.youtubelite.ui.MediaActionKind.Queue else com.hhst.youtubelite.ui.MediaActionKind.Download)
            }
            is HomeNavigationRequest.PostFeedback -> if (!fixtures) postCards.rate(
                com.hhst.youtubelite.post.domain.PostIdentity(request.channelId, request.postId), request.like)
            is HomeNavigationRequest.OpenVerification -> verificationLauncher.launch(
                Intent(this, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                    .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
            is HomeNavigationRequest.Explore -> launchNavigation(com.hhst.youtubelite.explore.ExploreActivity.intent(this))
            is HomeNavigationRequest.Destination -> if (request.destination != HomeDestination.Home) {
                com.hhst.youtubelite.ui.components.MainTabs.intent(this,
                    com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name),
                    homeViewModel.uiState.value.signedInPreview)?.let(::startActivity)
            }
            // Save writes run in the ViewModel; fixture feedback/comments exits
            // stay recorded requests. New request types must be handled here.
            is HomeNavigationRequest.SaveToWatchLater, is HomeNavigationRequest.SaveToPlaylist,
            is HomeNavigationRequest.CreatePlaylist, is HomeNavigationRequest.Feedback,
            is HomeNavigationRequest.PostComments -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::homeViewModel.isInitialized) {
            if (deferUntilReturn) return
            initializeHome(null)
        }
        refreshHomeSession()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !deferUntilReturn && !::homeViewModel.isInitialized) {
            initializeHome(null)
            refreshHomeSession()
        }
    }

    override fun onStop() {
        super.onStop()
        // A synthetic parent can briefly gain focus during startActivities; only a
        // return from its playback child should initialize the home feed.
        deferUntilReturn = false
    }

    private fun refreshHomeSession() {
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
            homeViewModel.updateSession(session.signedIn, session.generation,
                com.hhst.youtubelite.data.media.MediaSession.selectedAccount()?.photo)
            // A watch-report signal or a stale feed triggers one quiet refresh.
            homeViewModel.onForeground()
        }
        val preferences = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.extension.ExtensionManager>()
        homeViewModel.setHideShorts(preferences.isEnabled(com.hhst.youtubelite.extension.PreferenceKeys.ENABLE_HIDE_SHORTS))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::homeViewModel.isInitialized) initializeHome(Bundle())
        if (com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent) && intent.hasExtra(EXTRA_SIGNED_IN)) homeViewModel.setSignedInPreview(intent.getBooleanExtra(EXTRA_SIGNED_IN, false))
        receiveLink(intent)
    }

    private fun receiveLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        val link = DownloadShareParser.extractUrl(intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        homeViewModel.onAction(HomeAction.Navigate(HomeNavigationRequest.OpenLink(link)))
    }

    companion object {
        const val EXTRA_SCENARIO = "home_scenario"
        const val EXTRA_SIGNED_IN = "signed_in_preview"
        const val EXTRA_DEFER_HOME = "defer_home_until_visible"
    }
}
