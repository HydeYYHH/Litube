package com.hhst.youtubelite.playlist

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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.playlist.data.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.*
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

class PlaylistActivity : ComponentActivity() {
    lateinit var playlistViewModel: PlaylistViewModel
        private set
    var lastNavigationRequest: PlaylistNavigationRequest? = null
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0xFF2D4F72.toInt()), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val id = intent.getStringExtra(EXTRA_PLAYLIST_ID) ?: FixturePlaylistRepository.MOVIES
        val scenario = if (BuildConfig.DEBUG) PlaylistScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else PlaylistScenario.Content
        playlistViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == PlaylistViewModel::class.java)
                return PlaylistViewModel(FixturePlaylistRepository(scenario), extras.createSavedStateHandle(), id, scenario) as T
            }
        })[PlaylistViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is PlaylistNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@PlaylistActivity, request.videoId))
                        is PlaylistNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@PlaylistActivity, request.channelId))
                        is PlaylistNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@PlaylistActivity, request.query))
                        is PlaylistNavigationRequest.Share -> shareGateway.sharePlaylist(request.playlistId)
                        PlaylistNavigationRequest.Destination(PlaylistDestination.Home) -> startActivity(Intent(this@PlaylistActivity, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by playlistViewModel.uiState.collectAsStateWithLifecycle()
            val pageColor = (state.content as? PlaylistContent.Ready)?.page?.summary?.accent
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = pageColor == null
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
            }
            val back = { if (state.overlay != PlaylistOverlay.None) playlistViewModel.onAction(PlaylistAction.CloseOverlay) else finish() }
            BackHandler(onBack = back)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing)
                    .background(pageColor?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.ui.graphics.Color.White))
                PlaylistScreen(state, playlistViewModel::onAction, back,
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            }
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_SCENARIO = "playlist_scenario"
        fun intent(context: Context, playlistId: String, scenario: PlaylistScenario? = null) = Intent(context, PlaylistActivity::class.java)
            .putExtra(EXTRA_PLAYLIST_ID, playlistId).apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
