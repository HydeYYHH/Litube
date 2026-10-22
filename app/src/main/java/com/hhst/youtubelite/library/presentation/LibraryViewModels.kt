package com.hhst.youtubelite.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.library.domain.*
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

/** Hosts the You page. Library screens keep one VM per page, mirroring the search host. */
class YouViewModel(
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
    private val scenario: LibraryScenario,
    private var signedIn: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState(signedIn = signedIn, query = savedState[QUERY_KEY] ?: "", overlay = savedState.libraryOverlay()))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<LibraryNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<LibraryNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: Job? = null
    private var generation = 0

    init {
        when {
            !signedIn -> _uiState.update { it.copy(content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
            scenario == LibraryScenario.Loading -> _uiState.update { it.copy(content = LibraryContent.Loading) }
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
        _uiState.update { it.copy(signedIn = value, content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
        if (value) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.OpenSearch -> showOverlay(LibraryOverlay.Search)
            LibraryAction.CloseOverlay -> showOverlay(LibraryOverlay.None)
            is LibraryAction.QueryChanged -> {
                savedState[QUERY_KEY] = action.query
                _uiState.update { it.copy(query = action.query) }
            }
            is LibraryAction.SubmitSearch -> {
                savedState[QUERY_KEY] = action.query
                showOverlay(LibraryOverlay.None)
                _uiState.update { it.copy(query = action.query) }
                navigation.trySend(LibraryNavigationRequest.Search(action.query))
            }
            LibraryAction.SignIn -> navigation.trySend(LibraryNavigationRequest.SignIn)
            LibraryAction.OpenAccountMenu -> showOverlay(LibraryOverlay.Account)
            is LibraryAction.AccountSelected -> {
                val account = (_uiState.value.content as? LibraryContent.Ready)?.page?.account
                showOverlay(LibraryOverlay.None)
                when (action.action) {
                    com.hhst.youtubelite.account.domain.AccountMenuAction.ViewChannel ->
                        account?.let { navigation.trySend(LibraryNavigationRequest.Channel(it.channelId)) }
                    com.hhst.youtubelite.account.domain.AccountMenuAction.SwitchAccount ->
                        navigation.trySend(LibraryNavigationRequest.SignIn)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.Settings ->
                        navigation.trySend(LibraryNavigationRequest.Settings)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.About ->
                        navigation.trySend(LibraryNavigationRequest.About)
                }
            }
            LibraryAction.OpenNotifications -> navigation.trySend(LibraryNavigationRequest.OpenNotifications)
            LibraryAction.OpenHistory -> navigation.trySend(LibraryNavigationRequest.OpenHistory)
            LibraryAction.OpenPlaylists -> navigation.trySend(LibraryNavigationRequest.OpenPlaylists)
            LibraryAction.OpenWatchLater -> navigation.trySend(LibraryNavigationRequest.Playlist("WL"))
            LibraryAction.OpenLiked -> navigation.trySend(LibraryNavigationRequest.Playlist("LL"))
            LibraryAction.OpenDownloads -> navigation.trySend(LibraryNavigationRequest.OpenDownloads)
            is LibraryAction.OpenVideo -> navigation.trySend(LibraryNavigationRequest.Watch(action.videoId))
            is LibraryAction.OpenChannel -> navigation.trySend(LibraryNavigationRequest.Channel(action.channelId))
            is LibraryAction.OpenPlaylist -> navigation.trySend(LibraryNavigationRequest.Playlist(action.playlistId))
            is LibraryAction.OpenMenu -> showOverlay(LibraryOverlay.Menu(action.videoId))
            is LibraryAction.MenuSelected -> {
                val videoId = (_uiState.value.overlay as? LibraryOverlay.Menu)?.videoId
                showOverlay(LibraryOverlay.None)
                if (videoId != null) navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
            }
            is LibraryAction.Destination -> {
                if (action.tab == com.hhst.youtubelite.ui.components.MainTab.You) return
                navigation.trySend(LibraryNavigationRequest.Destination(action.tab))
            }
            LibraryAction.VoiceSearch -> navigation.trySend(LibraryNavigationRequest.VoiceSearch)
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? LibraryOverlay.Menu)?.videoId
        _uiState.update { it.copy(overlay = next) }
    }

    private fun refresh() {
        if (!signedIn) return
        loadJob?.cancel()
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = LibraryContent.Loading) }
            runCatching { repository.you() }.fold(
                onSuccess = { page -> if (request == generation) _uiState.update { it.copy(content = LibraryContent.Ready(page)) } },
                onFailure = { if (it is CancellationException) throw it
                    if (request == generation) _uiState.update { it.copy(content = LibraryContent.Failed) } },
            )
        }
    }

    companion object {
        private const val QUERY_KEY = "library_query"
    }
}

