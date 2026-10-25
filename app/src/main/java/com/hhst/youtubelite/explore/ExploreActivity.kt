package com.hhst.youtubelite.explore

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.core.trafficChallenge
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.data.FixtureExploreRepository
import com.hhst.youtubelite.explore.domain.ExploreAction
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExploreContent
import com.hhst.youtubelite.explore.domain.ExploreNavigationRequest
import com.hhst.youtubelite.explore.presentation.ExploreScreen
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExploreViewModel(private val repository: ExploreRepository, private val scenario: ExploreScenario,
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(com.hhst.youtubelite.explore.domain.ExploreUiState(category =
        ExploreCategory.entries.firstOrNull { it.name == savedState.get<String>("category") } ?: ExploreCategory.Music))
    val uiState: StateFlow<com.hhst.youtubelite.explore.domain.ExploreUiState> = _uiState.asStateFlow()
    private val navigation = Channel<ExploreNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<ExploreNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var generation = 0
    private var sessionGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation

    /** Identity-scoped sections re-read after an account switch. */
    fun updateSession(generation: Long) {
        if (generation == sessionGeneration) return
        sessionGeneration = generation
        load(_uiState.value.category)
    }

    init {
        if (scenario == ExploreScenario.Loading) {
            _uiState.update { it.copy(content = ExploreContent.Loading) }
        } else load(_uiState.value.category)
    }

    fun onAction(action: ExploreAction) {
        when (action) {
            ExploreAction.Retry -> load(_uiState.value.category)
            is ExploreAction.SelectCategory -> {
                savedState["category"] = action.category.name
                _uiState.update { it.copy(category = action.category) }
                load(action.category)
            }
            is ExploreAction.OpenVideo -> navigation.trySend(ExploreNavigationRequest.Watch(action.videoId))
            is ExploreAction.OpenChannel -> navigation.trySend(ExploreNavigationRequest.Channel(action.channelId))
            ExploreAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is ExploreAction.OpenVerification -> navigation.trySend(ExploreNavigationRequest.OpenVerification(action.challengeUrl))
        }
    }

    private fun load(category: ExploreCategory) {
        loadJob?.cancel()
        val request = ++generation
        _uiState.update { it.copy(content = ExploreContent.Loading) }
        loadJob = viewModelScope.launch {
            runCatching { repository.explore(category) }.fold(
                onSuccess = { page -> if (request == generation) _uiState.update { it.copy(content = ExploreContent.Ready(page)) } },
                onFailure = { failure -> if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (request != generation) return@fold
                    val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                        ?.let(::trafficChallenge)
                    _uiState.update { it.copy(content = ExploreContent.Failed, verification = challenge ?: it.verification) } },
            )
        }
    }
}

/** The explore entry: four fixed category containers sharing one template. */
class ExploreActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var exploreViewModel: ExploreViewModel
        private set
    var lastNavigationRequest: ExploreNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the card; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) {
            val challenge = result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE)
            if (challenge != null && exploreViewModel.uiState.value.verification?.challengeUrl == challenge) {
                exploreViewModel.onAction(ExploreAction.DismissVerification)
                exploreViewModel.onAction(ExploreAction.Retry)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Explore sections are identity-scoped server-side (visitor vs account).
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            exploreViewModel.updateSession(com.hhst.youtubelite.data.media.MediaSession.snapshot().generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) ExploreScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ExploreScenario.Content
        val category = ExploreCategory.parse(intent.getStringExtra(EXTRA_CATEGORY))
        exploreViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ExploreViewModel::class.java)
                val handle = extras.createSavedStateHandle()
                if (!handle.contains("category")) handle["category"] = category.name
                return ExploreViewModel(if (fixtures) FixtureExploreRepository(scenario) else sources.explore, scenario, handle) as T
            }
        })[ExploreViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                exploreViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ExploreNavigationRequest.Watch -> launchNavigation(WatchActivity.intent(this@ExploreActivity, request.videoId))
                        is ExploreNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@ExploreActivity, request.channelId))
                        is ExploreNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@ExploreActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        // No `else`: a new request type must fail to compile here.
                    }
                }
            }
        }
        setAppContent {
            val state by exploreViewModel.uiState.collectAsStateWithLifecycle()
            ExploreScreen(state, exploreViewModel::onAction, onBack = { finish() },
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "explore_scenario"
        const val EXTRA_CATEGORY = "explore_category"

        fun intent(context: Context, category: ExploreCategory? = null, scenario: ExploreScenario? = null) =
            Intent(context, ExploreActivity::class.java)
                .apply {
                    if (category != null) putExtra(EXTRA_CATEGORY, category.name)
                    if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument)
                }
    }
}
