package com.hhst.youtubelite.subscriptions.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.data.SubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.data.subscriptionsSignedOutPrompt
import com.hhst.youtubelite.subscriptions.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/** Hosts the subscriptions feed and its channel list. */
class SubscriptionsViewModel(
    private val repository: SubscriptionsRepository,
    private val savedState: SavedStateHandle,
    private val scenario: SubscriptionsScenario,
    private var signedIn: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SubscriptionsUiState(signedIn = signedIn, query = savedState[QUERY_KEY] ?: "",
            overlay = when (savedState.get<String>("overlay")) {
                "search" -> SubscriptionsOverlay.Search
                "channels" -> SubscriptionsOverlay.Channels
                "menu" -> savedState.get<String>("menuItem")?.let { SubscriptionsOverlay.Menu(it) } ?: SubscriptionsOverlay.None
                else -> SubscriptionsOverlay.None
            },
            selectedChip = SubscriptionsChip.entries.firstOrNull { it.name == savedState.get<String>("chip") } ?: SubscriptionsChip.All,
            content = if (scenario == SubscriptionsScenario.Content) SubscriptionsContent.Loading
            else scenario.initialContent(subscriptionsSignedOutPrompt()))
    )
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<SubscriptionsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<SubscriptionsNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0

    init {
        when {
            !signedIn -> _uiState.update { it.copy(content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
            scenario == SubscriptionsScenario.Content -> refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
            else -> Unit
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
        pageJob?.cancel()
        _uiState.update { it.copy(signedIn = value, content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
        if (value) refresh(SubscriptionsFilter.from(uiState.value.selectedChip))
    }

    fun onAction(action: SubscriptionsAction) {
        when (action) {
            SubscriptionsAction.Retry -> refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
            SubscriptionsAction.LoadNext -> loadNext()
            SubscriptionsAction.OpenSearch -> showOverlay(SubscriptionsOverlay.Search)
            SubscriptionsAction.CloseOverlay -> showOverlay(SubscriptionsOverlay.None)
            is SubscriptionsAction.QueryChanged -> {
                savedState[QUERY_KEY] = action.query
                _uiState.update { it.copy(query = action.query) }
            }
            is SubscriptionsAction.SubmitSearch -> {
                savedState[QUERY_KEY] = action.query
                showOverlay(SubscriptionsOverlay.None)
                _uiState.update { it.copy(query = action.query) }
                navigation.trySend(SubscriptionsNavigationRequest.Search(action.query))
            }
            is SubscriptionsAction.SelectChip -> {
                savedState["chip"] = action.chip.name
                _uiState.update { it.copy(selectedChip = action.chip) }
                refresh(SubscriptionsFilter.from(action.chip))
            }
            SubscriptionsAction.ShowChannels -> showOverlay(SubscriptionsOverlay.Channels)
            SubscriptionsAction.CloseChannels -> showOverlay(SubscriptionsOverlay.None)
            is SubscriptionsAction.OpenVideo -> navigation.trySend(SubscriptionsNavigationRequest.Watch(action.videoId))
            is SubscriptionsAction.OpenChannel -> navigation.trySend(SubscriptionsNavigationRequest.Channel(action.channelId))
            is SubscriptionsAction.OpenMenu -> showOverlay(SubscriptionsOverlay.Menu(action.videoId))
            is SubscriptionsAction.MenuSelected -> {
                val videoId = (_uiState.value.overlay as? SubscriptionsOverlay.Menu)?.videoId
                showOverlay(SubscriptionsOverlay.None)
                if (videoId != null) navigation.trySend(SubscriptionsNavigationRequest.MenuAction(videoId, action.action))
            }
            is SubscriptionsAction.Destination -> {
                if (action.tab == com.hhst.youtubelite.ui.components.MainTab.Subscriptions) return
                navigation.trySend(SubscriptionsNavigationRequest.Destination(action.tab))
            }
            SubscriptionsAction.SignIn -> navigation.trySend(SubscriptionsNavigationRequest.SignIn)
            SubscriptionsAction.Manage -> navigation.trySend(SubscriptionsNavigationRequest.Manage)
            SubscriptionsAction.VoiceSearch -> navigation.trySend(SubscriptionsNavigationRequest.VoiceSearch)
        }
    }

    private fun showOverlay(next: SubscriptionsOverlay) {
        savedState["overlay"] = when (next) {
            SubscriptionsOverlay.None -> "none"
            SubscriptionsOverlay.Search -> "search"
            SubscriptionsOverlay.Channels -> "channels"
            is SubscriptionsOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? SubscriptionsOverlay.Menu)?.videoId
        _uiState.update { it.copy(overlay = next) }
    }

    private fun refresh(filter: SubscriptionsFilter) {
        if (!signedIn) return
        loadJob?.cancel()
        pageJob?.cancel()
        val request = ++generation
        _uiState.update { it.copy(content = SubscriptionsContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.subscriptions(filter)
                if (request == generation) _uiState.update { it.copy(content = SubscriptionsContent.Feed(page)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _uiState.update { it.copy(content = SubscriptionsContent.Failed) }
            }
        }
    }

    private fun loadNext() {
        val current = _uiState.value.content as? SubscriptionsContent.Feed ?: return
        if (current.page.nextKey == null || current.append == AppendState.Loading) return
        val request = generation
        val filter = SubscriptionsFilter.from(_uiState.value.selectedChip)
        _uiState.update { it.copy(content = current.copy(append = AppendState.Loading)) }
        pageJob = viewModelScope.launch {
            try {
                val page = repository.subscriptions(filter, current.page.nextKey)
                if (request != generation) return@launch
                _uiState.update { state ->
                    val existing = (state.content as? SubscriptionsContent.Feed)?.page ?: return@update state
                    state.copy(content = SubscriptionsContent.Feed(existing.copy(
                        items = (existing.items + page.items).distinctBy { it.id },
                        nextKey = page.nextKey.takeUnless { it == current.page.nextKey },
                    )))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _uiState.update { state ->
                    val existing = state.content as? SubscriptionsContent.Feed ?: return@update state
                    state.copy(content = existing.copy(append = AppendState.Failed))
                }
            }
        }
    }

    /** The panel menu mirrors the home feed menu kinds. */
    val menuKind: FeedMenuKind = FeedMenuKind.Video

    companion object {
        private const val QUERY_KEY = "subscriptions_query"
    }
}
