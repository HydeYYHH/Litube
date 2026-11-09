package com.hhst.youtubelite.library.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.WriteFeedback
import com.hhst.youtubelite.core.ContinuationHistory
import com.hhst.youtubelite.core.trafficChallenge
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.library.domain.CollectionContent
import com.hhst.youtubelite.library.domain.CollectionUiState
import com.hhst.youtubelite.library.domain.HistoryContent
import com.hhst.youtubelite.library.domain.HistoryEntry
import com.hhst.youtubelite.library.domain.HistoryMenuAction
import com.hhst.youtubelite.library.domain.HistoryPage
import com.hhst.youtubelite.library.domain.HistoryUiState
import com.hhst.youtubelite.library.domain.LibraryAction
import com.hhst.youtubelite.library.domain.LibraryContent
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import com.hhst.youtubelite.library.domain.LibraryOverlay
import com.hhst.youtubelite.library.domain.LibraryUiState
import com.hhst.youtubelite.library.domain.RemovedHistoryEntry
import com.hhst.youtubelite.library.domain.YouPage
import com.hhst.youtubelite.watch.domain.toProvisional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun List<HistoryEntry>.restoring(removed: RemovedHistoryEntry): List<HistoryEntry> {
    if (any { it.videoId == removed.entry.videoId }) return this
    val previous = indexOfFirst { it.videoId == removed.prevVideoId }
    val next = indexOfFirst { it.videoId == removed.nextVideoId }
    val index = when {
        previous >= 0 -> previous + 1
        next >= 0 -> next
        else -> removed.index.coerceIn(0, size)
    }
    return toMutableList().apply { add(index, removed.entry) }
}

