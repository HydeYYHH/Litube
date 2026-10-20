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
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

/** Hosts the local results page and observes its navigation requests. */
class SearchResultsActivity : ComponentActivity() {
    lateinit var searchViewModel: SearchResultsViewModel
        private set
    var lastNavigationRequest: SearchNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val scenario = if (BuildConfig.DEBUG) SearchScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else SearchScenario.Results
        searchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == SearchResultsViewModel::class.java)
                return SearchResultsViewModel(FixtureSearchRepository(scenario), extras.createSavedStateHandle(), query, scenario) as T
            }
        })[SearchResultsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    if (request == SearchNavigationRequest.Destination(SearchDestination.Home)) finish()
                    if (request is SearchNavigationRequest.Watch) {
                        startActivity(WatchActivity.intent(this@SearchResultsActivity, request.videoId))
                    }
                    if (request is SearchNavigationRequest.Channel) {
                        startActivity(ChannelActivity.intent(this@SearchResultsActivity, request.channelId))
                    }
                }
            }
        }
        setContent {
            val state by searchViewModel.uiState.collectAsStateWithLifecycle()
            val back = {
                if (state.searchOpen) searchViewModel.onAction(SearchAction.CloseSearch) else finish()
            }
            BackHandler(onBack = back)
            SearchResultsScreen(
                state, searchViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_QUERY)?.let(searchViewModel::updateQuery)
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
