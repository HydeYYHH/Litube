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
import com.hhst.youtubelite.core.AppNotice

/** Hosts the shorts pager. Fixture builds keep like/subscribe as typed requests; production writes through here. */
class ShortsViewModel(
    private val repository: ShortsRepository,
    private val savedState: SavedStateHandle,
    private val scenario: ShortsScenario,
    private val startItemId: String?,
    private val videoActions: com.hhst.youtubelite.watch.domain.VideoActions? = null,
    private val preloader: com.hhst.youtubelite.shorts.data.ShortsPreloader? = null,
    /** Shared save flow; null in fixtures, where the save sheet stays request-only. */
    private val saves: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ShortsUiState(
        startItemId = startItemId,
        currentItemId = savedState["currentItem"] ?: startItemId,
        overlay = savedState.get<String>("overlayItem")?.let { id ->
            if (savedState.get<String>("overlay") == "comments") ShortsOverlay.Comments(id)
            else ShortsOverlay.Menu(id)
        } ?: ShortsOverlay.None,
        live = videoActions != null,
    ))
    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private val consumedCursors = mutableSetOf<String>()
    private var actionJob: Job? = null
    private var hydrateJob: Job? = null
    private var readyItemId: String? = null

    /** Per-item hydration bookkeeping; only terminal states stop further reads. */
    private sealed interface Hydration {
        data object Running : Hydration
        data object Done : Hydration
        /** The read succeeded but the server has no detail for the item. */
        data object Missing : Hydration
        data class Failed(val attempts: Int) : Hydration
    }
    private val hydration = object : LinkedHashMap<String, Hydration>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hydration>?): Boolean = size > MAX_HYDRATION_STATES
    }
    private var generation = 0
    private var sessionGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation

    /** Identity-scoped content (the feed and subscription states) re-reads after an account switch. */
    fun updateSession(generation: Long) {
        if (generation == sessionGeneration) return
        sessionGeneration = generation
        overlay(ShortsOverlay.None)
        // The open sheet shows the previous account's playlist membership; a
        // toggle after the switch would write stale choices under the new one.
        saveSheet?.close()
        preloader?.cancel()
        savedState["currentItem"] = startItemId
        _uiState.update { it.copy(currentItemId = startItemId) }
        refresh()
    }
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()
    private val navigation = Channel<ShortsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<ShortsNavigationRequest> = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices: Flow<AppNotice> = notices.receiveAsFlow()

    /** Shared live save-sheet flow (search results pattern); null keeps the row out of the menu. */
    val saveSheet: com.hhst.youtubelite.saves.presentation.SaveSheetController? = saves?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }

    init {
        if (scenario == ShortsScenario.Loading) {
            _uiState.update { it.copy(content = ShortsContent.Loading) }
        } else refresh()
    }

    fun onAction(action: ShortsAction) {
        when (action) {
            ShortsAction.Retry -> refresh()
            ShortsAction.LoadMore -> loadMore()
            ShortsAction.RetryMore -> loadMore(retry = true)
            is ShortsAction.SelectItem -> {
                if (action.itemId != _uiState.value.currentItemId) readyItemId = null
                preloader?.select(action.itemId)
                savedState["currentItem"] = action.itemId
                _uiState.update { it.copy(currentItemId = action.itemId) }
                hydrateAround(action.itemId)
            }
            is ShortsAction.Subscribe -> {
                val actions = videoActions
                if (actions == null) navigation.trySend(ShortsNavigationRequest.Subscribe(action.channelId))
                else if (actionJob?.isActive != true) {
                    val target = _uiState.value.subscriptions[action.channelId] != true
                    actionJob = viewModelScope.launch {
                        _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending + action.channelId) }
                        try {
                            actions.subscribe(action.channelId, target)
                            _uiState.update { it.copy(subscriptions = it.subscriptions + (action.channelId to target),
                                subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                            notices.trySend(AppNotice.SignIn)
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                        catch (error: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                            notices.trySend(failure("shorts_subscribe", error, action.channelId, UNCONFIRMED))
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                        catch (error: Exception) {
                            notices.trySend(failure("shorts_subscribe", error, action.channelId, "Couldn't update this subscription. Try again."))
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                    }
                }
            }
            is ShortsAction.OpenChannel -> navigation.trySend(ShortsNavigationRequest.Channel(action.channelId))
            is ShortsAction.OpenComments -> overlay(ShortsOverlay.Comments(action.itemId))
            ShortsAction.CloseComments -> overlay(ShortsOverlay.None)
            ShortsAction.CloseMenu -> overlay(ShortsOverlay.None)
            is ShortsAction.OpenMenu -> overlay(ShortsOverlay.Menu(action.itemId))
            is ShortsAction.OpenSaveToPlaylist -> saveSheet?.open(action.itemId)
            is ShortsAction.MenuSelected -> {
                val itemId = (_uiState.value.overlay as? ShortsOverlay.Menu)?.itemId
                overlay(ShortsOverlay.None)
                if (itemId != null) navigation.trySend(ShortsNavigationRequest.MenuAction(itemId, action.action))
            }
            is ShortsAction.Share -> navigation.trySend(ShortsNavigationRequest.Share(action.itemId))
            is ShortsAction.Like -> rate(action.itemId, com.hhst.youtubelite.watch.domain.VideoRating.Like)
            is ShortsAction.Dislike -> rate(action.itemId, com.hhst.youtubelite.watch.domain.VideoRating.Dislike)
        }
    }

    private fun rate(itemId: String, selection: com.hhst.youtubelite.watch.domain.VideoRating) {
        val actions = videoActions
        if (actions == null) {
            navigation.trySend(ShortsNavigationRequest.Rate(itemId, like = selection == com.hhst.youtubelite.watch.domain.VideoRating.Like))
            return
        }
        if (actionJob?.isActive == true) return
        val previous = _uiState.value.ratings[itemId] ?: com.hhst.youtubelite.watch.domain.VideoRating.None
        val next = if (previous == selection) com.hhst.youtubelite.watch.domain.VideoRating.None else selection
        actionJob = viewModelScope.launch {
            try {
                actions.rate(itemId, next, previous)
                _uiState.update { it.copy(ratings = it.ratings + (itemId to next)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) { notices.trySend(AppNotice.SignIn) }
            catch (error: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) { notices.trySend(failure("shorts_rate", error, itemId, UNCONFIRMED)) }
            catch (error: Exception) { notices.trySend(failure("shorts_rate", error, itemId, "Couldn't update this video. Try again.")) }
        }
    }

    /** Copyable failure notice for an account write, carrying the sanitized report. */
    private fun failure(operation: String, error: Exception, itemId: String, message: String): AppNotice =
        com.hhst.youtubelite.diagnostics.writeFailureNotice(operation, error, mapOf("video_id" to itemId), message)

    /**
     * Hydrates the settled item and, at most, the next one. A swipe cancels the
     * previous round mid-flight; playback never waits on these reads.
     */
    private fun hydrateAround(itemId: String) {
        hydrateJob?.cancel()
        val request = generation
        val items = (_uiState.value.content as? ShortsContent.Ready)?.page?.items.orEmpty()
        val index = items.indexOfFirst { it.id == itemId }
        val next = items.getOrNull(index + 1)?.id.takeIf { index >= 0 }
        hydrateJob = viewModelScope.launch {
            hydrateOne(itemId, request)
            if (request != generation) return@launch
            next ?: return@launch
            hydrateOne(next, request)
        }
    }

    fun onPlaybackReady(itemId: String) {
        if (itemId != _uiState.value.currentItemId) return
        readyItemId = itemId
        preloadNext()
    }

    private fun preloadNext() {
        val state = _uiState.value
        val id = readyItemId?.takeIf { it == state.currentItemId } ?: return
        val items = (state.content as? ShortsContent.Ready)?.page?.items ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items.getOrNull(index + 1)?.id?.let { preloader?.preload(it) }
    }

    private suspend fun hydrateOne(itemId: String, request: Int) {
        val items = (_uiState.value.content as? ShortsContent.Ready)?.page?.items ?: return
        if (items.none { it.id == itemId }) return
        val previousAttempts = (hydration[itemId] as? Hydration.Failed)?.attempts ?: 0
        when (val existing = hydration[itemId]) {
            is Hydration.Running, Hydration.Missing -> return
            Hydration.Done -> if (itemId in _uiState.value.details) return
            is Hydration.Failed -> if (existing.attempts >= MAX_HYDRATION_ATTEMPTS) return
            null -> Unit
        }
        hydration[itemId] = Hydration.Running
        val detail = try {
            repository.hydrate(itemId)
        } catch (cancelled: CancellationException) {
            // A cancelled read must stay retryable, never a terminal state.
            hydration.remove(itemId)
            throw cancelled
        } catch (_: Exception) {
            hydration[itemId] = Hydration.Failed(previousAttempts + 1)
            return
        }
        if (detail == null) {
            hydration[itemId] = Hydration.Missing
            return
        }
        if (request != generation) return
        hydration[itemId] = Hydration.Done
        _uiState.update { state ->
            val ready = state.content as? ShortsContent.Ready ?: return@update state
            state.copy(
                content = ready.copy(page = ready.page.copy(items = ready.page.items.map {
                    if (it.id == itemId) it.fillFrom(detail.item) else it
                })),
                details = (state.details + (itemId to detail)).entries.toList().takeLast(MAX_DETAILS)
                    .associateTo(LinkedHashMap()) { it.key to it.value },
                // The server's subscription state feeds the map; the local click
                // write only lands after the same server confirms the change.
                subscriptions = if (detail.subscribed != null && detail.item.channelId.isNotBlank())
                    state.subscriptions + (detail.item.channelId to detail.subscribed)
                else state.subscriptions,
            )
        }
    }

    private companion object {
        const val UNCONFIRMED = "The server did not confirm this change. Please refresh before trying again."
        const val MAX_DETAILS = 24
        const val MAX_HYDRATION_STATES = 96
        const val MAX_HYDRATION_ATTEMPTS = 2
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

    private fun loadMore(retry: Boolean = false) {
        val current = (_uiState.value.content as? ShortsContent.Ready)?.page ?: return
        val cursor = current.nextKey ?: return
        if (appendJob?.isActive == true || (uiState.value.appendFailed && !retry)) return
        if (cursor in consumedCursors) return
        val request = generation
        _uiState.update { it.copy(appendLoading = true, appendFailed = false) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.shortsNext(cursor)
                if (request != generation) return@launch
                consumedCursors += cursor
                _uiState.update { state ->
                    val readyPage = (state.content as? ShortsContent.Ready)?.page ?: return@update state
                    val seen = readyPage.items.map { it.id }.toSet()
                    state.copy(appendLoading = false, appendFailed = false,
                        content = ShortsContent.Ready(readyPage.copy(
                            items = (readyPage.items + page.items.filter { it.id !in seen }).distinctBy { it.id },
                            nextKey = page.nextKey?.takeUnless { it in consumedCursors || page.items.isEmpty() })))
                }
                preloadNext()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) _uiState.update { it.copy(appendLoading = false, appendFailed = true) }
            }
        }
    }

    private fun refresh() {
        loadJob?.cancel()
        appendJob?.cancel()
        actionJob?.cancel()
        hydrateJob?.cancel()
        hydration.clear()
        consumedCursors.clear()
        preloader?.cancel()
        readyItemId = null
        val request = ++generation
        _uiState.update { it.copy(content = ShortsContent.Loading, appendLoading = false, appendFailed = false,
            ratings = emptyMap(), subscriptions = emptyMap(), subscriptionsPending = emptySet(), details = emptyMap()) }
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

    override fun onCleared() {
        preloader?.cancel()
        super.onCleared()
    }
}