/** Library screens keep one VM per page, mirroring the search host. */
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
    val writes = WriteFeedback()
    private var actionJob: Job? = null
    // History writes survive refreshes of this account.
    private var sessionEpoch = 0L

    private var loadJob: Job? = null
    private var historyShelfJob: Job? = null
    private var playlistsShelfJob: Job? = null
    private var generation = 0

    init {
        when {
            !signedIn && !repository.supportsLocal -> _uiState.update { it.copy(content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
            scenario == LibraryScenario.Loading -> _uiState.update { it.copy(content = LibraryContent.Loading) }
            else -> refresh()
        }
    }

    private fun deviceActive() = repository.supportsLocal && !signedIn


    private var sessionKey: Long? = null

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        if (signedIn == value && !changed) return
        signedIn = value
        sessionEpoch++
        writes.reset()
        ++generation
        loadJob?.cancel()
        loadJob = null
        historyShelfJob?.cancel()
        historyShelfJob = null
        playlistsShelfJob?.cancel()
        playlistsShelfJob = null
        actionJob?.cancel()
        actionJob = null
        saveSheet?.resetSession()
        showOverlay(LibraryOverlay.None)
        _uiState.update { it.copy(signedIn = value, verification = null, undo = null, historyRetrying = false, playlistsRetrying = false,
            content = LibraryContent.SignedOut(SignedOutPrompts.forContent("you"))) }
        if (value || repository.supportsLocal) refresh()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> refresh()
            LibraryAction.RetryHistoryShelf -> retryShelf(history = true)
            LibraryAction.RetryPlaylistsShelf -> retryShelf(history = false)
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
                        navigation.trySend(if (signedIn || repository.hasSavedAccounts) LibraryNavigationRequest.SwitchAccount else LibraryNavigationRequest.SignIn)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.SignOut ->
                        navigation.trySend(LibraryNavigationRequest.SignOut)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.Settings ->
                        navigation.trySend(LibraryNavigationRequest.Settings)
                    com.hhst.youtubelite.account.domain.AccountMenuAction.About ->
                        navigation.trySend(LibraryNavigationRequest.About)
                }
            }
            LibraryAction.OpenHistory -> navigation.trySend(LibraryNavigationRequest.OpenHistory)
            LibraryAction.OpenPlaylists -> navigation.trySend(LibraryNavigationRequest.OpenPlaylists)
            LibraryAction.OpenWatchLater -> navigation.trySend(LibraryNavigationRequest.Playlist(
                if (deviceActive()) com.hhst.youtubelite.data.media.LocalLibraryStore.WATCH_LATER else "WL"))
            // Liked has no device lane: it stays an account-only exit.
            LibraryAction.OpenLiked -> if (!deviceActive()) navigation.trySend(LibraryNavigationRequest.Playlist("LL"))
            LibraryAction.OpenQueue -> navigation.trySend(LibraryNavigationRequest.OpenQueue)
            LibraryAction.OpenDownloads -> navigation.trySend(LibraryNavigationRequest.OpenDownloads)
            is LibraryAction.OpenVideo -> {
                (_uiState.value.content as? LibraryContent.Ready)?.page?.history
                    ?.firstOrNull { it.videoId == action.videoId }
                    ?.let { com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(it.toProvisional()) }
                navigation.trySend(LibraryNavigationRequest.Watch(action.videoId))
            }
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
            LibraryAction.UndoHistoryRemove -> undoHistoryRemove()
            LibraryAction.DismissUndo -> _uiState.update { it.copy(undo = null) }
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
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = historyShelfJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                val removed = repository.removeFromHistory(token)
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                _uiState.update { state ->
                    val ready = state.content as? LibraryContent.Ready
                    state.copy(content = ready?.copy(page = ready.page.copy(
                        history = ready.page.history.filterNot { it.videoId == videoId })) ?: state.content,
                        undo = removed)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
                }
            } catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't remove this entry. Try again."))
                }
            }
        }
    }

    /** Restore only the removed row, keeping the current shelves and list position. */
    private fun undoHistoryRemove() {
        val removed = _uiState.value.undo ?: return
        if (actionJob?.isActive == true) return
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = historyShelfJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                repository.restoreHistoryEntry(removed)
                currentCoroutineContext().ensureActive()
                if (request == sessionEpoch) _uiState.update { state ->
                    val ready = state.content as? LibraryContent.Ready
                    state.copy(content = ready?.copy(page = ready.page.copy(history = ready.page.history.restoring(removed))) ?: state.content,
                        undo = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't restore this entry."))
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

    private fun retryShelf(history: Boolean) {
        val ready = _uiState.value.content as? LibraryContent.Ready ?: return
        if (loadJob?.isActive == true) return
        if (history) {
            if (!ready.page.historyFailed || historyShelfJob?.isActive == true) return
        } else if (!ready.page.playlistsFailed || playlistsShelfJob?.isActive == true) return
        val request = generation
        val previousWrite = if (history) actionJob else null
        _uiState.update { if (history) it.copy(historyRetrying = true) else it.copy(playlistsRetrying = true) }
        val job = viewModelScope.launch {
            try {
                previousWrite?.join()
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                val merge: (YouPage) -> YouPage = if (history) {
                    repository.history().let { loaded ->
                        { page: YouPage -> page.copy(history = loaded.entries, historyFailed = false) }
                    }
                } else {
                    repository.playlists().let { loaded ->
                        { page: YouPage -> page.copy(playlists = loaded.playlists, playlistsFailed = false) }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                _uiState.update { state ->
                    val current = state.content as? LibraryContent.Ready ?: return@update state
                    state.copy(content = current.copy(page = merge(current.page)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Keep the failed shelf's retry available without replacing the usable page.
            } finally {
                if (request == generation) _uiState.update {
                    if (history) it.copy(historyRetrying = false) else it.copy(playlistsRetrying = false)
                }
            }
        }
        if (history) historyShelfJob = job else playlistsShelfJob = job
    }

    private fun refresh() {
        if (!signedIn && !repository.supportsLocal) return
        loadJob?.cancel()
        historyShelfJob?.cancel()
        playlistsShelfJob?.cancel()
        val request = ++generation
        val previousWrite = actionJob
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = LibraryContent.Loading, historyRetrying = false, playlistsRetrying = false) }
            runCatching {
                previousWrite?.join()
                currentCoroutineContext().ensureActive()
                repository.you().also { currentCoroutineContext().ensureActive() }
            }.fold(
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
    val writes = WriteFeedback()
    private var actionJob: Job? = null
    // History writes survive refreshes of this account.
    private var sessionEpoch = 0L

    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var generation = 0
    private var emptyAppends = 0
    private val continuations = ContinuationHistory()

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
        sessionEpoch++
        writes.reset()
        ++generation
        loadJob?.cancel()
        loadJob = null
        appendJob?.cancel()
        appendJob = null
        actionJob?.cancel()
        actionJob = null
        saveSheet?.resetSession()
        savedState["confirmClear"] = false
        showOverlay(LibraryOverlay.None)
        _uiState.update { it.copy(confirmClear = false, undo = null, verification = null) }
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
            is LibraryAction.OpenVideo -> {
                (_uiState.value.content as? HistoryContent.Ready)?.page?.entries
                    .orEmpty()
                    .firstOrNull { it.videoId == action.videoId }
                    ?.let { com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(it.toProvisional()) }
                navigation.trySend(LibraryNavigationRequest.Watch(action.videoId))
            }
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
            LibraryAction.UndoHistoryRemove -> undoHistoryRemove()
            LibraryAction.DismissUndo -> _uiState.update { it.copy(undo = null) }
            // Unreachable from the history screen: it has no account menu, tab row or playlist
            // rows. No `else`: a new action must fail to compile here.
            LibraryAction.OpenAccountMenu, is LibraryAction.AccountSelected,
            LibraryAction.OpenHistory, LibraryAction.OpenPlaylists,
            LibraryAction.OpenWatchLater, LibraryAction.OpenLiked, LibraryAction.OpenDownloads, LibraryAction.OpenQueue,
            is LibraryAction.OpenPlaylist, is LibraryAction.Destination,
            LibraryAction.LoadCollectionNext -> Unit
            LibraryAction.RetryHistoryShelf, LibraryAction.RetryPlaylistsShelf -> Unit
        }
    }

    /** On failure the loaded entries stay; only the append state reports failure. */
    private fun loadHistoryNext() {
        val ready = _uiState.value.content as? HistoryContent.Ready ?: return
        val cursor = ready.page.nextKey ?: return
        if (_uiState.value.append == com.hhst.youtubelite.home.domain.AppendState.Loading) return
        appendJob?.cancel()
        val requestGeneration = generation
        val previousWrite = actionJob
        _uiState.update { it.copy(append = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                previousWrite?.join()
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                val page = repository.historyNext(cursor)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                val existingIds = ready.page.entries.mapTo(hashSetOf()) { it.id }
                emptyAppends = if (page.entries.any { it.id !in existingIds }) 0 else emptyAppends + 1
                val nextKey = continuations.nextAfter(cursor, page.nextKey)
                val nextAppend = if (nextKey != null && emptyAppends >= MAX_EMPTY_APPENDS)
                    com.hhst.youtubelite.home.domain.AppendState.Failed else com.hhst.youtubelite.home.domain.AppendState.Idle
                _uiState.update { current ->
                    val existing = (current.content as? HistoryContent.Ready)?.page ?: return@update current
                    current.copy(content = HistoryContent.Ready(existing.copy(
                        entries = (existing.entries + page.entries).distinctBy { it.id },
                        nextKey = nextKey)),
                        append = nextAppend)
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
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = appendJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                repository.setHistoryPaused(paused)
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                notices.trySend(AppNotice.Message(
                    if (paused) "Watch history paused." else "Watch history resumed."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again."))
                }
            } catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't update watch history. Try again."))
                }
            }
        }
    }

    private fun clearHistory() {
        if (actionJob?.isActive == true) return
        savedState["confirmClear"] = false
        _uiState.update { it.copy(confirmClear = false) }
        showOverlay(LibraryOverlay.None)
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = appendJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                repository.clearHistory()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                ++generation
                loadJob?.cancel()
                appendJob?.cancel()
                emptyAppends = 0
                continuations.clear()
                _uiState.update { it.copy(content = HistoryContent.Empty(), undo = null,
                    append = com.hhst.youtubelite.home.domain.AppendState.Idle) }
                notices.trySend(AppNotice.Message("Watch history cleared."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
                }
            } catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't clear watch history. Try again."))
                }
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
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = appendJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                val removed = repository.removeFromHistory(token)
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                _uiState.update { state ->
                    val ready = state.content as? HistoryContent.Ready
                    state.copy(content = ready?.copy(page = ready.page.copy(
                        entries = ready.page.entries.filterNot { it.videoId == videoId })) ?: state.content,
                        undo = removed)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.SignIn)
                }
            } catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("The server did not confirm this removal. Please refresh before trying again."))
                }
            } catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't remove this entry. Try again."))
                }
            }
        }
    }

    /** Restore only the removed row; do not discard already loaded history pages. */
    private fun undoHistoryRemove() {
        val removed = _uiState.value.undo ?: return
        if (actionJob?.isActive == true) return
        val request = sessionEpoch
        val previousRead = loadJob
        val previousPage = appendJob
        actionJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                previousPage?.join()
                currentCoroutineContext().ensureActive()
                if (request != sessionEpoch) return@launch
                repository.restoreHistoryEntry(removed)
                currentCoroutineContext().ensureActive()
                if (request == sessionEpoch) _uiState.update { state ->
                    val ready = state.content as? HistoryContent.Ready
                    val page = ready?.page ?: if (state.content is HistoryContent.Empty) HistoryPage(emptyList()) else null
                    state.copy(content = page?.let { HistoryContent.Ready(it.copy(entries = it.entries.restoring(removed))) }
                        ?: state.content, undo = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == sessionEpoch) {
                    notices.trySend(AppNotice.Message("Couldn't restore this entry."))
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
        appendJob?.cancel()
        emptyAppends = 0
        val request = ++generation
        val previousWrite = actionJob
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = HistoryContent.Loading, append = com.hhst.youtubelite.home.domain.AppendState.Idle) }
            runCatching {
                previousWrite?.join()
                currentCoroutineContext().ensureActive()
                repository.history().also { currentCoroutineContext().ensureActive() }
            }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    continuations.clear()
                    val content = when {
                        page.entries.isEmpty() && page.nextKey == null -> HistoryContent.Empty()
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
        private const val MAX_EMPTY_APPENDS = 3
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
    private var emptyAppends = 0
    private val continuations = ContinuationHistory()

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
        _uiState.update { it.copy(verification = null, content = CollectionContent.SignedOut(SignedOutPrompts.forContent("playlists")),
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
            LibraryAction.OpenHistory, LibraryAction.OpenPlaylists,
            LibraryAction.OpenWatchLater, LibraryAction.OpenLiked, LibraryAction.OpenDownloads, LibraryAction.OpenQueue,
            is LibraryAction.OpenVideo, is LibraryAction.OpenChannel, is LibraryAction.OpenMenu,
            is LibraryAction.MenuSelected, LibraryAction.OpenManageHistory, LibraryAction.PauseHistory,
            LibraryAction.ResumeHistory, LibraryAction.RequestClearHistory, LibraryAction.ConfirmClearHistory,
            LibraryAction.UndoHistoryRemove, LibraryAction.DismissUndo,
            LibraryAction.RetryHistoryShelf, LibraryAction.RetryPlaylistsShelf,
            is LibraryAction.Destination, LibraryAction.LoadHistoryNext -> Unit
        }
    }

    /** On failure the loaded rows stay; only the append state reports failure. */
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
                val existingIds = ready.playlists.mapTo(hashSetOf()) { it.id }
                emptyAppends = if (page.playlists.any { it.id !in existingIds }) 0 else emptyAppends + 1
                val nextKey = continuations.nextAfter(cursor, page.nextKey)
                val nextAppend = if (nextKey != null && emptyAppends >= MAX_EMPTY_APPENDS)
                    com.hhst.youtubelite.home.domain.AppendState.Failed else com.hhst.youtubelite.home.domain.AppendState.Idle
                _uiState.update { current ->
                    val existing = (current.content as? CollectionContent.Ready) ?: return@update current
                    current.copy(content = existing.copy(
                        playlists = (existing.playlists + page.playlists).distinctBy { it.id },
                        nextKey = nextKey),
                        append = nextAppend)
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
        appendJob?.cancel()
        emptyAppends = 0
        val request = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(content = CollectionContent.Loading, append = com.hhst.youtubelite.home.domain.AppendState.Idle) }
            runCatching { repository.playlists() }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    continuations.clear()
                    val content = if (page.playlists.isEmpty() && page.nextKey == null) CollectionContent.Empty()
                        else CollectionContent.Ready(page.playlists, page.nextKey)
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
        private const val MAX_EMPTY_APPENDS = 3
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
