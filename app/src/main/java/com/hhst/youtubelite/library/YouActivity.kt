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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.account.SignInFlow
import com.hhst.youtubelite.account.domain.AccountMenuAction
import com.hhst.youtubelite.account.presentation.AccountMenuHeader
import com.hhst.youtubelite.account.presentation.accountMenuActions
import com.hhst.youtubelite.account.presentation.accountMenuItems
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryContent
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.library.domain.LibraryOverlay
import com.hhst.youtubelite.library.presentation.YouScreen
import com.hhst.youtubelite.library.presentation.YouViewModel
import com.hhst.youtubelite.notifications.NotificationsActivity
import com.hhst.youtubelite.playlist.PlaylistActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.settings.SettingsActivity
import com.hhst.youtubelite.ui.about.AboutActivity
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.components.MainTabs
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.SearchPanel
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.launch

import com.hhst.youtubelite.ui.renderNotice

/** Hosts the library page. The Shorts tab is wired with its own screen. */
class YouActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var youViewModel: YouViewModel
        private set
    var lastNavigationRequest: LibraryNavigationRequest? = null
        private set

    /** Test seam: records the typed request without opening the downloads manager. */
    var launchDownloads: Boolean = true

    private var showSwitchSheet by mutableStateOf(false)
    private var confirmSignOut by mutableStateOf(false)

    val shareGateway: com.hhst.youtubelite.share.ShareGateway by lazy { com.hhst.youtubelite.share.ShareGateway(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val signedIn = if (fixtures) intent.getBooleanExtra(EXTRA_SIGNED_IN, false) else com.hhst.youtubelite.data.media.MediaSession.signedIn()
        val scenario = if (BuildConfig.DEBUG) LibraryScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else LibraryScenario.Content
        youViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == YouViewModel::class.java)
                return YouViewModel(if (fixtures) FixtureLibraryRepository(scenario) else sources.library, extras.createSavedStateHandle(), scenario, signedIn,
                    if (fixtures) null else sources.saves) as T
            }
        })[YouViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                youViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is LibraryNavigationRequest.MenuAction -> if (request.action == com.hhst.youtubelite.library.domain.HistoryMenuAction.Share) {
                            shareGateway.share(request.videoId)
                        }

                        is LibraryNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@YouActivity, request.videoId))
                        is LibraryNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@YouActivity, request.channelId))
                        is LibraryNavigationRequest.Playlist -> startActivity(PlaylistActivity.intent(this@YouActivity, request.playlistId))
                        is LibraryNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@YouActivity, request.query))
                        LibraryNavigationRequest.OpenHistory -> startActivity(HistoryActivity.intent(this@YouActivity, scenario))
                        LibraryNavigationRequest.OpenPlaylists -> startActivity(PlaylistsActivity.intent(this@YouActivity, scenario))
                        LibraryNavigationRequest.OpenNotifications -> startActivity(NotificationsActivity.intent(this@YouActivity, signedIn))
                        LibraryNavigationRequest.OpenDownloads ->
                            if (launchDownloads) com.hhst.youtubelite.downloader.ui.DownloadUi.openManager(this@YouActivity)
                        LibraryNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        LibraryNavigationRequest.SignIn -> SignInFlow.open(this@YouActivity)
                        LibraryNavigationRequest.SwitchAccount -> showSwitchSheet = true
                        LibraryNavigationRequest.SignOut -> confirmSignOut = true
                        LibraryNavigationRequest.Settings -> startActivity(SettingsActivity.intent(this@YouActivity))
                        LibraryNavigationRequest.About ->
                            startActivity(Intent(this@YouActivity, AboutActivity::class.java))
                        is LibraryNavigationRequest.Destination -> openTab(request.tab)
                        // No `else`: a new request type must fail to compile here.
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                youViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        setAppContent {
            val state by youViewModel.uiState.collectAsStateWithLifecycle()
            val back: () -> Unit = {
                if (youViewModel.saveSheet?.state?.value != null) youViewModel.saveSheet?.close()
                else if (confirmSignOut) confirmSignOut = false
                else if (showSwitchSheet) showSwitchSheet = false
                else if (state.overlay != LibraryOverlay.None) youViewModel.onAction(LibraryAction.CloseOverlay)
                else MainTabs.intent(this, MainTab.Home, state.signedIn)?.let(::startActivity)
            }
            BackHandler(onBack = back)
            YouScreen(state, youViewModel::onAction,
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            youViewModel.saveSheet?.let { sheet ->
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
                        onQueryChanged = { youViewModel.onAction(LibraryAction.QueryChanged(it)) },
                        onSubmit = { youViewModel.onAction(LibraryAction.SubmitSearch(it)); rows?.onSubmit?.invoke(it) },
                        onClose = back,
                        onVoiceSearch = { youViewModel.onAction(LibraryAction.VoiceSearch) },
                    )
                }
            }
            if (state.overlay == LibraryOverlay.Account) {
                val account = (state.content as? LibraryContent.Ready)?.page?.account
                if (account == null) {
                    val actions = listOf(AccountMenuAction.SwitchAccount, AccountMenuAction.Settings, AccountMenuAction.About)
                    MenuBottomSheet(
                        items = actions.map { action ->
                            com.hhst.youtubelite.ui.design.MenuSheetItem(null,
                                if (action == AccountMenuAction.SwitchAccount) "Sign in" else action.label,
                                tag = "account-action-${action.tag}")
                        },
                        onDismiss = { youViewModel.onAction(LibraryAction.CloseOverlay) },
                        onSelect = { youViewModel.onAction(LibraryAction.AccountSelected(actions[it])) },
                        tag = "account-menu", paneTitle = "Menu",
                    )
                } else {
                    MenuBottomSheet(
                        items = accountMenuItems,
                        onDismiss = { youViewModel.onAction(LibraryAction.CloseOverlay) },
                        onSelect = { index -> youViewModel.onAction(LibraryAction.AccountSelected(accountMenuActions[index])) },
                        tag = "account-menu",
                        paneTitle = "Account menu",
                        header = {
                            AccountMenuHeader(account) {
                                youViewModel.onAction(LibraryAction.AccountSelected(AccountMenuAction.ViewChannel))
                            }
                        },
                    )
                }
            }
            if (state.overlay is LibraryOverlay.Menu) {
                com.hhst.youtubelite.ui.design.MenuBottomSheet(
                    items = com.hhst.youtubelite.library.domain.HistoryMenuAction.entries.map { action ->
                        com.hhst.youtubelite.ui.design.MenuSheetItem(action.icon(), action.label)
                    },
                    onDismiss = { youViewModel.onAction(LibraryAction.CloseOverlay) },
                    onSelect = { index ->
                        youViewModel.onAction(LibraryAction.MenuSelected(com.hhst.youtubelite.library.domain.HistoryMenuAction.entries[index]))
                    },
                    paneTitle = "History actions",
                )
            }
            if (showSwitchSheet) {
                // Fixtures keep a deterministic second account so the switcher is testable offline.
                val items = if (fixtures) {
                    val current = (state.content as? LibraryContent.Ready)?.page?.account
                    listOfNotNull(current?.let { com.hhst.youtubelite.account.domain.AccountItem(it.channelId, it.name, it.handle, it.avatar, selected = true) }) +
                        com.hhst.youtubelite.account.domain.AccountItem("litube-maker", "Litube Maker", "@litube-maker", "", selected = false)
                } else {
                    val selectedId = com.hhst.youtubelite.data.media.MediaSession.snapshot().accountId
                    com.hhst.youtubelite.data.media.MediaSession.accounts().filterNot { it.disabled }.map {
                        com.hhst.youtubelite.account.domain.AccountItem(it.id, it.name.orEmpty(), it.email.orEmpty(), it.photo.orEmpty(), it.id == selectedId)
                    }
                }
                com.hhst.youtubelite.account.presentation.AccountSwitchSheet(items,
                    onSelect = { id ->
                        showSwitchSheet = false
                        if (!fixtures && com.hhst.youtubelite.data.media.MediaSession.selectAccount(id)) {
                            val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
                            youViewModel.updateSession(session.signedIn, session.generation)
                        }
                    },
                    onAddAccount = {
                        showSwitchSheet = false
                        SignInFlow.open(this@YouActivity)
                    },
                    onDismiss = { showSwitchSheet = false })
            }
            if (confirmSignOut) {
                com.hhst.youtubelite.ui.components.YoutubeAlertDialog(
                    onDismissRequest = { confirmSignOut = false },
                    title = { com.hhst.youtubelite.ui.design.AppText("Sign out?") },
                    text = { com.hhst.youtubelite.ui.design.AppText("This removes the account from this device. Downloads and settings are kept.") },
                    confirmButton = {
                        com.hhst.youtubelite.ui.components.YoutubeTextButton(onClick = {
                            confirmSignOut = false
                            if (!fixtures) {
                                com.hhst.youtubelite.data.media.MediaSession.signOut()
                                val session = com.hhst.youtubelite.data.media.MediaSession.snapshot()
                                youViewModel.updateSession(session.signedIn, session.generation)
                            }
                        }, modifier = Modifier.testTag("account-signout-confirm")) {
                            com.hhst.youtubelite.ui.design.AppText("Sign out", color = com.hhst.youtubelite.ui.theme.YoutubeStyle.Action)
                        }
                    },
                    dismissButton = {
                        com.hhst.youtubelite.ui.components.YoutubeTextButton(onClick = { confirmSignOut = false }) {
                            com.hhst.youtubelite.ui.design.AppText("Cancel")
                        }
                    })
            }
        }
    }

    private fun com.hhst.youtubelite.library.domain.HistoryMenuAction.icon() = when (this) {
        com.hhst.youtubelite.library.domain.HistoryMenuAction.WatchLater -> AppIcon.Clock
        com.hhst.youtubelite.library.domain.HistoryMenuAction.SaveToPlaylist -> AppIcon.Bookmark
        com.hhst.youtubelite.library.domain.HistoryMenuAction.Share -> AppIcon.Share
        com.hhst.youtubelite.library.domain.HistoryMenuAction.RemoveFromHistory -> AppIcon.Close
    }

    private fun openTab(tab: MainTab) {
        val intent = MainTabs.intent(this, tab, intent.getBooleanExtra(EXTRA_SIGNED_IN, false))
        if (intent != null) startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let { youViewModel.updateSession(it.signedIn, it.generation) }
        }
    }

    companion object {
        const val EXTRA_SIGNED_IN = "library_signed_in"
        const val EXTRA_SCENARIO = "library_scenario"

        fun intent(context: Context, signedIn: Boolean, scenario: LibraryScenario? = null) =
            Intent(context, YouActivity::class.java)
                .putExtra(EXTRA_SIGNED_IN, signedIn)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
