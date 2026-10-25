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
    private var generation = 0
    private var sessionGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation

    /** Identity-scoped content (the feed and subscription states) re-reads after an account switch. */
    fun updateSession(generation: Long) {
        if (generation == sessionGeneration) return
        sessionGeneration = generation
        overlay(ShortsOverlay.None)
        savedState["currentItem"] = startItemId
        _uiState.update { it.copy(currentItemId = startItemId) }
        refresh()
    }
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()
    private val navigation = Channel<ShortsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<ShortsNavigationRequest> = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices: Flow<AppNotice> = notices.receiveAsFlow()

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
                savedState["currentItem"] = action.itemId
                _uiState.update { it.copy(currentItemId = action.itemId) }
            }
            is ShortsAction.Subscribe -> {
                val actions = videoActions
                if (actions == null) navigation.trySend(ShortsNavigationRequest.Subscribe(action.channelId))
                else if (actionJob?.isActive != true) {
                    val target = _uiState.value.subscriptions[action.channelId] != true
                    actionJob = viewModelScope.launch {
                        try {
                            actions.subscribe(action.channelId, target)
                            _uiState.update { it.copy(subscriptions = it.subscriptions + (action.channelId to target)) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) { notices.trySend(AppNotice.SignIn) }
                        catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) { notices.trySend(AppNotice.Message(UNCONFIRMED)) }
                        catch (_: Exception) { notices.trySend(AppNotice.Message("Couldn't update this subscription. Try again.")) }
                    }
                }
            }
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
            catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) { notices.trySend(AppNotice.Message(UNCONFIRMED)) }
            catch (_: Exception) { notices.trySend(AppNotice.Message("Couldn't update this video. Try again.")) }
        }
    }

    private companion object {
        const val UNCONFIRMED = "The server did not confirm this change. Please refresh before trying again."
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
        consumedCursors.clear()
        val request = ++generation
        _uiState.update { it.copy(content = ShortsContent.Loading, appendLoading = false, appendFailed = false,
            ratings = emptyMap(), subscriptions = emptyMap()) }
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
