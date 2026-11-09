package com.hhst.youtubelite.shorts.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.hhst.youtubelite.core.AppNotice

/** Fixture builds keep like/subscribe as typed requests; production writes through here. */
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
    private val ratingJobs = mutableMapOf<String, Job>()
    private val subscriptionJobs = mutableMapOf<String, Job>()
    private var hydrateJob: Job? = null
    private val upcomingHydration = mutableMapOf<String, Job>()
    private var readyItemId: String? = null

    /** Per-item hydration bookkeeping; retries are bounded within one feed generation. */
    private sealed interface Hydration {
        class Running : Hydration
        data object Done : Hydration
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
        saveSheet?.resetSession()
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
                trimUpcomingHydration(action.itemId)
                hydrateSelected(action.itemId)
            }
            is ShortsAction.Subscribe -> {
                val actions = videoActions
                if (actions == null) navigation.trySend(ShortsNavigationRequest.Subscribe(action.channelId))
                else if (subscriptionJobs[action.channelId]?.isActive != true) {
                    val target = _uiState.value.subscriptions[action.channelId] != true
                    val request = generation
                    _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending + action.channelId) }
                    subscriptionJobs[action.channelId] = viewModelScope.launch {
                        try {
                            actions.subscribe(action.channelId, target)
                            if (request != generation) return@launch
                            _uiState.update { it.copy(subscriptions = it.subscriptions + (action.channelId to target),
                                subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                            if (request != generation) return@launch
                            notices.trySend(AppNotice.SignIn)
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                        catch (error: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                            if (request != generation) return@launch
                            notices.trySend(failure("shorts_subscribe", error, action.channelId, UNCONFIRMED))
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                        catch (error: Exception) {
                            if (request != generation) return@launch
                            notices.trySend(failure("shorts_subscribe", error, action.channelId, "Couldn't update this subscription. Try again."))
                            _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                        finally {
                            if (request == generation) _uiState.update { it.copy(subscriptionsPending = it.subscriptionsPending - action.channelId) }
                        }
                    }
                    subscriptionJobs[action.channelId]?.let { job ->
                        job.invokeOnCompletion { if (subscriptionJobs[action.channelId] === job) subscriptionJobs.remove(action.channelId) }
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
        if (ratingJobs[itemId]?.isActive == true) return
        val previous = _uiState.value.ratings[itemId] ?: com.hhst.youtubelite.watch.domain.VideoRating.None
        val next = if (previous == selection) com.hhst.youtubelite.watch.domain.VideoRating.None else selection
        _uiState.update { it.copy(ratingsPending = it.ratingsPending + itemId) }
        val request = generation
        ratingJobs[itemId] = viewModelScope.launch {
            try {
                actions.rate(itemId, next, previous)
                if (request != generation) return@launch
                _uiState.update { it.copy(ratings = it.ratings + (itemId to next)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                if (request == generation) notices.trySend(AppNotice.SignIn)
            }
            catch (error: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                if (request == generation) notices.trySend(failure("shorts_rate", error, itemId, UNCONFIRMED))
            }
            catch (error: Exception) {
                if (request == generation) notices.trySend(failure("shorts_rate", error, itemId, "Couldn't update this video. Try again."))
            }
            finally { if (request == generation) _uiState.update { it.copy(ratingsPending = it.ratingsPending - itemId) } }
        }
        ratingJobs[itemId]?.let { job ->
            job.invokeOnCompletion { if (ratingJobs[itemId] === job) ratingJobs.remove(itemId) }
        }
    }

    /** Copyable failure notice for an account write, carrying the sanitized report. */
    private fun failure(operation: String, error: Exception, itemId: String, message: String): AppNotice =
        com.hhst.youtubelite.diagnostics.writeFailureNotice(operation, error, mapOf("video_id" to itemId), message)

    /** Selected details yield to startup, or take over an already-running neighbor read. */
    private fun hydrateSelected(itemId: String, immediate: Boolean = false) {
        if (upcomingHydration[itemId]?.isActive == true) {
            hydrateJob?.cancel()
            hydrateJob = null
            return
        }
        if (immediate && hydration[itemId] is Hydration.Running) return
        hydrateJob?.cancel()
        val request = generation
        hydrateJob = viewModelScope.launch {
            if (!immediate && readyItemId != itemId) delay(DETAILS_STARTUP_GRACE_MS)
            hydrateWithSelectedRetry(itemId, request)
        }
    }

    private suspend fun hydrateWithSelectedRetry(itemId: String, request: Int) {
        hydrateOne(itemId, request)
        val failed = hydration[itemId] as? Hydration.Failed
        if (failed != null && failed.attempts < MAX_HYDRATION_ATTEMPTS &&
            request == generation && itemId == _uiState.value.currentItemId) {
            delay(DETAILS_RETRY_DELAY_MS)
            hydrateOne(itemId, request)
        }
    }

    fun onPlaybackReady(itemId: String) {
        if (itemId != _uiState.value.currentItemId) return
        if (readyItemId == itemId) return
        readyItemId = itemId
        hydrateSelected(itemId, immediate = true)
        preloadNext()
    }

    /** Only the settled item may advance; an append can call this again when ready. */
    fun onPlaybackEnded(itemId: String) {
        val state = _uiState.value
        if (state.currentItemId != itemId || state.overlay != ShortsOverlay.None) return
        val page = (state.content as? ShortsContent.Ready)?.page ?: return
        val index = page.items.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val next = page.items.getOrNull(index + 1)
        if (next == null) loadMore()
        else onAction(ShortsAction.SelectItem(next.id))
    }

    private fun preloadNext() {
        val state = _uiState.value
        val id = readyItemId?.takeIf { it == state.currentItemId } ?: return
        val items = (state.content as? ShortsContent.Ready)?.page?.items ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items.getOrNull(index + 1)?.id?.let { preloader?.preload(it) }
        if (index < 0) return
        val request = generation
        // Only after a real frame: two bounded metadata reads overlap the current
        // short and survive a swipe onto their item; media bytes warm one ahead.
        items.drop(index + 1).take(METADATA_LOOKAHEAD).forEach { item ->
            if (item.id in upcomingHydration || hydration[item.id] is Hydration.Running ||
                hydration[item.id] == Hydration.Done && item.id in state.details ||
                hydration[item.id] is Hydration.Failed) return@forEach
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) { hydrateWithSelectedRetry(item.id, request) }
            upcomingHydration[item.id] = job
            job.invokeOnCompletion { if (upcomingHydration[item.id] === job) upcomingHydration.remove(item.id) }
            job.start()
        }
    }

    private fun trimUpcomingHydration(selected: String) {
        val items = (_uiState.value.content as? ShortsContent.Ready)?.page?.items.orEmpty()
        val index = items.indexOfFirst { it.id == selected }
        val keep = if (index < 0) setOf(selected) else items.drop(index).take(METADATA_LOOKAHEAD + 1).map { it.id }.toSet()
        upcomingHydration.keys.filter { it !in keep }.forEach { id ->
            val job = upcomingHydration.remove(id)
            if (hydration[id] is Hydration.Running) hydration.remove(id)
            job?.cancel()
        }
    }

    private suspend fun hydrateOne(itemId: String, request: Int) {
        val items = (_uiState.value.content as? ShortsContent.Ready)?.page?.items ?: return
        if (items.none { it.id == itemId }) return
        val previousAttempts = (hydration[itemId] as? Hydration.Failed)?.attempts ?: 0
        when (val existing = hydration[itemId]) {
            is Hydration.Running -> return
            Hydration.Done -> if (itemId in _uiState.value.details) return
            is Hydration.Failed -> if (existing.attempts >= MAX_HYDRATION_ATTEMPTS) return
            null -> Unit
        }
        val running = Hydration.Running()
        hydration[itemId] = running
        val detail = try {
            repository.hydrate(itemId).also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            // A cancelled read must stay retryable, never a terminal state.
            if (request == generation && hydration[itemId] === running) hydration.remove(itemId)
            throw cancelled
        } catch (_: Exception) {
            if (request == generation) hydration[itemId] = Hydration.Failed(previousAttempts + 1)
            return
        }
        if (request != generation) return
        if (detail == null || detail.item.id != itemId) {
            hydration[itemId] = Hydration.Failed(previousAttempts + 1)
            return
        }
        hydration[itemId] = Hydration.Done
        _uiState.update { state ->
            val ready = state.content as? ShortsContent.Ready ?: return@update state
            state.copy(
                content = ready.copy(page = ready.page.copy(items = ready.page.items.map {
                    if (it.id == itemId) it.fillFrom(detail.item) else it
                })),
                details = (state.details + (itemId to detail)).entries.toList().takeLast(MAX_DETAILS)
                    .associateTo(LinkedHashMap()) { it.key to it.value },
                // Server subscription state feeds the map; a local click write
                // only lands after the same server confirms the change.
                subscriptions = if (detail.subscribed != null && detail.item.channelId.isNotBlank() &&
                    detail.item.channelId !in state.subscriptionsPending && detail.item.channelId !in state.subscriptions)
                    state.subscriptions + (detail.item.channelId to detail.subscribed)
                else state.subscriptions,
                ratings = if (detail.rating != null && itemId !in state.ratingsPending && itemId !in state.ratings)
                    state.ratings + (itemId to detail.rating)
                else state.ratings,
            )
        }
    }

    private companion object {
        const val UNCONFIRMED = "The server did not confirm this change. Please refresh before trying again."
        const val MAX_DETAILS = 24
        const val METADATA_LOOKAHEAD = 2
        const val MAX_HYDRATION_STATES = 96
        const val MAX_HYDRATION_ATTEMPTS = 2
        const val DETAILS_STARTUP_GRACE_MS = 3_000L
        const val DETAILS_RETRY_DELAY_MS = 750L
    }

    private fun overlay(next: ShortsOverlay) {
        when (next) {
            is ShortsOverlay.Menu -> hydrateSelected(next.itemId, immediate = true)
            is ShortsOverlay.Comments -> hydrateSelected(next.itemId, immediate = true)
            ShortsOverlay.None -> Unit
        }
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
                    val merged = readyPage.items.associateByTo(linkedMapOf()) { it.id }
                    page.items.forEach { item -> merged[item.id] = merged[item.id]?.fillFrom(item) ?: item }
                    state.copy(appendLoading = false, appendFailed = false,
                        content = ShortsContent.Ready(readyPage.copy(
                            items = merged.values.toList(),
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
        ratingJobs.values.toList().forEach(Job::cancel)
        subscriptionJobs.values.toList().forEach(Job::cancel)
        ratingJobs.clear()
        subscriptionJobs.clear()
        hydrateJob?.cancel()
        upcomingHydration.values.toList().forEach(Job::cancel)
        upcomingHydration.clear()
        hydration.clear()
        consumedCursors.clear()
        // Initial entry can claim preparation started while browsing; explicit
        // retries discard obsolete work, identity changes cancel above.
        if (generation > 0) preloader?.cancel()
        readyItemId = null
        val request = ++generation
        _uiState.update { it.copy(content = ShortsContent.Loading, appendLoading = false, appendFailed = false,
            ratings = emptyMap(), subscriptions = emptyMap(), subscriptionsPending = emptySet(), ratingsPending = emptySet(), details = emptyMap()) }
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
