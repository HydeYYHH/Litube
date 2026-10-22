package com.hhst.youtubelite.shorts.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Hosts the shorts pager. Like/dislike stay typed requests; counts are fixture display. */
class ShortsViewModel(
    private val repository: ShortsRepository,
    private val savedState: SavedStateHandle,
    private val scenario: ShortsScenario,
    private val startItemId: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShortsUiState(
        startItemId = startItemId,
        currentItemId = savedState["currentItem"] ?: startItemId,
        overlay = savedState.get<String>("overlayItem")?.let { id ->
            if (savedState.get<String>("overlay") == "comments") ShortsOverlay.Comments(id)
            else ShortsOverlay.Menu(id)
        } ?: ShortsOverlay.None,
    ))
    private var loadJob: Job? = null
    private var generation = 0
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()
    private val navigation = Channel<ShortsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<ShortsNavigationRequest> = navigation.receiveAsFlow()

    init {
        if (scenario == ShortsScenario.Loading) {
            _uiState.update { it.copy(content = ShortsContent.Loading) }
        } else refresh()
    }

    fun onAction(action: ShortsAction) {
        when (action) {
            ShortsAction.Retry -> refresh()
            is ShortsAction.SelectItem -> {
                savedState["currentItem"] = action.itemId
                _uiState.update { it.copy(currentItemId = action.itemId) }
            }
            is ShortsAction.Subscribe -> navigation.trySend(ShortsNavigationRequest.Subscribe(action.channelId))
            is ShortsAction.OpenChannel -> navigation.trySend(ShortsNavigationRequest.Channel(action.channelId))
            is ShortsAction.OpenComments -> overlay(ShortsOverlay.Comments(action.itemId))
            ShortsAction.CloseComments -> overlay(ShortsOverlay.None)
            ShortsAction.CloseMenu -> overlay(ShortsOverlay.None)
            is ShortsAction.OpenMenu -> overlay(ShortsOverlay.Menu(action.itemId))
            is ShortsAction.MenuSelected -> {
                val itemId = (_uiState.value.overlay as? ShortsOverlay.Menu)?.itemId
                overlay(ShortsOverlay.None)
                if (itemId != null) navigation.trySend(ShortsNavigationRequest.MenuAction(itemId, action.action))
            }
            is ShortsAction.Share -> navigation.trySend(ShortsNavigationRequest.Share(action.itemId))
            is ShortsAction.Like -> navigation.trySend(ShortsNavigationRequest.Rate(action.itemId, like = true))
            is ShortsAction.Dislike -> navigation.trySend(ShortsNavigationRequest.Rate(action.itemId, like = false))
        }
    }

    private fun overlay(next: ShortsOverlay) {
        savedState["overlay"] = if (next is ShortsOverlay.Comments) "comments" else "menu"
        savedState["overlayItem"] = when (next) {
            is ShortsOverlay.Comments -> next.itemId
            is ShortsOverlay.Menu -> next.itemId
            ShortsOverlay.None -> null
        }
        _uiState.update { it.copy(overlay = next) }
    }

    private fun refresh() {
        loadJob?.cancel()
        val request = ++generation
        _uiState.update { it.copy(content = ShortsContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.shorts()
                if (request != generation) return@launch
                _uiState.update { it.copy(content = if (page.items.isEmpty()) ShortsContent.Empty else ShortsContent.Ready(page)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _uiState.update { it.copy(content = ShortsContent.Failed) }
            }
        }
    }
}
