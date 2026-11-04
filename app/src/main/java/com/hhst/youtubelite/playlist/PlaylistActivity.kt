package com.hhst.youtubelite.playlist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import com.hhst.youtubelite.R
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import com.hhst.youtubelite.ui.design.setAppContent
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

import com.hhst.youtubelite.ui.renderNotice
import com.hhst.youtubelite.ui.observeAccountChanges

class PlaylistActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var playlistViewModel: PlaylistViewModel
        private set
    var lastNavigationRequest: PlaylistNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the banner; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) playlistViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }
    val shareGateway: ShareGateway by lazy { ShareGateway(this) }
    private var downloadPending = false

    private fun downloadPlaylist() {
        val page = (playlistViewModel.uiState.value.content as? PlaylistContent.Ready)?.page ?: return
        val items = page.entries.mapNotNull { entry ->
            com.hhst.youtubelite.extractor.VideoId.parse(entry.videoId)?.let { id ->
                com.hhst.youtubelite.downloader.core.DownloadRequest(id, entry.title, entry.channel, entry.artwork)
            }
        }.distinctBy { it.videoId }
        if (items.isEmpty()) return
        val result = com.hhst.youtubelite.downloader.ui.DownloadUi.showBatchConfirm(this,
            com.hhst.youtubelite.downloader.core.BatchSnapshot(
                com.hhst.youtubelite.downloader.core.BatchSource.PLAYLIST, page.summary.title, items))
        if (result is com.hhst.youtubelite.downloader.ui.DownloadUiStart.Rejected)
            android.widget.Toast.makeText(this, R.string.playlist_too_large_to_download,
                android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.dark(0xFF2D4F72.toInt()), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (playlistId.isNullOrBlank() && !fixtures) {
            // No identity and no fixture seam: rendering a default playlist would fabricate content.
            finish()
            return
        }
        val id = playlistId ?: FixturePlaylistRepository.MOVIES
        val scenario = if (BuildConfig.DEBUG) PlaylistScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else PlaylistScenario.Content
        playlistViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == PlaylistViewModel::class.java)
                return PlaylistViewModel(if (fixtures) FixturePlaylistRepository(scenario) else sources.playlist, extras.createSavedStateHandle(), id, scenario, if (fixtures) null else sources.saves) as T
            }
        })[PlaylistViewModel::class.java]
        if (!fixtures) {
            fun syncSession() {
                com.hhst.youtubelite.data.media.MediaSession.snapshot().let {
                    playlistViewModel.updateSession(it.signedIn, it.generation)
                }
            }
            syncSession()
            observeAccountChanges(::syncSession)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistViewModel.actionNotices.collect { notice ->
                    renderNotice(notice)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is PlaylistNavigationRequest.Watch -> {
                            if (!fixtures) {
                                val entries = (playlistViewModel.uiState.value.content as? PlaylistContent.Ready)?.page?.entries.orEmpty()
                                                                val player = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.player.PlayerViewModel>()
                                player.startPlaylist(entries.map { com.hhst.youtubelite.player.QueueItem(it.videoId, title = it.title, author = it.channel) }, startId = request.videoId)
                            }
                            launchNavigation(WatchActivity.intent(this@PlaylistActivity, request.videoId))
                        }
                        is PlaylistNavigationRequest.Queue -> {
                            val entries = (playlistViewModel.uiState.value.content as? PlaylistContent.Ready)?.page?.entries.orEmpty()
                            val selected = if (request.shuffle) entries.shuffled() else entries
                            selected.firstOrNull()?.let { first ->
                                if (!fixtures) org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.player.PlayerViewModel>()
                                    .startPlaylist(selected.map { com.hhst.youtubelite.player.QueueItem(it.videoId, title = it.title, author = it.channel) })
                                launchNavigation(WatchActivity.intent(this@PlaylistActivity, first.videoId))
                            }
                        }
                        is PlaylistNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@PlaylistActivity, request.channelId))
                        is PlaylistNavigationRequest.Search -> launchNavigation(SearchResultsActivity.intent(this@PlaylistActivity, request.query))
                        is PlaylistNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@PlaylistActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        PlaylistNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        is PlaylistNavigationRequest.Download -> downloadPlaylist()
                        is PlaylistNavigationRequest.Share -> shareGateway.sharePlaylist(request.playlistId)
                        is PlaylistNavigationRequest.Destination -> com.hhst.youtubelite.ui.components.MainTabs.intent(
                            this@PlaylistActivity, com.hhst.youtubelite.ui.components.MainTab.valueOf(request.destination.name), playlistViewModel.uiState.value.signedIn)?.let { launchNavigation(it); finish() }
                        // Fixture-only typed exits: production runs saves and per-video actions
                        // through the in-VM save sheet. No `else`: a new request type must fail
                        // to compile here.
                        is PlaylistNavigationRequest.Save, is PlaylistNavigationRequest.VideoAction -> Unit
                    }
                }
            }
        }
        downloadPending = savedInstanceState?.getBoolean(EXTRA_DOWNLOAD) ?: intent.getBooleanExtra(EXTRA_DOWNLOAD, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistViewModel.uiState.collect { state ->
                    if (downloadPending && state.content is PlaylistContent.Ready) {
                        downloadPending = false
                        downloadPlaylist()
                    }
                }
            }
        }
        setAppContent {
            val state by playlistViewModel.uiState.collectAsStateWithLifecycle()
            playlistViewModel.saveSheet?.let { com.hhst.youtubelite.ui.design.WriteProgressOverlay(it.writes) }
            val pageColor = (state.content as? PlaylistContent.Ready)?.page?.summary?.accent
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = pageColor == null
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
            }
            val back: () -> Unit = {
                if (playlistViewModel.saveSheet?.state?.value != null) playlistViewModel.saveSheet?.dismiss()
                else if (state.overlay != PlaylistOverlay.None) playlistViewModel.onAction(PlaylistAction.CloseOverlay)
                else finish()
            }
            BackHandler(onBack = back)
            Box(Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing)
                    .background(pageColor?.let { androidx.compose.ui.graphics.Color(it) } ?: com.hhst.youtubelite.ui.design.DesignTokens.Background))
                PlaylistScreen(state, playlistViewModel::onAction, back,
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
                playlistViewModel.saveSheet?.let { sheet ->
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
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_DOWNLOAD, downloadPending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let {
                playlistViewModel.updateSession(it.signedIn, it.generation)
            }
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD = "playlist_download"
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_SCENARIO = "playlist_scenario"
        fun intent(context: Context, playlistId: String, scenario: PlaylistScenario? = null) = Intent(context, PlaylistActivity::class.java)
            .putExtra(EXTRA_PLAYLIST_ID, playlistId).apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
