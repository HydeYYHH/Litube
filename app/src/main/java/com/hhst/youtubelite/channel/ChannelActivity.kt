package com.hhst.youtubelite.channel

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.domain.ChannelOverlay
import com.hhst.youtubelite.share.ShareGateway
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
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelDestination
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.post.PostDetailActivity
import com.hhst.youtubelite.channel.domain.ChannelNavigationRequest
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

/** Hosts the local channel page and observes its navigation requests. */
class ChannelActivity : ComponentActivity() {
    lateinit var channelViewModel: ChannelViewModel
        private set
    var lastNavigationRequest: ChannelNavigationRequest? = null
        private set
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: FixtureChannelRepository.profile().channelId
        val scenario = if (BuildConfig.DEBUG) ChannelScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ChannelScenario.Content
        channelViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ChannelViewModel::class.java)
                return ChannelViewModel(FixtureChannelRepository(scenario), extras.createSavedStateHandle(), channelId, scenario) as T
            }
        })[ChannelViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                channelViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ChannelNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@ChannelActivity, request.videoId))
                        is ChannelNavigationRequest.Playlist -> startActivity(com.hhst.youtubelite.playlist.PlaylistActivity.intent(this@ChannelActivity, request.playlistId))
                        is ChannelNavigationRequest.SharePlaylist -> shareGateway.sharePlaylist(request.playlistId)
                        is ChannelNavigationRequest.Post -> startActivity(
                            PostDetailActivity.intent(this@ChannelActivity, request.identity.channelId, request.identity.postId)
                        )
                        ChannelNavigationRequest.Destination(ChannelDestination.Home) ->
                            startActivity(Intent(this@ChannelActivity, HomeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by channelViewModel.uiState.collectAsStateWithLifecycle()
            val back = { if (state.overlay != ChannelOverlay.None) channelViewModel.onAction(ChannelAction.CloseOverlay) else finish() }
            BackHandler(onBack = back)
            ChannelScreen(state, channelViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_SCENARIO = "channel_scenario"

        fun intent(context: Context, channelId: String, scenario: ChannelScenario? = null) =
            Intent(context, ChannelActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