class HistoryViewModel(
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
    private val scenario: LibraryScenario,
    private var signedIn: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState(query = savedState[QUERY_KEY] ?: "", overlay = savedState.libraryOverlay()))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<LibraryNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<LibraryNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: Job? = null
    private var generation = 0

    init {
        if (!signedIn) _uiState.update { it.copy(content = HistoryContent.SignedOut(SignedOutPrompts.forContent("history"))) }
        else if (scenario == LibraryScenario.Loading) {
            _uiState.update { it.copy(content = HistoryContent.Loading) }
        } else refresh()
    }


    private var sessionKey: Long? = null

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        if (signedIn == value && !changed) return
        signedIn = value
        ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(content = HistoryContent.SignedOut(SignedOutPrompts.forContent("history"))) }
        if (value) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.OpenSearch -> showOverlay(LibraryOverlay.Search)
            LibraryAction.CloseOverlay -> showOverlay(LibraryOverlay.None)
            is LibraryAction.QueryChanged -> {
                savedState[QUERY_KEY] = action.query
                _uiState.update { it.copy(query = action.query) }
            }
            is LibraryAction.SubmitSearch -> {
                savedState[QUERY_KEY] = action.query
                showOverlay(LibraryOverlay.None)
                _uiState.update { it.copy(query = action.query) }
                navigation.trySend(LibraryNavigationRequest.Search(action.query))
            }
            LibraryAction.SignIn -> navigation.trySend(LibraryNavigationRequest.SignIn)
            is LibraryAction.OpenVideo -> navigation.trySend(LibraryNavigationRequest.Watch(action.videoId))
            is LibraryAction.OpenChannel -> navigation.trySend(LibraryNavigationRequest.Channel(action.channelId))
            is LibraryAction.OpenMenu -> showOverlay(LibraryOverlay.Menu(action.videoId))
            is LibraryAction.MenuSelected -> {
                val videoId = (_uiState.value.overlay as? LibraryOverlay.Menu)?.videoId
                showOverlay(LibraryOverlay.None)
                if (videoId != null) navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
            }
            else -> Unit
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? LibraryOverlay.Menu)?.videoId
        _uiState.update { it.copy(overlay = next) }
    }

    private fun refresh() {
        if (!signedIn) return
        loadJob?.cancel()
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = HistoryContent.Loading) }
            runCatching { repository.history() }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    val content = when {
                        page.entries.isEmpty() && scenario == LibraryScenario.HistoryEmpty -> HistoryContent.Empty()
                        page.entries.isEmpty() -> HistoryContent.Empty()
                        else -> HistoryContent.Ready(page)
                    }
                    _uiState.update { it.copy(content = content) }
                },
                onFailure = { if (it is CancellationException) throw it
                    if (request == generation) _uiState.update { it.copy(content = HistoryContent.Failed) } },
            )
        }
    }

    companion object {
        private const val QUERY_KEY = "history_query"
    }
}

class CollectionViewModel(
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
    private val scenario: LibraryScenario,
    private var signedIn: Boolean = true,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionUiState(query = savedState[QUERY_KEY] ?: "", overlay = savedState.libraryOverlay()))
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<LibraryNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<LibraryNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: Job? = null
    private var generation = 0

    init {
        if (!signedIn) _uiState.update { it.copy(content = CollectionContent.SignedOut(SignedOutPrompts.forContent("playlists"))) }
        else if (scenario == LibraryScenario.Loading) {
            _uiState.update { it.copy(content = CollectionContent.Loading) }
        } else refresh()
    }


    private var sessionKey: Long? = null

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        if (signedIn == value && !changed) return
        signedIn = value
        ++generation
        loadJob?.cancel()
        _uiState.update { it.copy(content = CollectionContent.SignedOut(SignedOutPrompts.forContent("playlists"))) }
        if (value) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.OpenSearch -> showOverlay(LibraryOverlay.Search)
            LibraryAction.CloseOverlay -> showOverlay(LibraryOverlay.None)
            is LibraryAction.QueryChanged -> {
                savedState[QUERY_KEY] = action.query
                _uiState.update { it.copy(query = action.query) }
            }
            is LibraryAction.SubmitSearch -> {
                savedState[QUERY_KEY] = action.query
                showOverlay(LibraryOverlay.None)
                _uiState.update { it.copy(query = action.query) }
                navigation.trySend(LibraryNavigationRequest.Search(action.query))
            }
            LibraryAction.SignIn -> navigation.trySend(LibraryNavigationRequest.SignIn)
            is LibraryAction.OpenPlaylist -> navigation.trySend(LibraryNavigationRequest.Playlist(action.playlistId))
            else -> Unit
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? LibraryOverlay.Menu)?.videoId
        _uiState.update { it.copy(overlay = next) }
    }

    private fun refresh() {
        if (!signedIn) return
        loadJob?.cancel()
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = CollectionContent.Loading) }
            runCatching { repository.playlists() }.fold(
                onSuccess = { playlists ->
                    if (request != generation) return@fold
                    val content = if (playlists.isEmpty()) CollectionContent.Empty() else CollectionContent.Ready(playlists)
                    _uiState.update { it.copy(content = content) }
                },
                onFailure = { if (it is CancellationException) throw it
                    if (request == generation) _uiState.update { it.copy(content = CollectionContent.Failed) } },
            )
        }
    }

    companion object {
        private const val QUERY_KEY = "collection_query"
    }
}

private fun SavedStateHandle.libraryOverlay(): LibraryOverlay = when (get<String>("overlay")) {
    "search" -> LibraryOverlay.Search
    "account" -> LibraryOverlay.Account
    "menu" -> get<String>("menuItem")?.let { LibraryOverlay.Menu(it) } ?: LibraryOverlay.None
    else -> LibraryOverlay.None
}
