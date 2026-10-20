package com.hhst.youtubelite.watch

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.WatchAction
import com.hhst.youtubelite.watch.domain.WatchNavigationRequest
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.SearchPanel
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hhst.youtubelite.watch.presentation.WatchPlayer
import com.hhst.youtubelite.watch.presentation.WatchScreen
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class WatchActivity : ComponentActivity() {
    lateinit var watchViewModel: WatchViewModel
        private set
    var lastNavigationRequest: WatchNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val scenario = if (BuildConfig.DEBUG) WatchScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else WatchScenario.Content
        watchViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == WatchViewModel::class.java)
                return WatchViewModel(FixtureWatchRepository(scenario), extras.createSavedStateHandle(), videoId, scenario) as T
            }
        })[WatchViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is WatchNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@WatchActivity, request.channelId))
                        is WatchNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@WatchActivity, request.query))
                        is WatchNavigationRequest.Watch -> startActivity(intent(this@WatchActivity, request.videoId))
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val playerViewModel: PlayerViewModel = koinInject()
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val state by watchViewModel.uiState.collectAsStateWithLifecycle()
            val fullscreen = playerState.fullscreen
            val back: () -> Unit = {
                if (state.searchOpen) watchViewModel.onAction(WatchAction.CloseSearch)
                else when (PlayerUi.nextBackStep(playerState.locked, fullscreen)) {
                    PlayerUi.BackStep.Unlock -> playerViewModel.setLocked(false)
                    PlayerUi.BackStep.ExitFullscreen -> playerViewModel.setFullscreen(false)
                    PlayerUi.BackStep.BrowserBack -> finish()
                }
            }
            BackHandler(onBack = back)
            Box(Modifier.fillMaxSize().background(ComposeColor.White)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    WatchScreen(
                        state = state,
                        onAction = watchViewModel::onAction,
                        onBack = back,
                        modifier = Modifier.fillMaxSize().then(if (state.searchOpen) Modifier.clearAndSetSemantics {} else Modifier),
                        fullscreen = fullscreen,
                    )
                }
                if (fullscreen) {
                    Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                        WatchPlayer(Modifier.fillMaxSize(), back)
                    }
                }
                if (state.searchOpen) Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    val query = state.draftQuery.trim()
                    SearchPanel(
                        query = state.draftQuery,
                        suggestions = if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live"),
                        onQueryChanged = { watchViewModel.onAction(WatchAction.QueryChanged(it)) },
                        onSubmit = { watchViewModel.onAction(WatchAction.SubmitSearch(it)) },
                        onClose = { watchViewModel.onAction(WatchAction.CloseSearch) },
                        onVoiceSearch = { watchViewModel.onAction(WatchAction.VoiceSearch) },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "watch_video_id"
        const val EXTRA_SCENARIO = "watch_scenario"

        fun intent(context: Context, videoId: String, scenario: WatchScenario? = null) =
            Intent(context, WatchActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
