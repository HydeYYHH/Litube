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
    private val restoredFilters = SearchFilters(
        type = runCatching { SearchResultType.valueOf(savedState.get<String>(KEY_TYPE) ?: "") }
            .getOrDefault(SearchResultType.All),
        uploaded = runCatching { SearchUploadDate.valueOf(savedState.get<String>(KEY_DATE) ?: "") }
            .getOrDefault(SearchUploadDate.Anytime),
    )
    private val restoredOverlay = when (savedState.get<String>(KEY_OVERLAY)) {
        "search" -> SearchOverlay.Search
        "menu" -> savedState.get<String>(KEY_MENU_VIDEO)?.let { SearchOverlay.ItemMenu(it) }
        else -> SearchOverlay.None
    } ?: SearchOverlay.None
    private val _state = MutableStateFlow(
        SearchUiState(
            query = restoredQuery,
            content = scenario.initialContent(restoredQuery),
            overlay = restoredOverlay,
            draftQuery = savedState["draftQuery"] ?: restoredQuery,
            filters = restoredFilters,
            filtersOpen = savedState[KEY_FILTERS_OPEN] ?: (savedState.get<String>(KEY_OVERLAY) == "filters"),
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<SearchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    init {
        savedState["query"] = _state.value.query
        persistOverlay(restoredOverlay)
        persistFilters(restoredFilters)
        savedState[KEY_FILTERS_OPEN] = _state.value.filtersOpen
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
            is SearchAction.OpenItemMenu -> overlay(SearchOverlay.ItemMenu(action.videoId))
            SearchAction.CloseItemMenu -> overlay(SearchOverlay.None)
            is SearchAction.MenuAction -> {
                val menu = uiState.value.overlay as? SearchOverlay.ItemMenu ?: return
                overlay(SearchOverlay.None)
                if (action.action == SearchVideoMenuAction.Share) {
                    navigation.trySend(SearchNavigationRequest.Share(menu.videoId))
                } else {
                    navigation.trySend(SearchNavigationRequest.MenuAction(menu.videoId, action.action))
                }
            }
            SearchAction.OpenFilters -> {
                val open = !uiState.value.filtersOpen
                showFilters(open)
                if (open) navigation.trySend(SearchNavigationRequest.Filters)
            }
            SearchAction.CloseFilters -> showFilters(false)
            is SearchAction.SelectResultType -> selectType(action.type)
            is SearchAction.SelectUploadDate -> selectDate(action.date)
            SearchAction.VoiceSearch -> navigation.trySend(SearchNavigationRequest.VoiceSearch)
            SearchAction.OpenPageMenu -> navigation.trySend(SearchNavigationRequest.PageMenu)
            SearchAction.Retry -> load()
        }
    }

    fun updateQuery(query: String) {
        editSearch(false, query)
        if (query == uiState.value.query) return
        savedState["query"] = query
        _state.update { it.copy(query = query) }
        load()
    }

    private fun selectType(type: SearchResultType) {
        if (type != SearchResultType.All) {
            navigation.trySend(SearchNavigationRequest.UnsupportedType(type))
            return
        }
        if (uiState.value.filters.type == type) return
        val filters = uiState.value.filters.copy(type = type)
        persistFilters(filters)
        _state.update { it.copy(filters = filters) }
        load()
    }

    private fun selectDate(date: SearchUploadDate) {
        if (uiState.value.filters.uploaded == date) return
        val filters = uiState.value.filters.copy(uploaded = date)
        persistFilters(filters)
        _state.update { it.copy(filters = filters) }
        load()
    }

    private fun editSearch(open: Boolean, draft: String) {
        savedState["draftQuery"] = draft
        _state.update { it.copy(overlay = if (open) SearchOverlay.Search else SearchOverlay.None, draftQuery = draft) }
        persistOverlay(uiState.value.overlay)
    }

    private fun overlay(next: SearchOverlay) {
        _state.update { it.copy(overlay = next) }
        persistOverlay(next)
    }

    private fun persistOverlay(overlay: SearchOverlay) {
        savedState[KEY_OVERLAY] = when (overlay) {
            SearchOverlay.Search -> "search"
            is SearchOverlay.ItemMenu -> {
                savedState[KEY_MENU_VIDEO] = overlay.videoId
                "menu"
            }
            SearchOverlay.None -> {
                savedState.remove<String>(KEY_MENU_VIDEO)
                "none"
            }
        }
    }

    private fun showFilters(open: Boolean) {
        savedState[KEY_FILTERS_OPEN] = open
        _state.update { it.copy(filtersOpen = open) }
    }

    private fun persistFilters(filters: SearchFilters) {
        savedState[KEY_TYPE] = filters.type.name
        savedState[KEY_DATE] = filters.uploaded.name
    }

    private fun load() {
        val query = uiState.value.query
        val filters = uiState.value.filters
        loadJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = SearchContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.search(query, filters)
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

    private companion object {
        const val KEY_OVERLAY = "overlay"
        const val KEY_MENU_VIDEO = "menuVideo"
        const val KEY_TYPE = "filterType"
        const val KEY_DATE = "filterUpload"
        const val KEY_FILTERS_OPEN = "filtersOpen"
    }
}
