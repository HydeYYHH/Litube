package com.hhst.youtubelite.search

import com.hhst.youtubelite.ui.observeAccountChanges

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.data.SearchScenario
import com.hhst.youtubelite.search.domain.SearchAction
import com.hhst.youtubelite.search.domain.SearchDestination
import com.hhst.youtubelite.search.domain.SearchNavigationRequest
import com.hhst.youtubelite.search.domain.SearchOverlay
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch
import com.hhst.youtubelite.ui.renderNotice

class SearchResultsActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var searchViewModel: SearchResultsViewModel
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    var lastNavigationRequest: SearchNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the banner; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) searchViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val scenario = if (BuildConfig.DEBUG) SearchScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else SearchScenario.Results
        searchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == SearchResultsViewModel::class.java)
                return SearchResultsViewModel(if (fixtures) FixtureSearchRepository(scenario) else sources.search, extras.createSavedStateHandle(), query, scenario, if (fixtures) null else com.hhst.youtubelite.data.media.MediaSession.signedIn(),
                    if (fixtures) null else sources.videoActions, if (fixtures) null else sources.saves, if (fixtures) null else sources.avatars) as T
            }
        })[SearchResultsViewModel::class.java]
        if (!fixtures) observeAccountChanges(::syncSession)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    if (request is SearchNavigationRequest.Destination) {
                        com.hhst.youtubelite.ui.components.MainTabs.intent(this@SearchResultsActivity,
                            com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name),
                            searchViewModel.uiState.value.signedInPreview)?.let { launchNavigation(it); finish() }
                    }
                    if (request is SearchNavigationRequest.Watch) {
                        launchNavigation(WatchActivity.intent(this@SearchResultsActivity, request.videoId))
                    }
                    if (request is SearchNavigationRequest.Shorts) {
                        launchNavigation(com.hhst.youtubelite.shorts.ShortsActivity.intent(this@SearchResultsActivity, request.itemId))
                    }
                    if (request is SearchNavigationRequest.Channel) {
                        launchNavigation(ChannelActivity.intent(this@SearchResultsActivity, request.channelId))
                    }
                    if (request is SearchNavigationRequest.OpenVerification) verificationLauncher.launch(
                        android.content.Intent(this@SearchResultsActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                            .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                    if (request is SearchNavigationRequest.VoiceSearch && !fixtures) openVoiceSearch()
                    if (request is SearchNavigationRequest.Share) shareGateway.share(request.videoId)
                    if (request is SearchNavigationRequest.MenuAction && !fixtures) {
                        val kind = when (request.action) {
                            com.hhst.youtubelite.search.domain.SearchVideoMenuAction.Queue -> com.hhst.youtubelite.ui.MediaActionKind.Queue
                            com.hhst.youtubelite.search.domain.SearchVideoMenuAction.Download -> com.hhst.youtubelite.ui.MediaActionKind.Download
                            else -> null
                        }
                        val video = (searchViewModel.uiState.value.content as? com.hhst.youtubelite.search.domain.SearchContent.Results)?.page?.results
                            ?.firstOrNull { it.id == request.videoId }
                        if (kind != null) com.hhst.youtubelite.ui.ActivityMediaActionExecutor(this@SearchResultsActivity).execute(
                            com.hhst.youtubelite.ui.MediaActionItem(request.videoId, video?.title.orEmpty(), video?.channel.orEmpty(), video?.artwork.orEmpty()), kind)
                    }
                    if (request is SearchNavigationRequest.Playlist) launchNavigation(com.hhst.youtubelite.playlist.PlaylistActivity.intent(this@SearchResultsActivity, request.playlistId))
                    if (request is SearchNavigationRequest.SharePlaylist) shareGateway.sharePlaylist(request.playlistId)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        setAppContent {
            val state by searchViewModel.uiState.collectAsStateWithLifecycle()
            com.hhst.youtubelite.ui.design.WriteProgressOverlay(searchViewModel.writes)
            searchViewModel.saveSheet?.let { com.hhst.youtubelite.ui.design.WriteProgressOverlay(it.writes) }
            val back: () -> Unit = {
                if (searchViewModel.saveSheet?.state?.value != null) searchViewModel.saveSheet?.dismiss()
                else if (state.overlay is SearchOverlay.ItemMenu || state.overlay is SearchOverlay.PlaylistMenu) searchViewModel.onAction(SearchAction.CloseItemMenu)
                else if (state.searchOpen) searchViewModel.onAction(SearchAction.CloseSearch)
                else if (state.filtersOpen) searchViewModel.onAction(SearchAction.CloseFilters)
                else finish()
            }
            BackHandler(onBack = back)
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                SearchResultsScreen(
                    state, searchViewModel::onAction, onBack = back,
                    modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                    searchRows = com.hhst.youtubelite.ui.components.rememberSearchRows(if (fixtures || !state.searchOpen) null else sources.suggestions, state.draftQuery.trim()),
                )
                searchViewModel.saveSheet?.let { sheet ->
                    val sheetState by sheet.state.collectAsStateWithLifecycle()
                    sheetState?.let { current ->
                        if (current.newPlaylist) com.hhst.youtubelite.saves.presentation.NewPlaylistSheet(
                            draft = com.hhst.youtubelite.saves.domain.NewPlaylistDraft(current.title, current.description),
                            onDismiss = sheet::dismiss,
                            onDraftChange = { sheet.draftChanged(it.title, it.description) },
                            onSubmit = sheet::submitNewPlaylist,
                            pending = current.pending,
                        ) else com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet(
                            playlists = emptyList(),
                            onDismiss = sheet::dismiss,
                            onSelect = {},
                            onNewPlaylist = sheet::openNewPlaylist,
                            live = com.hhst.youtubelite.saves.presentation.SaveSheetLive(
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_QUERY)?.let(searchViewModel::updateQuery)
    }

    override fun onResume() {
        super.onResume()
        syncSession()
    }

    private fun syncSession() {
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
            searchViewModel.updateSession(session.signedIn, session.generation)
        }
    }

    companion object {
        const val EXTRA_QUERY = "search_query"
        const val EXTRA_SCENARIO = "search_scenario"

        fun intent(context: Context, query: String, scenario: SearchScenario? = null) =
            Intent(context, SearchResultsActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
