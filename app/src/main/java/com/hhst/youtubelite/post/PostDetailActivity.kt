package com.hhst.youtubelite.post

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.post.domain.PostDestination
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.PostDetailAction
import com.hhst.youtubelite.post.domain.PostDetailNavigationRequest
import com.hhst.youtubelite.post.domain.PostDetailOverlay
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.presentation.PostDetailScreen
import com.hhst.youtubelite.post.presentation.PostDetailViewModel
import kotlinx.coroutines.launch

class PostDetailActivity : ComponentActivity() {
    lateinit var postDetailViewModel: PostDetailViewModel
        private set
    var lastNavigationRequest: PostDetailNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: "studio"
        val postId = intent.getStringExtra(EXTRA_POST_ID) ?: FixturePostRepository.HOME_POST_ID
        val identity = PostIdentity(channelId, postId)
        val scenario = if (BuildConfig.DEBUG) PostScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else PostScenario.Content
        postDetailViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == PostDetailViewModel::class.java)
                return PostDetailViewModel(
                    FixturePostRepository(scenario),
                    extras.createSavedStateHandle(),
                    identity,
                    scenario,
                ) as T
            }
        })[PostDetailViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                postDetailViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is PostDetailNavigationRequest.Channel -> startActivity(
                            ChannelActivity.intent(this@PostDetailActivity, request.channelId)
                        )
                        PostDetailNavigationRequest.Destination(PostDestination.Home) -> startActivity(
                            Intent(this@PostDetailActivity, HomeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by postDetailViewModel.uiState.collectAsStateWithLifecycle()
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
            val back = {
                if (state.overlay != PostDetailOverlay.None) postDetailViewModel.onAction(PostDetailAction.CloseOverlay)
                else finish()
            }
            BackHandler(onBack = back)
            PostDetailScreen(
                state,
                postDetailViewModel::onAction,
                back,
                Modifier.statusBarsPadding().navigationBarsPadding(),
            )
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_POST_ID = "postId"
        const val EXTRA_SCENARIO = "scenario"

        fun intent(context: Context, channelId: String, postId: String, scenario: PostScenario? = null): Intent =
            Intent(context, PostDetailActivity::class.java)
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_POST_ID, postId)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
