package com.hhst.youtubelite.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: HomeRepository,
    private val savedState: SavedStateHandle,
    scenario: HomeScenario = HomeScenario.Empty,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(
        content = scenario.initialContent(), query = savedState["query"] ?: "",
        selectedChip = savedState["chip"] ?: "All",
        signedInPreview = scenario != HomeScenario.Empty,
        overlay = if (savedState.get<Boolean>("searchOpen") == true) HomeOverlay.Search else HomeOverlay.None,
    ))
    val uiState = _state.asStateFlow()
    private val navigation = Channel<HomeNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.OpenSearch -> overlay(HomeOverlay.Search)
            HomeAction.CloseOverlay -> overlay(HomeOverlay.None)
            is HomeAction.QueryChanged -> {
                savedState["query"] = action.query
                _state.update { it.copy(query = action.query) }
            }
            is HomeAction.SubmitSearch -> if (action.query.isNotBlank()) {
                onAction(HomeAction.QueryChanged(action.query.trim()))
                // The results page takes over; the overlay must not reappear when it returns.
                overlay(HomeOverlay.None)
                navigation.trySend(HomeNavigationRequest.Search(action.query.trim()))
            }
            is HomeAction.SelectChip -> {
                if (action.chip == uiState.value.selectedChip) return
                savedState["chip"] = action.chip
                _state.update { it.copy(selectedChip = action.chip) }
                load(append = false)
            }
            is HomeAction.OpenMenu -> overlay(HomeOverlay.Menu(action.itemId, action.kind))
            is HomeAction.MenuSelected -> {
                val menu = uiState.value.overlay as? HomeOverlay.Menu ?: return
                if (action.action !in menu.kind.actions) return
                overlay(HomeOverlay.None)
                navigation.trySend(HomeNavigationRequest.MenuAction(menu.itemId, action.action))
            }
            is HomeAction.Navigate -> navigation.trySend(action.request)
            HomeAction.Refresh -> load(append = false)
            HomeAction.LoadNext -> load(append = true)
        }
    }

    private fun overlay(overlay: HomeOverlay) {
        savedState["searchOpen"] = overlay == HomeOverlay.Search
        _state.update { it.copy(overlay = overlay) }
    }

    private fun load(append: Boolean) {
        val feed = uiState.value.content as? HomeContent.Feed
        val next = if (append) feed?.page?.nextKey ?: return else null
        if (append && (feed?.append == AppendState.Loading || feed?.refreshing == true)) return
        loadJob?.cancel()
        val requestGeneration = ++generation
        val filter = uiState.value.selectedChip
        _state.update { state -> state.copy(content = when {
            append && feed != null -> feed.copy(append = AppendState.Loading)
            feed != null -> feed.copy(refreshing = true, append = AppendState.Idle, refreshFailed = false)
            else -> HomeContent.Loading
        }) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.loadHome(filter, next)
                if (requestGeneration != generation) return@launch
                val combined = if (append && feed != null) page.copy(items = (feed.page.items + page.items).distinctBy { it.id }) else page
                _state.update { it.copy(content = if (combined.items.isEmpty()) HomeContent.Empty(
                    title = combined.messages.firstOrNull() ?: HomeContent.Empty().title,
                ) else HomeContent.Feed(combined)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = if (feed != null) feed.copy(refreshing = false,
                    append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = !append) else HomeContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
