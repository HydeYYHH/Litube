package com.hhst.youtubelite.library

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
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.CollectionContent
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.library.domain.LibraryOverlay
import com.hhst.youtubelite.library.presentation.CollectionScreen
import com.hhst.youtubelite.library.presentation.CollectionViewModel
import com.hhst.youtubelite.library.presentation.verificationCompleted
import com.hhst.youtubelite.playlist.PlaylistActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.SearchPanel
import kotlinx.coroutines.launch

class PlaylistsActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var collectionViewModel: CollectionViewModel
        private set
    var lastNavigationRequest: LibraryNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the card; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) collectionViewModel.verificationCompleted(
            result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) LibraryScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else LibraryScenario.Content
        collectionViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == CollectionViewModel::class.java)
                return CollectionViewModel(if (fixtures) FixtureLibraryRepository(scenario) else sources.library, extras.createSavedStateHandle(), scenario, fixtures || com.hhst.youtubelite.data.media.MediaSession.signedIn(), ) as T
            }
        })[CollectionViewModel::class.java]
        if (!fixtures) observeAccountChanges(::syncSession)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectionViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is LibraryNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@PlaylistsActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        is LibraryNavigationRequest.Playlist -> launchNavigation(PlaylistActivity.intent(this@PlaylistsActivity, request.playlistId))
                        is LibraryNavigationRequest.Search -> launchNavigation(SearchResultsActivity.intent(this@PlaylistsActivity, request.query))
                        LibraryNavigationRequest.VoiceSearch -> if (!fixtures) openVoiceSearch()
                        LibraryNavigationRequest.SignIn -> com.hhst.youtubelite.account.SignInFlow.open(this@PlaylistsActivity)
                        // Shared library request type carries entries the collection screen never emits.
                        // No `else`: a new request type must fail to compile here.
                        is LibraryNavigationRequest.Watch, is LibraryNavigationRequest.Channel,
                        LibraryNavigationRequest.OpenHistory, LibraryNavigationRequest.OpenPlaylists,
                        LibraryNavigationRequest.OpenQueue, LibraryNavigationRequest.OpenDownloads,
                        is LibraryNavigationRequest.MenuAction, is LibraryNavigationRequest.Destination,
                        LibraryNavigationRequest.Settings, LibraryNavigationRequest.About,
                        LibraryNavigationRequest.SwitchAccount, LibraryNavigationRequest.SignOut -> Unit
                    }
                }
            }
        }
        setAppContent {
            val state by collectionViewModel.uiState.collectAsStateWithLifecycle()
            val back = {
                if (state.overlay != LibraryOverlay.None) collectionViewModel.onAction(LibraryAction.CloseOverlay) else finish()
            }
            BackHandler(onBack = back)
            CollectionScreen(state, collectionViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            if (state.overlay == LibraryOverlay.Search) {
                val query = state.query.trim()
                val rows = com.hhst.youtubelite.ui.components.rememberSearchRows(if (fixtures) null else sources.suggestions, query)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    SearchPanel(
                        query = state.query,
                        suggestions = rows?.suggestions ?: if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live").distinct(),
                        history = rows?.history.orEmpty(),
                        onRemoveHistory = rows?.onRemove,
                        onQueryChanged = { collectionViewModel.onAction(LibraryAction.QueryChanged(it)) },
                        onSubmit = { collectionViewModel.onAction(LibraryAction.SubmitSearch(it)); rows?.onSubmit?.invoke(it) },
                        onClose = back,
                        onVoiceSearch = { collectionViewModel.onAction(LibraryAction.VoiceSearch) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncSession()
    }

    private fun syncSession() {
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let { collectionViewModel.updateSession(it.signedIn, it.generation) }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "playlists_scenario"

        // Default scenario is never attached: parse(null) already yields Content, and a
        // scenario extra would trip the fixture-scene heuristic on normal navigation.
        fun intent(context: Context, scenario: LibraryScenario? = null) =
            Intent(context, PlaylistsActivity::class.java)
                .apply { if (scenario != null && scenario != LibraryScenario.Content) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
