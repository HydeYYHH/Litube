package com.hhst.youtubelite.library

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
import com.hhst.youtubelite.library.presentation.verificationCompleted
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.SearchPanel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

import com.hhst.youtubelite.ui.renderNotice

/** Hosts the watch history page. */
class HistoryActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var historyViewModel: HistoryViewModel
        private set
    var lastNavigationRequest: LibraryNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the card; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) historyViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }

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
                return HistoryViewModel(if (fixtures) FixtureLibraryRepository(scenario) else sources.library, extras.createSavedStateHandle(), scenario, fixtures || com.hhst.youtubelite.data.media.MediaSession.signedIn(),
                    if (fixtures) null else sources.saves) as T
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

                        is LibraryNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@HistoryActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        is LibraryNavigationRequest.Watch -> launchNavigation(WatchActivity.intent(this@HistoryActivity, request.videoId))
                        is LibraryNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@HistoryActivity, request.channelId))
                        is LibraryNavigationRequest.Search -> launchNavigation(SearchResultsActivity.intent(this@HistoryActivity, request.query))
                        LibraryNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        LibraryNavigationRequest.SignIn -> com.hhst.youtubelite.account.SignInFlow.open(this@HistoryActivity)
                        // The shared library request type carries You-page entries (tabs, account,
                        // settings, downloads, playlists) the history screen never emits. No
                        // `else`: a new request type must fail to compile here.
                        is LibraryNavigationRequest.Playlist, LibraryNavigationRequest.OpenHistory,
                        LibraryNavigationRequest.OpenPlaylists, LibraryNavigationRequest.OpenNotifications,
                        LibraryNavigationRequest.OpenDownloads, is LibraryNavigationRequest.Destination,
                        LibraryNavigationRequest.Settings, LibraryNavigationRequest.About,
                        LibraryNavigationRequest.SwitchAccount, LibraryNavigationRequest.SignOut -> Unit
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                historyViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        setAppContent {
            val state by historyViewModel.uiState.collectAsStateWithLifecycle()
            val back: () -> Unit = {
                if (historyViewModel.saveSheet?.state?.value != null) historyViewModel.saveSheet?.close()
                else if (state.overlay != LibraryOverlay.None) historyViewModel.onAction(LibraryAction.CloseOverlay)
                else finish()
            }
            BackHandler(onBack = back)
            HistoryScreen(state, historyViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            historyViewModel.saveSheet?.let { sheet ->
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
            if (state.overlay == LibraryOverlay.Search) {
                val query = state.query.trim()
                val rows = com.hhst.youtubelite.ui.components.rememberSearchRows(if (fixtures) null else sources.suggestions, query)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    SearchPanel(
                        query = state.query,
                        suggestions = rows?.suggestions ?: if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live").distinct(),
                        history = rows?.history.orEmpty(),
                        onRemoveHistory = rows?.onRemove,
                        onQueryChanged = { historyViewModel.onAction(LibraryAction.QueryChanged(it)) },
                        onSubmit = { historyViewModel.onAction(LibraryAction.SubmitSearch(it)); rows?.onSubmit?.invoke(it) },
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
            if (state.overlay == LibraryOverlay.ManageHistory && !state.confirmClear) {
                MenuBottomSheet(
                    items = listOf(
                        MenuSheetItem(AppIcon.Pause, "Pause watch history"),
                        MenuSheetItem(AppIcon.Play, "Resume watch history"),
                        MenuSheetItem(AppIcon.Delete, "Clear watch history"),
                    ),
                    onDismiss = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                    onSelect = { index ->
                        when (index) {
                            0 -> historyViewModel.onAction(LibraryAction.PauseHistory)
                            1 -> historyViewModel.onAction(LibraryAction.ResumeHistory)
                            else -> historyViewModel.onAction(LibraryAction.RequestClearHistory)
                        }
                    },
                    tag = "history-manage-sheet",
                    paneTitle = "History settings",
                )
            }
            if (state.overlay == LibraryOverlay.ManageHistory && state.confirmClear) {
                com.hhst.youtubelite.ui.components.YoutubeAlertDialog(
                    onDismissRequest = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                    title = { androidx.compose.material3.Text("Clear watch history?") },
                    text = { androidx.compose.material3.Text("Your watch history will be cleared on this account. This can't be undone.") },
                    confirmButton = {
                        com.hhst.youtubelite.ui.components.YoutubeTextButton(
                            onClick = { historyViewModel.onAction(LibraryAction.ConfirmClearHistory) },
                        ) { androidx.compose.material3.Text("Clear") }
                    },
                    dismissButton = {
                        com.hhst.youtubelite.ui.components.YoutubeTextButton(
                            onClick = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                        ) { androidx.compose.material3.Text("Cancel") }
                    },
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
