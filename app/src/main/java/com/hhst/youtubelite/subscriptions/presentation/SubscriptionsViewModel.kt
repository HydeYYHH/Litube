package com.hhst.youtubelite.subscriptions.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.data.SubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.data.subscriptionsSignedOutPrompt
import com.hhst.youtubelite.subscriptions.domain.*
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

/** Hosts the subscriptions feed and its channel list. */
class SubscriptionsViewModel(
    private val repository: SubscriptionsRepository,
    private val savedState: SavedStateHandle,
    private val scenario: SubscriptionsScenario,
    private var signedIn: Boolean,
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SubscriptionsUiState(signedIn = signedIn, query = savedState[QUERY_KEY] ?: "",
            overlay = when (savedState.get<String>("overlay")) {
                "search" -> SubscriptionsOverlay.Search
                "channels" -> SubscriptionsOverlay.Channels
                "menu" -> savedState.get<String>("menuItem")?.let { SubscriptionsOverlay.Menu(it) } ?: SubscriptionsOverlay.None
                else -> SubscriptionsOverlay.None
            },
            selectedChip = (SubscriptionsChip.entries.firstOrNull { it.name == savedState.get<String>("chip") } ?: SubscriptionsChip.All)
                .takeIf { SubscriptionsFilter.from(it) in repository.supportedFilters } ?: SubscriptionsChip.All,
            chips = repository.supportedFilters.map { it.chip },
            live = saveRepository != null,
            content = if (scenario == SubscriptionsScenario.Content) SubscriptionsContent.Loading
            else scenario.initialContent(subscriptionsSignedOutPrompt()))
    )
    val uiState: StateFlow<SubscriptionsUiState> = _uiState.asStateFlow()
    private val navigation = kotlinx.coroutines.channels.Channel<SubscriptionsNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: kotlinx.coroutines.flow.Flow<SubscriptionsNavigationRequest> = navigation.receiveAsFlow()
    private val notices = kotlinx.coroutines.channels.Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices: kotlinx.coroutines.flow.Flow<AppNotice> = notices.receiveAsFlow()
    /** Live save flow for the item menu; null in fixture builds, where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var channelsJob: Job? = null
    private var generation = 0
    /** Channel reads follow the account, not the chip: a feed refresh never invalidates them. */
    private var channelsGeneration = 0

    init {
        when {
            !signedIn -> _uiState.update { it.copy(content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
            scenario == SubscriptionsScenario.Content -> {
                refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
                loadChannels()
            }
            else -> Unit
        }
    }

    private var sessionKey: Long? = null

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        if (signedIn == value && !changed) return
        signedIn = value
        ++generation
        ++channelsGeneration
        loadJob?.cancel()
        pageJob?.cancel()
        channelsJob?.cancel()
        saveSheet?.close()
        _uiState.update { it.copy(signedIn = value, channels = SubscriptionsChannels.Loading,
            content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
        if (value) {
            refresh(SubscriptionsFilter.from(uiState.value.selectedChip))
            loadChannels()
        }
    }

    fun onAction(action: SubscriptionsAction) {
        when (action) {
            SubscriptionsAction.Retry -> refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
            SubscriptionsAction.LoadNext -> loadNext()
            SubscriptionsAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is SubscriptionsAction.OpenVerification -> navigation.trySend(SubscriptionsNavigationRequest.OpenVerification(action.challengeUrl))
            SubscriptionsAction.OpenSearch -> showOverlay(SubscriptionsOverlay.Search)
            SubscriptionsAction.CloseOverlay -> showOverlay(SubscriptionsOverlay.None)
            is SubscriptionsAction.QueryChanged -> {
                savedState[QUERY_KEY] = action.query
                _uiState.update { it.copy(query = action.query) }
            }
            is SubscriptionsAction.SubmitSearch -> {
                savedState[QUERY_KEY] = action.query
                showOverlay(SubscriptionsOverlay.None)
                _uiState.update { it.copy(query = action.query) }
                navigation.trySend(SubscriptionsNavigationRequest.Search(action.query))
            }
            is SubscriptionsAction.SelectChip -> {
                if (SubscriptionsFilter.from(action.chip) !in repository.supportedFilters) return
                savedState["chip"] = action.chip.name
                _uiState.update { it.copy(selectedChip = action.chip) }
                refresh(SubscriptionsFilter.from(action.chip))
            }
            SubscriptionsAction.ShowChannels -> {
                showOverlay(SubscriptionsOverlay.Channels)
                if (_uiState.value.channels is SubscriptionsChannels.Failed) loadChannels()
            }
            SubscriptionsAction.CloseChannels -> showOverlay(SubscriptionsOverlay.None)
            SubscriptionsAction.RetryChannels -> loadChannels()
            is SubscriptionsAction.OpenVideo -> navigation.trySend(SubscriptionsNavigationRequest.Watch(action.videoId))
            is SubscriptionsAction.OpenChannel -> navigation.trySend(SubscriptionsNavigationRequest.Channel(action.channelId))
            is SubscriptionsAction.OpenMenu -> showOverlay(SubscriptionsOverlay.Menu(action.videoId))
            is SubscriptionsAction.MenuSelected -> {
                val videoId = (_uiState.value.overlay as? SubscriptionsOverlay.Menu)?.videoId
                showOverlay(SubscriptionsOverlay.None)
                if (videoId != null) when {
                    action.action == FeedMenuAction.Share ->
                        navigation.trySend(SubscriptionsNavigationRequest.MenuAction(videoId, action.action))
                    saveSheet == null ->
                        navigation.trySend(SubscriptionsNavigationRequest.MenuAction(videoId, action.action))
                    action.action == FeedMenuAction.WatchLater -> saveSheet.saveWatchLater(videoId)
                    action.action == FeedMenuAction.SaveToPlaylist -> saveSheet.open(videoId)
                    else -> Unit
                }
            }
            is SubscriptionsAction.Destination -> {
                if (action.tab == com.hhst.youtubelite.ui.components.MainTab.Subscriptions) return
                navigation.trySend(SubscriptionsNavigationRequest.Destination(action.tab))
            }
            SubscriptionsAction.SignIn -> navigation.trySend(SubscriptionsNavigationRequest.SignIn)
            SubscriptionsAction.Manage -> navigation.trySend(SubscriptionsNavigationRequest.Manage)
            SubscriptionsAction.VoiceSearch -> navigation.trySend(SubscriptionsNavigationRequest.VoiceSearch)
        }
    }

    private fun showOverlay(next: SubscriptionsOverlay) {
        savedState["overlay"] = when (next) {
            SubscriptionsOverlay.None -> "none"
            SubscriptionsOverlay.Search -> "search"
            SubscriptionsOverlay.Channels -> "channels"
            is SubscriptionsOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? SubscriptionsOverlay.Menu)?.videoId
        _uiState.update { it.copy(overlay = next) }
    }

    private fun loadChannels() {
        if (!signedIn) return
        channelsJob?.cancel()
        val request = ++channelsGeneration
        _uiState.update { it.copy(channels = SubscriptionsChannels.Loading) }
        channelsJob = viewModelScope.launch {
            try {
                val channels = repository.subscribedChannels()
                if (request == channelsGeneration) _uiState.update { it.copy(channels = SubscriptionsChannels.Ready(channels)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == channelsGeneration) _uiState.update { it.copy(channels = SubscriptionsChannels.Failed) }
            }
        }
    }

    private fun refresh(filter: SubscriptionsFilter) {
        if (!signedIn) return
        loadJob?.cancel()
        pageJob?.cancel()
        val request = ++generation
        _uiState.update { it.copy(content = SubscriptionsContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.subscriptions(filter)
                if (request == generation) {
                    _uiState.update { it.copy(content = SubscriptionsContent.Feed(page)) }
                    // A filter can empty the first page while the server keeps going.
                    continuePastEmptyPage(autoLoads = 0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (request == generation) _uiState.update { it.copy(
                    content = SubscriptionsContent.Failed,
                    verification = com.hhst.youtubelite.core.trafficChallenge(error) ?: it.verification) }
            } catch (_: Exception) {
                if (request == generation) _uiState.update { it.copy(content = SubscriptionsContent.Failed) }
            }
        }
    }

    /**
     * The host reports the verification screen finished with RESULT_OK. The feed
     * retries once with the current identity — never automatically on another
     * failure, so no request loop can form.
     */
    fun verificationCompleted(challengeUrl: String? = _uiState.value.verification?.challengeUrl) {
        if (challengeUrl == null || _uiState.value.verification?.challengeUrl != challengeUrl) return
        _uiState.update { it.copy(verification = null) }
        notices.trySend(AppNotice.Message("Verification finished. Retrying…"))
        refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
    }

    private fun loadNext(autoLoads: Int = MAX_EMPTY_AUTO_LOADS, automatic: Boolean = false) {
        val current = _uiState.value.content as? SubscriptionsContent.Feed ?: return
        if (current.page.nextKey == null || current.append == AppendState.Loading) return
        if (automatic && autoLoads > MAX_EMPTY_AUTO_LOADS) return
        val request = generation
        val filter = SubscriptionsFilter.from(_uiState.value.selectedChip)
        _uiState.update { it.copy(content = current.copy(append = AppendState.Loading)) }
        pageJob = viewModelScope.launch {
            try {
                val page = repository.subscriptions(filter, current.page.nextKey)
                if (request != generation) return@launch
                var combinedEmpty = true
                _uiState.update { state ->
                    val existing = (state.content as? SubscriptionsContent.Feed)?.page ?: return@update state
                    val combined = (existing.items + page.items).distinctBy { it.id }
                    combinedEmpty = combined.isEmpty()
                    state.copy(content = SubscriptionsContent.Feed(existing.copy(
                        items = combined,
                        nextKey = page.nextKey.takeUnless { it == current.page.nextKey },
                    ), emptyAutoLoads = if (combinedEmpty) autoLoads else 0))
                }
                if (combinedEmpty) continuePastEmptyPage(autoLoads)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (request == generation) _uiState.update { state ->
                    val existing = state.content as? SubscriptionsContent.Feed ?: return@update state
                    state.copy(content = existing.copy(append = AppendState.Failed),
                        verification = com.hhst.youtubelite.core.trafficChallenge(error) ?: state.verification)
                }
            } catch (_: Exception) {
                if (request == generation) _uiState.update { state ->
                    val existing = state.content as? SubscriptionsContent.Feed ?: return@update state
                    state.copy(content = existing.copy(append = AppendState.Failed))
                }
            }
        }
    }

    /**
     * Keeps reading when a filter emptied the current page. Automatic continuation stops at
     * [MAX_EMPTY_AUTO_LOADS]; past that the screen offers an explicit Load more, and a manual
     * load (the default budget) always fetches exactly one page per tap.
     */
    private fun continuePastEmptyPage(autoLoads: Int) {
        val feed = _uiState.value.content as? SubscriptionsContent.Feed ?: return
        if (feed.page.items.isEmpty() && feed.page.nextKey != null) loadNext(autoLoads = autoLoads + 1, automatic = true)
    }

    /** The panel menu mirrors the home feed menu kinds. */
    val menuKind: FeedMenuKind = FeedMenuKind.Video

    companion object {
        private const val QUERY_KEY = "subscriptions_query"
        /** How many extra pages an empty filtered feed may fetch on its own before it asks. */
        private const val MAX_EMPTY_AUTO_LOADS = 3
    }
}
