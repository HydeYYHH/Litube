package com.hhst.youtubelite.library

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
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.HistoryMenuAction
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.library.domain.LibraryOverlay
import com.hhst.youtubelite.library.presentation.HistoryScreen
import com.hhst.youtubelite.library.presentation.HistoryViewModel
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.SearchPanel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

/** Hosts the watch history page. */
class HistoryActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var historyViewModel: HistoryViewModel
        private set
    var lastNavigationRequest: LibraryNavigationRequest? = null
        private set

    val shareGateway: com.hhst.youtubelite.share.ShareGateway by lazy { com.hhst.youtubelite.share.ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) LibraryScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else LibraryScenario.Content
        historyViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == HistoryViewModel::class.java)
                return HistoryViewModel(if (fixtures) FixtureLibraryRepository(scenario) else sources.library, extras.createSavedStateHandle(), scenario, fixtures || com.hhst.youtubelite.data.media.MediaSession.signedIn()) as T
            }
        })[HistoryViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                historyViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is LibraryNavigationRequest.MenuAction -> if (request.action == com.hhst.youtubelite.library.domain.HistoryMenuAction.Share) {
                            shareGateway.share(request.videoId)
                        }

                        is LibraryNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@HistoryActivity, request.videoId))
                        is LibraryNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@HistoryActivity, request.channelId))
                        is LibraryNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@HistoryActivity, request.query))
                        LibraryNavigationRequest.SignIn -> com.hhst.youtubelite.account.SignInFlow.open(this@HistoryActivity)
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by historyViewModel.uiState.collectAsStateWithLifecycle()
            val back = {
                if (state.overlay != LibraryOverlay.None) historyViewModel.onAction(LibraryAction.CloseOverlay) else finish()
            }
            BackHandler(onBack = back)
            HistoryScreen(state, historyViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            if (state.overlay == LibraryOverlay.Search) {
                val query = state.query.trim()
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    SearchPanel(
                        query = state.query,
                        suggestions = if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live").distinct(),
                        onQueryChanged = { historyViewModel.onAction(LibraryAction.QueryChanged(it)) },
                        onSubmit = { historyViewModel.onAction(LibraryAction.SubmitSearch(it)) },
                        onClose = back,
                        onVoiceSearch = { historyViewModel.onAction(LibraryAction.VoiceSearch) },
                    )
                }
            }
            if (state.overlay is LibraryOverlay.Menu) {
                MenuBottomSheet(
                    items = HistoryMenuAction.entries.map { action -> MenuSheetItem(action.icon(), action.label) },
                    onDismiss = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                    onSelect = { index -> historyViewModel.onAction(LibraryAction.MenuSelected(HistoryMenuAction.entries[index])) },
                    paneTitle = "History actions",
                )
            }
        }
    }

    private fun HistoryMenuAction.icon() = when (this) {
        HistoryMenuAction.WatchLater -> AppIcon.Clock
        HistoryMenuAction.SaveToPlaylist -> AppIcon.Bookmark
        HistoryMenuAction.Share -> AppIcon.Share
        HistoryMenuAction.RemoveFromHistory -> AppIcon.Close
    }

    override fun onResume() {
        super.onResume()
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let { historyViewModel.updateSession(it.signedIn, it.generation) }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "history_scenario"

        fun intent(context: Context, scenario: LibraryScenario? = null) =
            Intent(context, HistoryActivity::class.java)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
