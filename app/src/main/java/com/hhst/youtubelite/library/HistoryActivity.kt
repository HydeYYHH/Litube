package com.hhst.youtubelite.library

import com.hhst.youtubelite.ui.design.labelResource
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.observeAccountChanges

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
        if (!fixtures) observeAccountChanges(::syncSession)
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
                        LibraryNavigationRequest.OpenPlaylists,
                        LibraryNavigationRequest.OpenQueue, LibraryNavigationRequest.OpenDownloads, is LibraryNavigationRequest.Destination,
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
            com.hhst.youtubelite.ui.design.WriteProgressOverlay(historyViewModel.writes)
            historyViewModel.saveSheet?.let { com.hhst.youtubelite.ui.design.WriteProgressOverlay(it.writes) }
            val back: () -> Unit = {
                if (historyViewModel.saveSheet?.state?.value != null) historyViewModel.saveSheet?.dismiss()
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
                        onDismiss = sheet::dismiss,
                        onDraftChange = { sheet.draftChanged(it.title, it.description) },
                        onSubmit = sheet::submitNewPlaylist,
                        pending = current.pending,
                    ) else com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet(
                        playlists = emptyList(),
                        onDismiss = sheet::dismiss,
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
                    items = HistoryMenuAction.entries.map { action -> MenuSheetItem(action.icon(), stringResource(action.labelResource)) },
                    onDismiss = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                    onSelect = { index -> historyViewModel.onAction(LibraryAction.MenuSelected(HistoryMenuAction.entries[index])) },
                    paneTitle = stringResource(R.string.ui_history_actions),
                )
            }
            if (state.overlay == LibraryOverlay.ManageHistory && !state.confirmClear) {
                MenuBottomSheet(
                    items = listOf(
                        MenuSheetItem(AppIcon.Pause, stringResource(R.string.ui_pause_watch_history)),
                        MenuSheetItem(AppIcon.Play, stringResource(R.string.ui_resume_watch_history)),
                        MenuSheetItem(AppIcon.Delete, stringResource(R.string.ui_clear_watch_history_2)),
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
                    paneTitle = stringResource(R.string.a11y_history_settings),
                )
            }
            if (state.overlay == LibraryOverlay.ManageHistory && state.confirmClear) {
                com.hhst.youtubelite.ui.components.AppAlertDialog(
                    onDismissRequest = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                    title = { androidx.compose.material3.Text(stringResource(R.string.ui_clear_watch_history)) },
                    text = { androidx.compose.material3.Text(stringResource(R.string.ui_your_watch_history_will_be_cleared_on_this_account_this_can_t_be_undon)) },
                    confirmButton = {
                        com.hhst.youtubelite.ui.components.AppTextButton(
                            onClick = { historyViewModel.onAction(LibraryAction.ConfirmClearHistory) },
                        ) { androidx.compose.material3.Text(stringResource(R.string.ui_clear)) }
                    },
                    dismissButton = {
                        com.hhst.youtubelite.ui.components.AppTextButton(
                            onClick = { historyViewModel.onAction(LibraryAction.CloseOverlay) },
                        ) { androidx.compose.material3.Text(stringResource(R.string.cancel)) }
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
        syncSession()
    }

    private fun syncSession() {
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let { historyViewModel.updateSession(it.signedIn, it.generation) }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "history_scenario"

        // The default scenario is never attached: parse(null) already yields Content,
        // and a scenario extra would trip the fixture-scene heuristic on normal navigation.
        fun intent(context: Context, scenario: LibraryScenario? = null) =
            Intent(context, HistoryActivity::class.java)
                .apply { if (scenario != null && scenario != LibraryScenario.Content) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
