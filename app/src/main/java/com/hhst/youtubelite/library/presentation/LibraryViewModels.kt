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
import com.hhst.youtubelite.core.trafficChallenge

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
            !signedIn && !repository.supportsLocal -> _uiState.update { it.copy(content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
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
        if (value || repository.supportsLocal) refresh()
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
            LibraryAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is LibraryAction.OpenVerification -> navigation.trySend(LibraryNavigationRequest.OpenVerification(action.challengeUrl))
            // History-page management actions are unreachable from the You page.
            LibraryAction.OpenManageHistory, LibraryAction.PauseHistory, LibraryAction.ResumeHistory,
            LibraryAction.RequestClearHistory, LibraryAction.ConfirmClearHistory,
            LibraryAction.LoadHistoryNext, LibraryAction.LoadCollectionNext -> Unit
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
        if (!signedIn && !repository.supportsLocal) return
        loadJob?.cancel()
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = LibraryContent.Loading) }
            runCatching { repository.you() }.fold(
                onSuccess = { page -> if (request == generation) _uiState.update { it.copy(content = LibraryContent.Ready(page)) } },
                onFailure = { failure -> if (failure is CancellationException) throw failure
                    if (request != generation) return@fold
                    val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                        ?.let(::trafficChallenge)
                    _uiState.update { it.copy(content = LibraryContent.Failed, verification = challenge ?: it.verification) } },
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
    private var appendJob: Job? = null
    private var generation = 0

    init {
        if (!signedIn && !repository.supportsLocal) _uiState.update { it.copy(content = HistoryContent.SignedOut(SignedOutPrompts.forContent("history"))) }
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
        appendJob?.cancel()
        actionJob?.cancel()
        saveSheet?.close()
        savedState["confirmClear"] = false
        showOverlay(LibraryOverlay.None)
        _uiState.update { it.copy(confirmClear = false) }
        _uiState.update { it.copy(content = HistoryContent.SignedOut(SignedOutPrompts.forContent("history")),
            append = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        if (value || repository.supportsLocal) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is LibraryAction.OpenVerification -> navigation.trySend(LibraryNavigationRequest.OpenVerification(action.challengeUrl))
            LibraryAction.LoadHistoryNext -> loadHistoryNext()
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
            is LibraryAction.OpenPlaylist, is LibraryAction.Destination,
            LibraryAction.LoadCollectionNext -> Unit
        }
    }

    /** Appends the next history page; a failure keeps the loaded entries. */
    private fun loadHistoryNext() {
        val ready = _uiState.value.content as? HistoryContent.Ready ?: return
        val cursor = ready.page.nextKey ?: return
        if (_uiState.value.append == com.hhst.youtubelite.home.domain.AppendState.Loading) return
        appendJob?.cancel()
        val requestGeneration = generation
        _uiState.update { it.copy(append = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.historyNext(cursor)
                if (requestGeneration != generation) return@launch
                _uiState.update { current ->
                    val existing = (current.content as? HistoryContent.Ready)?.page ?: return@update current
                    current.copy(content = HistoryContent.Ready(existing.copy(
                        entries = (existing.entries + page.entries).distinctBy { it.id },
                        nextKey = page.nextKey.takeUnless { it == cursor })),
                        append = com.hhst.youtubelite.home.domain.AppendState.Idle)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation) _uiState.update {
                    it.copy(append = com.hhst.youtubelite.home.domain.AppendState.Failed)
                }
            }
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
        if (!signedIn && !repository.supportsLocal) return
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
                onFailure = { failure -> if (failure is CancellationException) throw failure
                    if (request != generation) return@fold
                    val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                        ?.let(::trafficChallenge)
                    _uiState.update { it.copy(content = HistoryContent.Failed, verification = challenge ?: it.verification) } },
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
    private var appendJob: Job? = null
    private var generation = 0

    init {
        if (!signedIn && !repository.supportsLocal) _uiState.update { it.copy(content = CollectionContent.SignedOut(SignedOutPrompts.forContent("playlists"))) }
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
        appendJob?.cancel()
        _uiState.update { it.copy(content = CollectionContent.SignedOut(SignedOutPrompts.forContent("playlists")),
            append = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        if (value || repository.supportsLocal) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is LibraryAction.OpenVerification -> navigation.trySend(LibraryNavigationRequest.OpenVerification(action.challengeUrl))
            LibraryAction.LoadCollectionNext -> loadCollectionNext()
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
            is LibraryAction.Destination, LibraryAction.LoadHistoryNext -> Unit
        }
    }

    /** Appends the next collection page; a failure keeps the loaded rows. */
    private fun loadCollectionNext() {
        val ready = _uiState.value.content as? CollectionContent.Ready ?: return
        val cursor = ready.nextKey ?: return
        if (_uiState.value.append == com.hhst.youtubelite.home.domain.AppendState.Loading) return
        appendJob?.cancel()
        val requestGeneration = generation
        _uiState.update { it.copy(append = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.playlistsNext(cursor)
                if (requestGeneration != generation) return@launch
                _uiState.update { current ->
                    val existing = (current.content as? CollectionContent.Ready) ?: return@update current
                    current.copy(content = existing.copy(
                        playlists = (existing.playlists + page.playlists).distinctBy { it.id },
                        nextKey = page.nextKey.takeUnless { it == cursor }),
                        append = com.hhst.youtubelite.home.domain.AppendState.Idle)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation) _uiState.update {
                    it.copy(append = com.hhst.youtubelite.home.domain.AppendState.Failed)
                }
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
        if (!signedIn && !repository.supportsLocal) return
        loadJob?.cancel()
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = CollectionContent.Loading) }
            runCatching { repository.playlists() }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    val content = if (page.playlists.isEmpty()) CollectionContent.Empty() else CollectionContent.Ready(page.playlists, page.nextKey)
                    _uiState.update { it.copy(content = content) }
                },
                onFailure = { failure -> if (failure is CancellationException) throw failure
                    if (request != generation) return@fold
                    val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                        ?.let(::trafficChallenge)
                    _uiState.update { it.copy(content = CollectionContent.Failed, verification = challenge ?: it.verification) } },
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

/** One typed retry after a completed `/sorry` verification; hosts call it with their VM. */
fun YouViewModel.verificationCompleted(challengeUrl: String?) {
    if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
    onAction(LibraryAction.DismissVerification)
    onAction(LibraryAction.Retry)
}

fun HistoryViewModel.verificationCompleted(challengeUrl: String?) {
    if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
    onAction(LibraryAction.DismissVerification)
    onAction(LibraryAction.Retry)
}

fun CollectionViewModel.verificationCompleted(challengeUrl: String?) {
    if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
    onAction(LibraryAction.DismissVerification)
    onAction(LibraryAction.Retry)
}
