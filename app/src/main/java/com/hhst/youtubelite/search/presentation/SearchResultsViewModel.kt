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
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.trafficChallenge

class SearchResultsViewModel(
    private val repository: SearchRepository,
    private val savedState: SavedStateHandle,
    query: String,
    scenario: SearchScenario = SearchScenario.Results,
    private var signedIn: Boolean? = null,
    private val videoActions: com.hhst.youtubelite.watch.domain.VideoActions? = null,
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
    private val avatars: com.hhst.youtubelite.data.media.MediaAvatarDirectory? = null,
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
        "playlist" -> savedState.get<String>(KEY_MENU_VIDEO)?.let { SearchOverlay.PlaylistMenu(it) }
        else -> SearchOverlay.None
    } ?: SearchOverlay.None
    private val _state = MutableStateFlow(
        SearchUiState(
            query = restoredQuery,
            content = scenario.initialContent(restoredQuery),
            overlay = restoredOverlay,
            draftQuery = savedState["draftQuery"] ?: restoredQuery,
            filters = restoredFilters,
            signedInPreview = signedIn ?: (restoredFilters.type == SearchResultType.All),
            filtersOpen = savedState[KEY_FILTERS_OPEN] ?: (savedState.get<String>(KEY_OVERLAY) == "filters"),
            live = videoActions != null,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<SearchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    /** Live save flow for the result menus; null in fixture builds, where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }
    private var loadJob: Job? = null
    private var generation = 0
    private var pageJob: Job? = null
    private var sessionKey: Long? = null
    private var actionJob: Job? = null

    fun updateSession(signedIn: Boolean, key: Long) {
        val changed = sessionKey != null && sessionKey != key
        sessionKey = key
        this.signedIn = signedIn
        _state.update { it.copy(signedInPreview = signedIn) }
        if (changed) {
            // The old account's save targets and in-flight writes must not survive the switch.
            actionJob?.cancel()
            saveSheet?.close()
            overlay(SearchOverlay.None)
            load()
        }
    }

    init {
        savedState["query"] = _state.value.query
        persistOverlay(restoredOverlay)
        persistFilters(restoredFilters)
        savedState[KEY_FILTERS_OPEN] = _state.value.filtersOpen
        if (scenario.loadsResults() && _state.value.content is SearchContent.Loading) load()
    }

    fun onAction(action: SearchAction) {
        when (action) {
            SearchAction.LoadMore -> loadMore()
            SearchAction.OpenSearch -> editSearch(true, uiState.value.query)
            SearchAction.CloseSearch -> editSearch(false, uiState.value.draftQuery)
            is SearchAction.QueryChanged -> editSearch(true, action.query)
            is SearchAction.SubmitSearch -> {
                val query = action.query.trim()
                if (query.isNotEmpty()) updateQuery(query)
            }
            is SearchAction.OpenResult -> navigation.trySend(
                if (uiState.value.filters.type == SearchResultType.Shorts) SearchNavigationRequest.Shorts(action.videoId)
                else SearchNavigationRequest.Watch(action.videoId))
            is SearchAction.OpenPlaylist -> navigation.trySend(SearchNavigationRequest.Playlist(action.playlistId))
            is SearchAction.OpenPlaylistMenu -> overlay(SearchOverlay.PlaylistMenu(action.playlistId))
            SearchAction.SharePlaylist -> {
                val menu = uiState.value.overlay as? SearchOverlay.PlaylistMenu ?: return
                overlay(SearchOverlay.None)
                navigation.trySend(SearchNavigationRequest.SharePlaylist(menu.playlistId))
            }
            is SearchAction.Subscribe -> {
                val actions = videoActions
                if (actions == null) navigation.trySend(SearchNavigationRequest.Subscribe(action.channelId))
                else if (actionJob?.isActive != true) {
                    val target = _state.value.subscriptions[action.channelId] != true
                    actionJob = viewModelScope.launch {
                        try {
                            actions.subscribe(action.channelId, target)
                            _state.update { it.copy(subscriptions = it.subscriptions + (action.channelId to target)) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) { notices.trySend(AppNotice.SignIn) }
                        catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) { notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again.")) }
                        catch (_: Exception) { notices.trySend(AppNotice.Message("Couldn't update this subscription. Try again.")) }
                    }
                }
            }
            is SearchAction.OpenChannel -> navigation.trySend(SearchNavigationRequest.Channel(action.channelId))
            is SearchAction.SelectDestination -> navigation.trySend(SearchNavigationRequest.Destination(action.destination))
            is SearchAction.OpenItemMenu -> overlay(SearchOverlay.ItemMenu(action.videoId))
            SearchAction.CloseItemMenu -> overlay(SearchOverlay.None)
            is SearchAction.MenuAction -> {
                val menu = uiState.value.overlay as? SearchOverlay.ItemMenu ?: return
                overlay(SearchOverlay.None)
                when {
                    action.action == SearchVideoMenuAction.Share ->
                        navigation.trySend(SearchNavigationRequest.Share(menu.videoId))
                    saveSheet == null ->
                        navigation.trySend(SearchNavigationRequest.MenuAction(menu.videoId, action.action))
                    action.action == SearchVideoMenuAction.WatchLater -> saveSheet.saveWatchLater(menu.videoId)
                    action.action == SearchVideoMenuAction.SaveToPlaylist -> saveSheet.open(menu.videoId)
                    else -> Unit
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
            SearchAction.DismissVerification -> _state.update { it.copy(verification = null) }
            is SearchAction.OpenVerification -> navigation.trySend(SearchNavigationRequest.OpenVerification(action.challengeUrl))
            SearchAction.Retry -> load()
        }
    }

    /** One typed retry after a completed `/sorry` verification; the host launcher calls this with the solved challenge. */
    fun verificationCompleted(challengeUrl: String?) {
        if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
        onAction(SearchAction.DismissVerification)
        onAction(SearchAction.Retry)
    }

    fun updateQuery(query: String) {
        editSearch(false, query)
        if (query == uiState.value.query) return
        savedState["query"] = query
        _state.update { it.copy(query = query) }
        load()
    }

    private fun selectType(type: SearchResultType) {
        if (uiState.value.filters.type == type) return
        val filters = uiState.value.filters.copy(type = type)
        persistFilters(filters)
        _state.update { it.copy(filters = filters, signedInPreview = signedIn ?: (type == SearchResultType.All)) }
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
            is SearchOverlay.PlaylistMenu -> {
                savedState[KEY_MENU_VIDEO] = overlay.playlistId
                "playlist"
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
        pageJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = SearchContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.search(query, filters)
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = if (page.results.isEmpty() && page.playlists.isEmpty() && page.channels.isEmpty() && page.shorts.isEmpty() && page.nextCursor == null) SearchContent.Empty(query) else SearchContent.Results(page)) }
                fillMissingAvatars(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestGeneration != generation) return@launch
                val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                    ?.let(::trafficChallenge)
                _state.update { it.copy(content = SearchContent.Failed, verification = challenge ?: it.verification) }
            }
        }
    }

    /**
     * One shared directory read per channel, capped and cached there; the rewrite only
     * touches rows still missing their avatar, so a stale application is identity-safe.
     */
    private var avatarJob: Job? = null
    private fun fillMissingAvatars(page: com.hhst.youtubelite.search.domain.SearchPage) {
        val directory = avatars ?: return
        val resolved = (page.results + page.shorts)
            .filter { it.avatar == null && it.channelId.isNotBlank() }
            .distinctBy { it.channelId }
            .takeIf { it.isNotEmpty() } ?: return
        avatarJob?.cancel()
        avatarJob = viewModelScope.launch {
            val byChannel = resolved.mapNotNull { row ->
                directory.avatar(row.channelId)?.let { row.channelId to it }
            }.toMap()
            if (byChannel.isEmpty()) return@launch
            fun <T> List<T>.filled(avatar: (T) -> String?, channelId: (T) -> String, copy: (T, String) -> T) =
                map { row -> if (avatar(row) == null) byChannel[channelId(row)]?.let { copy(row, it) } ?: row else row }
            _state.update { state ->
                val ready = state.content as? SearchContent.Results ?: return@update state
                state.copy(content = SearchContent.Results(ready.page.copy(
                    results = ready.page.results.filled({ it.avatar }, { it.channelId }, { row, a -> row.copy(avatar = a) }),
                    shorts = ready.page.shorts.filled({ it.avatar }, { it.channelId }, { row, a -> row.copy(avatar = a) }),
                )))
            }
        }
    }

    private fun loadMore() {
        val ready = _state.value.content as? SearchContent.Results ?: return
        val cursor = ready.page.nextCursor ?: return
        if (ready.loadingMore) return
        val requestedGeneration = generation
        val query = _state.value.query
        val filters = _state.value.filters
        _state.update { it.copy(content = ready.copy(loadingMore = true, loadMoreFailed = false)) }
        pageJob = viewModelScope.launch {
            try {
                val page = repository.next(query, filters, cursor)
                if (requestedGeneration != generation) return@launch
                if (page.nextCursor == cursor) throw java.io.IOException("repeated_cursor")
                val combined = ready.page.copy(
                    results = (ready.page.results + page.results).distinctBy { it.id },
                    playlists = (ready.page.playlists + page.playlists).distinctBy { it.id },
                    channels = (ready.page.channels + page.channels).distinctBy { it.id },
                    shorts = (ready.page.shorts + page.shorts).distinctBy { it.id },
                    nextCursor = page.nextCursor,
                )
                _state.update { it.copy(content = SearchContent.Results(combined)) }
                fillMissingAvatars(combined)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (requestedGeneration != generation) return@launch
                val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                    ?.let(::trafficChallenge)
                _state.update { it.copy(content = ready.copy(loadMoreFailed = true), verification = challenge ?: it.verification) }
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
