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
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.CollectionContent
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.library.domain.LibraryOverlay
import com.hhst.youtubelite.library.presentation.CollectionScreen
import com.hhst.youtubelite.library.presentation.CollectionViewModel
import com.hhst.youtubelite.playlist.PlaylistActivity
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.ui.design.SearchPanel
import kotlinx.coroutines.launch

/** Hosts the personal playlists collection page. */
class PlaylistsActivity : ComponentActivity() {
    lateinit var collectionViewModel: CollectionViewModel
        private set
    var lastNavigationRequest: LibraryNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) LibraryScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else LibraryScenario.Content
        collectionViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == CollectionViewModel::class.java)
                return CollectionViewModel(FixtureLibraryRepository(scenario), extras.createSavedStateHandle(), scenario) as T
            }
        })[CollectionViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectionViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is LibraryNavigationRequest.Playlist -> startActivity(PlaylistActivity.intent(this@PlaylistsActivity, request.playlistId))
                        is LibraryNavigationRequest.Search -> startActivity(SearchResultsActivity.intent(this@PlaylistsActivity, request.query))
                        LibraryNavigationRequest.SignIn -> com.hhst.youtubelite.account.SignInFlow.open(this@PlaylistsActivity)
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by collectionViewModel.uiState.collectAsStateWithLifecycle()
            val back = {
                if (state.overlay != LibraryOverlay.None) collectionViewModel.onAction(LibraryAction.CloseOverlay) else finish()
            }
            BackHandler(onBack = back)
            val playlists = (state.content as? CollectionContent.Ready)?.playlists.orEmpty()
            CollectionScreen(state, playlists, collectionViewModel::onAction, onBack = back,
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
            if (state.overlay == LibraryOverlay.Search) {
                val query = state.query.trim()
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    SearchPanel(
                        query = state.query,
                        suggestions = if (query.isBlank()) emptyList() else listOf(query, "$query documentary", "$query music", "$query live").distinct(),
                        onQueryChanged = { collectionViewModel.onAction(LibraryAction.QueryChanged(it)) },
                        onSubmit = { collectionViewModel.onAction(LibraryAction.SubmitSearch(it)) },
                        onClose = back,
                        onVoiceSearch = { collectionViewModel.onAction(LibraryAction.VoiceSearch) },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "playlists_scenario"

        fun intent(context: Context, scenario: LibraryScenario? = null) =
            Intent(context, PlaylistsActivity::class.java)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}
