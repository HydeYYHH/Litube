package com.hhst.youtubelite.search.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.search.data.SearchScenario
import com.hhst.youtubelite.search.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchResultsViewModel(
    private val repository: SearchRepository,
    private val savedState: SavedStateHandle,
    query: String,
    scenario: SearchScenario = SearchScenario.Results,
) : ViewModel() {
    private val restoredQuery = savedState.get<String>("query") ?: query
    private val _state = MutableStateFlow(
        SearchUiState(
            query = restoredQuery,
            content = scenario.initialContent(restoredQuery),
            searchOpen = savedState["searchOpen"] ?: false,
            draftQuery = savedState["draftQuery"] ?: restoredQuery,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<SearchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    init {
        savedState["query"] = _state.value.query
        if (scenario.loadsResults() && _state.value.content is SearchContent.Loading) load()
    }

    fun onAction(action: SearchAction) {
        when (action) {
            SearchAction.OpenSearch -> editSearch(true, uiState.value.query)
            SearchAction.CloseSearch -> editSearch(false, uiState.value.draftQuery)
            is SearchAction.QueryChanged -> editSearch(true, action.query)
            is SearchAction.SubmitSearch -> {
                val query = action.query.trim()
                if (query.isNotEmpty()) updateQuery(query)
            }
            is SearchAction.OpenResult -> navigation.trySend(SearchNavigationRequest.Watch(action.videoId))
            is SearchAction.OpenChannel -> navigation.trySend(SearchNavigationRequest.Channel(action.channelId))
            is SearchAction.SelectDestination -> navigation.trySend(SearchNavigationRequest.Destination(action.destination))
            is SearchAction.OpenItemMenu -> navigation.trySend(SearchNavigationRequest.ItemMenu(action.videoId))
            SearchAction.OpenFilters -> navigation.trySend(SearchNavigationRequest.Filters)
            SearchAction.VoiceSearch -> navigation.trySend(SearchNavigationRequest.VoiceSearch)
            SearchAction.OpenPageMenu -> navigation.trySend(SearchNavigationRequest.PageMenu)
            SearchAction.Retry -> load()
        }
    }

    /** A single-top intent with another query reloads the local page. */
    fun updateQuery(query: String) {
        editSearch(false, query)
        if (query == uiState.value.query) return
        savedState["query"] = query
        _state.update { it.copy(query = query) }
        load()
    }

    private fun editSearch(open: Boolean, draft: String) {
        savedState["searchOpen"] = open
        savedState["draftQuery"] = draft
        _state.update { it.copy(searchOpen = open, draftQuery = draft) }
    }

    private fun load() {
        val query = uiState.value.query
        loadJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = SearchContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.search(query)
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = if (page.results.isEmpty()) SearchContent.Empty(query) else SearchContent.Results(page)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = SearchContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
