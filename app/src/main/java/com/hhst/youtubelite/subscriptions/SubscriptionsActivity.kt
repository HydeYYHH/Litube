package com.hhst.youtubelite.subscriptions

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
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsAction
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsNavigationRequest
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsOverlay
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsViewModel
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.components.MainTabs
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.SearchPanel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

/** Hosts the subscriptions page. The Shorts tab is wired with its own screen. */
class SubscriptionsActivity : ComponentActivity() {
    lateinit var subscriptionsViewModel: SubscriptionsViewModel
        private set
    var lastNavigationRequest: SubscriptionsNavigationRequest? = null
        private set

    val shareGateway: com.hhst.youtubelite.share.ShareGateway by lazy { com.hhst.youtubelite.share.ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val signedIn = intent.getBooleanExtra(EXTRA_SIGNED_IN, false)
        val scenario = if (BuildConfig.DEBUG) SubscriptionsScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else SubscriptionsScenario.Content
        subscriptionsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == SubscriptionsViewModel::class.java)
                return SubscriptionsViewModel(FixtureSubscriptionsRepository(scenario), extras.createSavedStateHandle(), scenario, signedIn) as T
            }
        })[SubscriptionsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                subscriptionsViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is SubscriptionsNavigationRequest.MenuAction -> if (request.action == com.hhst.youtubelite.home.domain.FeedMenuAction.Share) {
                            shareGateway.share(request.videoId)
                        }

                        is SubscriptionsNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@SubscriptionsActivity, request.videoId))
                        is SubscriptionsNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@SubscriptionsActivity, request.channelId))
                        is SubscriptionsNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@SubscriptionsActivity, request.query))
                        is SubscriptionsNavigationRequest.Destination -> openTab(request.tab)
                        SubscriptionsNavigationRequest.SignIn -> com.hhst.youtubelite.account.SignInFlow.open(this@SubscriptionsActivity)
                        SubscriptionsNavigationRequest.Manage -> subscriptionsViewModel.onAction(SubscriptionsAction.ShowChannels)
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by subscriptionsViewModel.uiState.collectAsStateWithLifecycle()
            val back: () -> Unit = {
                when {
                    state.overlay == SubscriptionsOverlay.Channels -> subscriptionsViewModel.onAction(SubscriptionsAction.CloseChannels)
                    state.overlay != SubscriptionsOverlay.None -> subscriptionsViewModel.onAction(SubscriptionsAction.CloseOverlay)
                    else -> finish()
                }
            }
            BackHandler(onBack = back)
            SubscriptionsScreen(state, subscriptionsViewModel::onAction,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            if (state.overlay == SubscriptionsOverlay.Search) {
                val query = state.query.trim()
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    SearchPanel(
                        query = state.query,
                        suggestions = if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live").distinct(),
                        onQueryChanged = { subscriptionsViewModel.onAction(SubscriptionsAction.QueryChanged(it)) },
                        onSubmit = { subscriptionsViewModel.onAction(SubscriptionsAction.SubmitSearch(it)) },
                        onClose = back,
                        onVoiceSearch = { subscriptionsViewModel.onAction(SubscriptionsAction.VoiceSearch) },
                    )
                }
            }
            if (state.overlay is SubscriptionsOverlay.Menu) {
                MenuBottomSheet(
                    items = com.hhst.youtubelite.home.domain.FeedMenuKind.Video.actions.map { action ->
                        MenuSheetItem(action.icon(), action.label)
                    },
                    onDismiss = { subscriptionsViewModel.onAction(SubscriptionsAction.CloseOverlay) },
                    onSelect = { index ->
                        subscriptionsViewModel.onAction(SubscriptionsAction.MenuSelected(FeedMenuKind.Video.actions[index]))
                    },
                    paneTitle = "Video actions",
                )
            }
        }
    }

    private fun FeedMenuAction.icon() = when (this) {
        FeedMenuAction.WatchLater -> AppIcon.Clock
        FeedMenuAction.SaveToPlaylist -> AppIcon.Bookmark
        FeedMenuAction.Share -> AppIcon.Share
        FeedMenuAction.NotInterested -> AppIcon.Block
        FeedMenuAction.DontRecommend -> AppIcon.Close
        else -> AppIcon.Info
    }

    private fun openTab(tab: MainTab) {
        val intent = MainTabs.intent(this, tab, intent.getBooleanExtra(EXTRA_SIGNED_IN, false))
        if (intent != null) startActivity(intent)
    }

    companion object {
        const val EXTRA_SIGNED_IN = "subscriptions_signed_in"
        const val EXTRA_SCENARIO = "subscriptions_scenario"

        fun intent(context: Context, signedIn: Boolean, scenario: SubscriptionsScenario? = null) =
            Intent(context, SubscriptionsActivity::class.java)
                .putExtra(EXTRA_SIGNED_IN, signedIn)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
