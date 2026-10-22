package com.hhst.youtubelite.notifications

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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.notifications.data.FixtureNotificationsRepository
import com.hhst.youtubelite.notifications.data.NotificationsScenario
import com.hhst.youtubelite.notifications.data.notificationsSignedOutPrompt
import com.hhst.youtubelite.notifications.domain.NotificationsAction
import com.hhst.youtubelite.notifications.domain.NotificationsContent
import com.hhst.youtubelite.notifications.domain.NotificationsUiState
import com.hhst.youtubelite.notifications.domain.NotificationsNavigationRequest
import com.hhst.youtubelite.notifications.presentation.NotificationsScreen
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Hosts the notifications page. */
class NotificationsActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var notificationsViewModel: NotificationsViewModel
        private set
    var lastNavigationRequest: NotificationsNavigationRequest? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val signedIn = if (fixtures) intent.getBooleanExtra(EXTRA_SIGNED_IN, false) else com.hhst.youtubelite.data.media.MediaSession.signedIn()
        val scenario = if (BuildConfig.DEBUG) NotificationsScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else NotificationsScenario.Content
        notificationsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == NotificationsViewModel::class.java)
                return NotificationsViewModel(if (fixtures) FixtureNotificationsRepository(scenario) else sources.notifications, scenario, signedIn) as T
            }
        })[NotificationsViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                notificationsViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is NotificationsNavigationRequest.Watch -> startActivity(WatchActivity.intent(this@NotificationsActivity, request.videoId))
                        is NotificationsNavigationRequest.Channel -> startActivity(ChannelActivity.intent(this@NotificationsActivity, request.channelId))
                        NotificationsNavigationRequest.SignIn ->
                            com.hhst.youtubelite.account.SignInFlow.open(this@NotificationsActivity)
                    }
                }
            }
        }
        setContent {
            val state by notificationsViewModel.uiState.collectAsStateWithLifecycle()
            BackHandler { finish() }
            NotificationsScreen(state, notificationsViewModel::onAction, onBack = { finish() },
                modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            com.hhst.youtubelite.data.media.MediaSession.snapshot().let { notificationsViewModel.updateSession(it.signedIn, it.generation) }
        }
    }

    companion object {
        const val EXTRA_SIGNED_IN = "notifications_signed_in"
        const val EXTRA_SCENARIO = "notifications_scenario"

        fun intent(context: Context, signedIn: Boolean, scenario: NotificationsScenario? = null) =
            Intent(context, NotificationsActivity::class.java)
                .putExtra(EXTRA_SIGNED_IN, signedIn)
                .apply { if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument) }
    }
}

/** Renders the packaged notification samples. */
class NotificationsViewModel(
    private val repository: com.hhst.youtubelite.notifications.data.NotificationsRepository,
    private val scenario: NotificationsScenario,
    private var signedIn: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsUiState(signedIn = signedIn))
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<NotificationsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<NotificationsNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var generation = 0

    init {
        when {
            !signedIn -> _uiState.update { it.copy(content = NotificationsContent.SignedOut(notificationsSignedOutPrompt())) }
            scenario == NotificationsScenario.Loading -> _uiState.update { it.copy(content = NotificationsContent.Loading) }
            else -> refresh()
        }
    }

    private var sessionKey: Long? = null

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        if (signedIn == value && !changed) return
        signedIn = value
        ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(signedIn = value, content = NotificationsContent.SignedOut(notificationsSignedOutPrompt())) }
        if (value) refresh()
    }

    fun onAction(action: NotificationsAction) {
        when (action) {
            NotificationsAction.Retry -> refresh()
            is NotificationsAction.OpenVideo -> navigation.trySend(NotificationsNavigationRequest.Watch(action.videoId))
            is NotificationsAction.OpenChannel -> navigation.trySend(NotificationsNavigationRequest.Channel(action.channelId))
            NotificationsAction.SignIn -> navigation.trySend(NotificationsNavigationRequest.SignIn)
        }
    }


    private fun refresh() {
        if (!signedIn) return
        loadJob?.cancel()
        val request = ++generation
        _uiState.update { it.copy(content = NotificationsContent.Loading) }
        loadJob = viewModelScope.launch {
            runCatching { repository.notifications() }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    val content = if (page.items.isEmpty()) NotificationsContent.Empty else NotificationsContent.Ready(page)
                    _uiState.update { it.copy(content = content) }
                },
                onFailure = { if (it is kotlinx.coroutines.CancellationException) throw it
                    if (request == generation) _uiState.update { it.copy(content = NotificationsContent.Failed) } },
            )
        }
    }
}
