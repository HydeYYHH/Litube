package com.hhst.youtubelite.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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

/** Hosts the local results page and observes its navigation requests. */
class SearchResultsActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var searchViewModel: SearchResultsViewModel
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    var lastNavigationRequest: SearchNavigationRequest? = null
        private set

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
                    if (fixtures) null else sources.videoActions, if (fixtures) null else sources.saves) as T
            }
        })[SearchResultsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    if (request is SearchNavigationRequest.Destination) {
                        com.hhst.youtubelite.ui.components.MainTabs.intent(this@SearchResultsActivity,
                            com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name),
                            searchViewModel.uiState.value.signedInPreview)?.let { startActivity(it); finish() }
                    }
                    if (request is SearchNavigationRequest.Watch) {
                        startActivity(WatchActivity.intent(this@SearchResultsActivity, request.videoId))
                    }
                    if (request is SearchNavigationRequest.Shorts) {
                        startActivity(com.hhst.youtubelite.shorts.ShortsActivity.intent(this@SearchResultsActivity, request.itemId))
                    }
                    if (request is SearchNavigationRequest.Channel) {
                        startActivity(ChannelActivity.intent(this@SearchResultsActivity, request.channelId))
                    }
                    if (request is SearchNavigationRequest.VoiceSearch && !fixtures) openVoiceSearch()
                    if (request is SearchNavigationRequest.Share) shareGateway.share(request.videoId)
                    if (request is SearchNavigationRequest.Playlist) startActivity(com.hhst.youtubelite.playlist.PlaylistActivity.intent(this@SearchResultsActivity, request.playlistId))
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
        setContent {
            val state by searchViewModel.uiState.collectAsStateWithLifecycle()
            val back: () -> Unit = {
                if (searchViewModel.saveSheet?.state?.value != null) searchViewModel.saveSheet?.close()
                else if (state.overlay is SearchOverlay.ItemMenu || state.overlay is SearchOverlay.PlaylistMenu) searchViewModel.onAction(SearchAction.CloseItemMenu)
                else if (state.searchOpen) searchViewModel.onAction(SearchAction.CloseSearch)
                else if (state.filtersOpen) searchViewModel.onAction(SearchAction.CloseFilters)
                else finish()
            }
            BackHandler(onBack = back)
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                SearchResultsScreen(
                    state, searchViewModel::onAction, onBack = back,
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                    searchRows = com.hhst.youtubelite.ui.components.rememberSearchRows(if (fixtures || !state.searchOpen) null else sources.suggestions, state.draftQuery.trim()),
                )
                searchViewModel.saveSheet?.let { sheet ->
                    val sheetState by sheet.state.collectAsStateWithLifecycle()
                    sheetState?.let { current ->
                        if (current.newPlaylist) com.hhst.youtubelite.saves.presentation.NewPlaylistSheet(
                            draft = com.hhst.youtubelite.saves.domain.NewPlaylistDraft(current.title, current.description),
                            onDismiss = sheet::close,
                            onDraftChange = { sheet.draftChanged(it.title, it.description) },
                            onSubmit = sheet::submitNewPlaylist,
                        ) else com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet(
                            playlists = emptyList(),
                            onDismiss = sheet::close,
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
