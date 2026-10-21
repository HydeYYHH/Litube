package com.hhst.youtubelite.home

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch

/** Hosts the home screen and observes its navigation requests. */
class HomeActivity : ComponentActivity() {
    lateinit var homeViewModel: HomeViewModel
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    var lastNavigationRequest: HomeNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) HomeScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else HomeScenario.Empty
        homeViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == HomeViewModel::class.java)
                return HomeViewModel(FixtureHomeRepository(scenario), extras.createSavedStateHandle(), scenario) as T
            }
        })[HomeViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    // The results and watch pages are the implemented destinations; everything else stays a request.
                    if (request is HomeNavigationRequest.Search) startActivity(SearchResultsActivity.intent(this@HomeActivity, request.query))
                    if (request is HomeNavigationRequest.Watch) startActivity(WatchActivity.intent(this@HomeActivity, request.videoId))
                    if (request is HomeNavigationRequest.Channel) startActivity(ChannelActivity.intent(this@HomeActivity, request.channelId))
                    if (request is HomeNavigationRequest.OpenPost) startActivity(
                        PostDetailActivity.intent(this@HomeActivity, request.channelId, request.postId)
                    )
                    if (request is HomeNavigationRequest.MenuAction && request.action == FeedMenuAction.Share) {
                        shareGateway.share(request.itemId)
                    }
                }
            }
        }
        setContent {
            val state by homeViewModel.uiState.collectAsStateWithLifecycle()
            BackHandler(state.overlay != HomeOverlay.None) { homeViewModel.onAction(HomeAction.CloseOverlay) }
            HomeScreen(state, homeViewModel::onAction, Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
        }
        if (savedInstanceState == null) receiveLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveLink(intent)
    }

    private fun receiveLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        val link = DownloadShareParser.extractUrl(intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        homeViewModel.onAction(HomeAction.Navigate(HomeNavigationRequest.OpenLink(link)))
    }

    companion object { const val EXTRA_SCENARIO = "home_scenario" }
}
