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

import com.hhst.youtubelite.core.AppNotice

/** Hosts the You page. Library screens keep one VM per page, mirroring the search host. */
class YouViewModel(
    private val repository: LibraryRepository,
    private val savedState: SavedStateHandle,
    private val scenario: LibraryScenario,
    private var signedIn: Boolean,
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState(signedIn = signedIn, query = savedState[QUERY_KEY] ?: "", overlay = savedState.libraryOverlay()))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<LibraryNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<LibraryNavigationRequest> = navigation.receiveAsFlow()
    private val notices = kotlinx.coroutines.channels.Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices: kotlinx.coroutines.flow.Flow<AppNotice> = notices.receiveAsFlow()
    /** Live save flow for the history-shelf menu; null in fixture builds, where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }
    private var actionJob: Job? = null

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
        actionJob?.cancel()
        saveSheet?.close()
        showOverlay(LibraryOverlay.None)
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
                        // The signed-out menu relabels this row "Sign in"; the intent differs.
                        navigation.trySend(if (signedIn) LibraryNavigationRequest.SwitchAccount else LibraryNavigationRequest.SignIn)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.SignOut ->
                        navigation.trySend(LibraryNavigationRequest.SignOut)
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
                if (videoId != null) when {
                    action.action == HistoryMenuAction.Share ->
                        navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
                    saveSheet == null ->
                        navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
                    action.action == HistoryMenuAction.WatchLater -> saveSheet.saveWatchLater(videoId)
                    action.action == HistoryMenuAction.SaveToPlaylist -> saveSheet.open(videoId)
                    action.action == HistoryMenuAction.RemoveFromHistory -> removeFromHistory(videoId)
                    else -> Unit
                }
            }
            is LibraryAction.Destination -> {
                if (action.tab == com.hhst.youtubelite.ui.components.MainTab.You) return
                navigation.trySend(LibraryNavigationRequest.Destination(action.tab))
            }
            LibraryAction.VoiceSearch -> navigation.trySend(LibraryNavigationRequest.VoiceSearch)
            // History-page management actions are unreachable from the You page.
            LibraryAction.OpenManageHistory, LibraryAction.PauseHistory, LibraryAction.ResumeHistory,
            LibraryAction.RequestClearHistory, LibraryAction.ConfirmClearHistory -> Unit
        }
    }

    private fun removeFromHistory(videoId: String) {
        val entry = (_uiState.value.content as? LibraryContent.Ready)?.page?.history
            ?.firstOrNull { it.videoId == videoId } ?: return
        val token = entry.removeToken
        if (token.isNullOrBlank()) {
            notices.trySend(AppNotice.Message("The server offered no removal for this entry."))
            return
        }
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            try {
                repository.removeFromHistory(token)
                _uiState.update { state ->
                    val ready = state.content as? LibraryContent.Ready ?: return@update state
                    state.copy(content = ready.copy(page = ready.page.copy(
                        history = ready.page.history.filterNot { it.videoId == videoId })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                notices.trySend(AppNotice.SignIn)
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
            } catch (_: Exception) {
                notices.trySend(AppNotice.Message("Couldn't remove this entry. Try again."))
            }
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
            LibraryOverlay.ManageHistory -> "manage"
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
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState(query = savedState[QUERY_KEY] ?: "", overlay = savedState.libraryOverlay(),
        live = saveRepository != null, confirmClear = savedState.get<Boolean>("confirmClear") == true))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<LibraryNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<LibraryNavigationRequest> = navigation.receiveAsFlow()
    private val notices = kotlinx.coroutines.channels.Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices: kotlinx.coroutines.flow.Flow<AppNotice> = notices.receiveAsFlow()
    /** Live save flow for the entry menu; null in fixture builds, where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }
    private var actionJob: Job? = null

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
        actionJob?.cancel()
        saveSheet?.close()
        savedState["confirmClear"] = false
        showOverlay(LibraryOverlay.None)
        _uiState.update { it.copy(confirmClear = false) }
        _uiState.update { it.copy(content = HistoryContent.SignedOut(SignedOutPrompts.forContent("history"))) }
        if (value) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.OpenSearch -> showOverlay(LibraryOverlay.Search)
            LibraryAction.CloseOverlay -> {
                savedState["confirmClear"] = false
                _uiState.update { it.copy(confirmClear = false) }
                showOverlay(LibraryOverlay.None)
            }
            LibraryAction.OpenManageHistory -> showOverlay(LibraryOverlay.ManageHistory)
            LibraryAction.PauseHistory -> setHistoryPaused(true)
            LibraryAction.ResumeHistory -> setHistoryPaused(false)
            LibraryAction.RequestClearHistory -> {
                savedState["confirmClear"] = true
                _uiState.update { it.copy(confirmClear = true) }
            }
            LibraryAction.ConfirmClearHistory -> clearHistory()
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
                if (videoId != null) when {
                    action.action == HistoryMenuAction.Share ->
                        navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
                    saveSheet == null ->
                        navigation.trySend(LibraryNavigationRequest.MenuAction(videoId, action.action))
                    action.action == HistoryMenuAction.WatchLater -> saveSheet.saveWatchLater(videoId)
                    action.action == HistoryMenuAction.SaveToPlaylist -> saveSheet.open(videoId)
                    action.action == HistoryMenuAction.RemoveFromHistory -> removeFromHistory(videoId)
                    else -> Unit
                }
            }
            LibraryAction.VoiceSearch -> navigation.trySend(LibraryNavigationRequest.VoiceSearch)
            // Unreachable from the history screen: it has no account menu, tab row or playlist
            // rows. No `else`: a new action must fail to compile here.
            LibraryAction.OpenAccountMenu, is LibraryAction.AccountSelected,
            LibraryAction.OpenNotifications, LibraryAction.OpenHistory, LibraryAction.OpenPlaylists,
            LibraryAction.OpenWatchLater, LibraryAction.OpenLiked, LibraryAction.OpenDownloads,
            is LibraryAction.OpenPlaylist, is LibraryAction.Destination -> Unit
        }
    }

    private fun setHistoryPaused(paused: Boolean) {
        showOverlay(LibraryOverlay.None)
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            try {
                repository.setHistoryPaused(paused)
                notices.trySend(AppNotice.Message(
                    if (paused) "Watch history paused." else "Watch history resumed."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                notices.trySend(AppNotice.SignIn)
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again."))
            } catch (_: Exception) {
                notices.trySend(AppNotice.Message("Couldn't update watch history. Try again."))
            }
        }
    }

    private fun clearHistory() {
        savedState["confirmClear"] = false
        _uiState.update { it.copy(confirmClear = false) }
        showOverlay(LibraryOverlay.None)
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            try {
                repository.clearHistory()
                notices.trySend(AppNotice.Message("Watch history cleared."))
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                notices.trySend(AppNotice.SignIn)
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
            } catch (_: Exception) {
                notices.trySend(AppNotice.Message("Couldn't clear watch history. Try again."))
            }
        }
    }

    private fun removeFromHistory(videoId: String) {
        val entry = (_uiState.value.content as? HistoryContent.Ready)?.page?.entries
            ?.firstOrNull { it.videoId == videoId } ?: return
        val token = entry.removeToken
        if (token.isNullOrBlank()) {
            notices.trySend(AppNotice.Message("The server offered no removal for this entry."))
            return
        }
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            try {
                repository.removeFromHistory(token)
                _uiState.update { state ->
                    val ready = state.content as? HistoryContent.Ready ?: return@update state
                    state.copy(content = ready.copy(page = ready.page.copy(
                        entries = ready.page.entries.filterNot { it.videoId == videoId })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                notices.trySend(AppNotice.SignIn)
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
            } catch (_: Exception) {
                notices.trySend(AppNotice.Message("Couldn't remove this entry. Try again."))
            }
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
            LibraryOverlay.ManageHistory -> "manage"
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
            LibraryAction.VoiceSearch -> navigation.trySend(LibraryNavigationRequest.VoiceSearch)
            // Unreachable from the collection screen: it has no account menu, tab row, video
            // rows or history management. No `else`: a new action must fail to compile here.
            LibraryAction.OpenAccountMenu, is LibraryAction.AccountSelected,
            LibraryAction.OpenNotifications, LibraryAction.OpenHistory, LibraryAction.OpenPlaylists,
            LibraryAction.OpenWatchLater, LibraryAction.OpenLiked, LibraryAction.OpenDownloads,
            is LibraryAction.OpenVideo, is LibraryAction.OpenChannel, is LibraryAction.OpenMenu,
            is LibraryAction.MenuSelected, LibraryAction.OpenManageHistory, LibraryAction.PauseHistory,
            LibraryAction.ResumeHistory, LibraryAction.RequestClearHistory, LibraryAction.ConfirmClearHistory,
            is LibraryAction.Destination -> Unit
        }
    }

    private fun showOverlay(next: LibraryOverlay) {
        savedState["overlay"] = when (next) {
            LibraryOverlay.None -> "none"
            LibraryOverlay.Search -> "search"
            LibraryOverlay.Account -> "account"
            is LibraryOverlay.Menu -> "menu"
            LibraryOverlay.ManageHistory -> "manage"
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
    "manage" -> LibraryOverlay.ManageHistory
    else -> LibraryOverlay.None
}
