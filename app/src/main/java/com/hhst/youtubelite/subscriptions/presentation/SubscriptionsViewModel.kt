package com.hhst.youtubelite.subscriptions.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.home.domain.withMissingDisplayFrom
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.data.SubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.data.subscriptionsSignedOutPrompt
import com.hhst.youtubelite.subscriptions.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.ContinuationHistory

class SubscriptionsViewModel(
    private val repository: SubscriptionsRepository,
    private val savedState: SavedStateHandle,
    private val scenario: SubscriptionsScenario,
    private var signedIn: Boolean,
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    private val readChannels = savedState.get<ArrayList<String>>("readChannels").orEmpty().toMutableSet()
    private val _uiState = MutableStateFlow(
        SubscriptionsUiState(signedIn = signedIn, query = savedState[QUERY_KEY] ?: "",
            selectedChannel = savedState.get<String>("selectedChannelId")?.let { id ->
                SubscribedChannel(id, savedState["selectedChannelName"] ?: "", savedState["selectedChannelAvatar"] ?: "", "",
                    subscriptionParams = savedState["selectedChannelParams"])
            },
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
    /** Live save flow for the item menu; null in fixture builds where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var channelsJob: Job? = null
    private var menuJob: Job? = null
    private var menuRevision = 0L
    private var sessionEpoch = 0L
    val writes = com.hhst.youtubelite.core.WriteFeedback()
    private var feedbackJob: Job? = null
    private var generation = 0
    /** Channel reads follow the account, not the chip: a feed refresh never invalidates them. */
    private var channelsGeneration = 0
    private var channelsReadAt: Long? = null
    private val feedContinuations = ContinuationHistory()
    private val channelContinuations = ContinuationHistory()

    fun onForeground() {
        if (scenario != SubscriptionsScenario.Content || channelsJob?.isActive == true) return
        if (channelsReadAt?.let { clock() - it in 0 until 60_000 } == true) return
        loadChannels(quiet = true)
    }

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

    private var sessionKey: Long? = savedState["subscriptionsSession"]

    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        savedState["subscriptionsSession"] = key
        if (signedIn == value && !changed) return
        signedIn = value
        sessionEpoch++
        writes.reset()
        ++generation
        ++channelsGeneration
        loadJob?.cancel()
        pageJob?.cancel()
        channelsJob?.cancel()
        // Deferred menu reads and feedback writes from the previous session must not survive.
        menuJob?.cancel()
        feedbackJob?.cancel(); feedbackJob = null
        saveSheet?.resetSession()
        showOverlay(SubscriptionsOverlay.None)
        readChannels.clear()
        savedState["readChannels"] = arrayListOf<String>()
        saveSelectedChannel(null)
        _uiState.update { it.copy(signedIn = value, selectedChannel = null, channels = SubscriptionsChannels.Loading, verification = null,
            content = SubscriptionsContent.SignedOut(subscriptionsSignedOutPrompt())) }
        if (value) {
            refresh(SubscriptionsFilter.from(uiState.value.selectedChip))
            loadChannels()
        }
    }

    fun onAction(action: SubscriptionsAction) {
        when (action) {
            SubscriptionsAction.Retry -> {
                refresh(SubscriptionsFilter.from(_uiState.value.selectedChip))
                loadChannels(quiet = true)
            }
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
                if (action.chip == _uiState.value.selectedChip ||
                    SubscriptionsFilter.from(action.chip) !in repository.supportedFilters) return
                savedState["chip"] = action.chip.name
                _uiState.update { it.copy(selectedChip = action.chip) }
                refresh(SubscriptionsFilter.from(action.chip))
            }
            SubscriptionsAction.ShowChannels -> {
                showOverlay(SubscriptionsOverlay.Channels)
                onForeground()
            }
            SubscriptionsAction.CloseChannels -> showOverlay(SubscriptionsOverlay.None)
            SubscriptionsAction.RetryChannels -> loadChannels()
            SubscriptionsAction.LoadMoreChannels -> loadMoreChannels()
            is SubscriptionsAction.OpenVideo -> navigation.trySend(SubscriptionsNavigationRequest.Watch(action.videoId))
            is SubscriptionsAction.OpenChannel -> navigation.trySend(SubscriptionsNavigationRequest.Channel(action.channelId))
            is SubscriptionsAction.SelectChannel -> selectChannel(action.channelId)
            is SubscriptionsAction.OpenMenu -> {
                showOverlay(SubscriptionsOverlay.Menu(action.videoId))
                loadMenu(action.videoId)
            }
            is SubscriptionsAction.MenuSelected -> {
                val videoId = (_uiState.value.overlay as? SubscriptionsOverlay.Menu)?.videoId
                showOverlay(SubscriptionsOverlay.None)
                if (videoId != null) when {
                    action.action in setOf(FeedMenuAction.Share, FeedMenuAction.Queue, FeedMenuAction.Download) ->
                        navigation.trySend(SubscriptionsNavigationRequest.MenuAction(videoId, action.action))
                    action.action in setOf(FeedMenuAction.NotInterested, FeedMenuAction.DontRecommend) && saveSheet != null ->
                        sendFeedback(videoId, action.action)
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

    private fun saveSelectedChannel(channel: SubscribedChannel?) {
        savedState["selectedChannelId"] = channel?.channelId
        savedState["selectedChannelName"] = channel?.name
        savedState["selectedChannelAvatar"] = channel?.avatar
        savedState["selectedChannelParams"] = channel?.subscriptionParams
    }

    private fun selectChannel(channelId: String?) {
        if (!signedIn || channelId == _uiState.value.selectedChannel?.channelId) return
        val channels = _uiState.value.channels as? SubscriptionsChannels.Ready
        val channel = if (channelId == null) null else channels?.channels?.firstOrNull { it.channelId == channelId } ?: return
        if (channel != null) readChannels.add(channel.channelId)
        savedState["readChannels"] = ArrayList(readChannels)
        saveSelectedChannel(channel)
        savedState["chip"] = SubscriptionsChip.All.name
        showOverlay(SubscriptionsOverlay.None)
        _uiState.update { state ->
            state.copy(selectedChannel = channel?.copy(hasNewContent = false), selectedChip = SubscriptionsChip.All,
                verification = null,
                channels = channels?.copy(channels = channels.channels.map {
                    if (it.channelId == channelId) it.copy(hasNewContent = false) else it
                }) ?: state.channels)
        }
        refresh(SubscriptionsFilter.All)
    }

    private fun showOverlay(next: SubscriptionsOverlay) {
        menuRevision++
        menuJob?.cancel()
        savedState["overlay"] = when (next) {
            SubscriptionsOverlay.None -> "none"
            SubscriptionsOverlay.Search -> "search"
            SubscriptionsOverlay.Channels -> "channels"
            is SubscriptionsOverlay.Menu -> "menu"
        }
        savedState["menuItem"] = (next as? SubscriptionsOverlay.Menu)?.videoId
        // Deferred-menu header state only exists while a menu is open.
        _uiState.update {
            it.copy(overlay = next,
                menuLoading = it.menuLoading && next is SubscriptionsOverlay.Menu,
                menuFailed = it.menuFailed && next is SubscriptionsOverlay.Menu)
        }
    }

    private fun loadChannels(quiet: Boolean = false) {
        if (!signedIn) return
        channelsJob?.cancel()
        val request = ++channelsGeneration
        val previous = _uiState.value.channels.takeIf { quiet && it is SubscriptionsChannels.Ready }
        if (previous == null) _uiState.update { it.copy(channels = SubscriptionsChannels.Loading) }
        channelsJob = viewModelScope.launch {
            try {
                val page = repository.subscribedChannelsPage()
                if (request == channelsGeneration) {
                    channelContinuations.clear()
                    channelsReadAt = clock()
                    _uiState.update { it.copy(channels = readyChannels(emptyList(), page)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == channelsGeneration) {
                    channelsReadAt = clock()
                    _uiState.update { it.copy(channels = previous ?: SubscriptionsChannels.Failed) }
                }
            }
        }
    }

    /** Repeated channel id updates unread in place instead of duplicating; updated-first stays stable per group. */
    private fun loadMoreChannels() {
        // Quiet refresh still paints the old directory, but its cursor belongs to that old read.
        if (channelsJob?.isActive == true) return
        val ready = _uiState.value.channels as? SubscriptionsChannels.Ready ?: return
        val cursor = ready.nextKey ?: return
        if (ready.append == AppendState.Loading) return
        val request = channelsGeneration
        _uiState.update { it.copy(channels = ready.copy(append = AppendState.Loading)) }
        channelsJob = viewModelScope.launch {
            try {
                val page = repository.subscribedChannelsPage(cursor)
                if (request != channelsGeneration) return@launch
                val nextKey = channelContinuations.nextAfter(cursor, page.nextKey)
                _uiState.update {
                    val current = it.channels as? SubscriptionsChannels.Ready ?: return@update it
                    it.copy(channels = readyChannels(current.channels, page.copy(nextKey = nextKey)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == channelsGeneration) _uiState.update {
                    val current = it.channels as? SubscriptionsChannels.Ready ?: return@update it
                    it.copy(channels = current.copy(append = AppendState.Failed))
                }
            }
        }
    }

    /** Unread flags OR-merge per channel id; updated channels lead. */
    private fun readyChannels(loaded: List<SubscribedChannel>, page: ChannelDirectoryPage): SubscriptionsChannels.Ready {
        val byId = LinkedHashMap<String, SubscribedChannel>(loaded.size + page.channels.size)
        loaded.forEach { byId[it.channelId] = it }
        page.channels.forEach { next ->
            val previous = byId[next.channelId]
            byId[next.channelId] = if (previous == null) next
            else previous.copy(hasNewContent = previous.hasNewContent || next.hasNewContent)
        }
        // Keep the consumed marker hidden while an in-flight/stale directory still reports it;
        // once the server clears it, a subsequent new update can badge again.
        page.channels.groupBy { it.channelId }.forEach { (id, entries) ->
            if (entries.none { it.hasNewContent }) readChannels.remove(id)
        }
        savedState["readChannels"] = ArrayList(readChannels)
        val merged = byId.values.map { if (it.channelId in readChannels) it.copy(hasNewContent = false) else it }
            .sortedByDescending { it.hasNewContent }
        return SubscriptionsChannels.Ready(merged, page.nextKey, AppendState.Idle)
    }

    private fun refresh(filter: SubscriptionsFilter) {
        if (!signedIn) return
        loadJob?.cancel()
        pageJob?.cancel()
        val request = ++generation
        val channel = _uiState.value.selectedChannel
        _uiState.update { it.copy(content = SubscriptionsContent.Loading, menuLoading = false, menuFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val page = if (channel == null) repository.subscriptions(filter)
                    else repository.channelSubscriptions(channel, filter)
                if (request == generation) {
                    feedContinuations.clear()
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
     * Retries once with the current identity after RESULT_OK — never automatically on another
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
        val channel = _uiState.value.selectedChannel
        _uiState.update { it.copy(content = current.copy(append = AppendState.Loading)) }
        pageJob = viewModelScope.launch {
            try {
                val page = if (channel == null) repository.subscriptions(filter, current.page.nextKey)
                    else repository.channelSubscriptions(channel, filter, current.page.nextKey)
                if (request != generation) return@launch
                val nextKey = feedContinuations.nextAfter(current.page.nextKey, page.nextKey)
                var combinedEmpty = true
                _uiState.update { state ->
                    val existing = (state.content as? SubscriptionsContent.Feed)?.page ?: return@update state
                    val unique = linkedMapOf<String, com.hhst.youtubelite.home.domain.FeedItem.Video>()
                    (existing.items + page.items).forEach { row ->
                        unique[row.id] = unique[row.id]?.withMissingDisplayFrom(row) ?: row
                    }
                    val combined = unique.values.toList()
                    combinedEmpty = combined.isEmpty()
                    state.copy(content = SubscriptionsContent.Feed(existing.copy(
                        items = combined,
                        nextKey = nextKey,
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
     * Keeps reading when a filter emptied the page. Automatic stops at [MAX_EMPTY_AUTO_LOADS];
     * past that the screen offers Load more; a manual load always fetches one page per tap.
     */
    private fun continuePastEmptyPage(autoLoads: Int) {
        val feed = _uiState.value.content as? SubscriptionsContent.Feed ?: return
        if (feed.page.items.isEmpty() && feed.page.nextKey != null) loadNext(autoLoads = autoLoads + 1, automatic = true)
    }

    private fun menuItem(videoId: String): com.hhst.youtubelite.home.domain.FeedItem.Video? =
        (_uiState.value.content as? SubscriptionsContent.Feed)?.page?.items?.firstOrNull { it.id == videoId }

    /**
     * Reads the open card's get_panel so feedback rows carry real tokens; cards with inline
     * tokens return unchanged; nothing is preloaded for unopened menus.
     */
    private fun loadMenu(videoId: String) {
        val video = menuItem(videoId) ?: return
        if (!_uiState.value.live || !signedIn) return
        if (sessionKey != null && video.feedbackGeneration != sessionKey) return
        menuJob?.cancel()
        val revision = ++menuRevision
        val requestGeneration = generation
        val requestSession = sessionEpoch
        fun ownsMenu() = revision == menuRevision && requestGeneration == generation && requestSession == sessionEpoch
        if (video.menuEndpoint == null) {
            _uiState.update { it.copy(menuLoading = false, menuFailed = false) }
            return
        }
        _uiState.update { it.copy(menuLoading = true, menuFailed = false) }
        menuJob = viewModelScope.launch {
            try {
                val resolved = repository.menu(video)
                currentCoroutineContext().ensureActive()
                if (!ownsMenu()) return@launch
                _uiState.update { current ->
                    if ((current.overlay as? SubscriptionsOverlay.Menu)?.videoId != videoId) return@update current
                    if (sessionKey != null && resolved.feedbackGeneration != sessionKey) return@update current
                    val feed = current.content as? SubscriptionsContent.Feed ?: return@update current.copy(menuLoading = false)
                    current.copy(menuLoading = false, content = feed.copy(page = feed.page.copy(
                        items = feed.page.items.map { item ->
                            if (item.id == videoId) item.copy(notInterestedToken = resolved.notInterestedToken,
                                dontRecommendToken = resolved.dontRecommendToken,
                                feedbackGeneration = resolved.feedbackGeneration) else item
                        })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _uiState.update { current ->
                    if (!ownsMenu() || (current.overlay as? SubscriptionsOverlay.Menu)?.videoId != videoId) current
                    else current.copy(menuLoading = false, menuFailed = true)
                }
            }
        }
    }

    private fun sendFeedback(videoId: String, action: FeedMenuAction) {
        if (feedbackJob?.isActive == true) return
        val video = menuItem(videoId)?.takeIf { sessionKey != null && it.feedbackGeneration == sessionKey } ?: return
        val token = when (action) {
            FeedMenuAction.NotInterested -> video.notInterestedToken
            FeedMenuAction.DontRecommend -> video.dontRecommendToken
            else -> null
        }
        if (token.isNullOrBlank()) return
        val request = generation
        val requestSession = sessionEpoch
        fun ownsFeedback() = request == generation && requestSession == sessionEpoch
        showOverlay(SubscriptionsOverlay.None)
        feedbackJob = writes.launch(viewModelScope) {
            try {
                repository.feedback(video, action)
                currentCoroutineContext().ensureActive()
                if (!ownsFeedback()) return@launch
                _uiState.update { state ->
                    val feed = state.content as? SubscriptionsContent.Feed ?: return@update state
                    state.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.filterNot {
                        it.id == videoId || action == FeedMenuAction.DontRecommend &&
                            video.channelId.isNotBlank() && it.channelId == video.channelId
                    })))
                }
                notices.trySend(AppNotice.Message("Removed from recommendations"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) { if (ownsFeedback()) notices.trySend(AppNotice.SignIn) }
            catch (error: Exception) { if (ownsFeedback()) notices.trySend(com.hhst.youtubelite.diagnostics.writeFailureNotice(
                "subscriptions_feedback_${action.name.lowercase()}", error,
                mapOf("video_id" to videoId, "channel_id" to video.channelId),
                message = "Couldn't update recommendations. Try again.")) }
        }
    }

    val menuKind: FeedMenuKind = FeedMenuKind.Video

    companion object {
        private const val QUERY_KEY = "subscriptions_query"
        /** Extra pages an empty filtered feed may auto-fetch before it asks. */
        private const val MAX_EMPTY_AUTO_LOADS = 3
    }
}
