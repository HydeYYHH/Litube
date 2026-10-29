package com.hhst.youtubelite.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.isTrafficChallenge

class HomeViewModel(
    private val repository: HomeRepository,
    private val savedState: SavedStateHandle,
    scenario: HomeScenario = HomeScenario.Empty,
    loadOnStart: Boolean = false,
    private val saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
    private val avatars: com.hhst.youtubelite.data.media.MediaAvatarDirectory? = null,
    private val snapshots: com.hhst.youtubelite.core.ContentSnapshots? = null,
    private val accountKey: () -> String = { "visitor" },
    /** Identity-scoped anonymous watch-report counter; null in fixtures. */
    private val reportSignal: (() -> Long)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** Snapshot seeding IO runs here; tests inject the runTest scheduler. */
    private val seedDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(
        content = scenario.initialContent(), query = savedState["query"] ?: "",
        selectedChip = savedState.get<String>("chip")?.takeIf { it in repository.filters } ?: "All",
        signedInPreview = savedState.get<Boolean>("signedInPreview") ?: (scenario != HomeScenario.Empty),
        overlay = restoreOverlay(), chips = repository.filters, live = saveRepository != null,
    ))
    val uiState = _state.asStateFlow()
    private val navigation = Channel<HomeNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var feedbackJob: Job? = null
    private var generation = 0
    private var liveResultReceived = false

    init {
        seedSnapshot()
        if (loadOnStart) load(append = false)
        if ((_state.value.overlay as? HomeOverlay.Save)?.live == true) loadSaveChoices()
    }

    /** Cold start paints the last captured rows and the live load replaces them. */
    private fun seedSnapshot() {
        val store = snapshots ?: return
        val seedGeneration = generation
        val account = accountKey()
        val chip = _state.value.selectedChip
        // Snapshot read/parse never blocks the main thread; it still paints well
        // ahead of the network load, and never over a live result.
        viewModelScope.launch {
            val rows = withContext(seedDispatcher) {
                store.read<FeedItem.Video>("home", account, chip,
                    com.google.gson.reflect.TypeToken.getParameterized(List::class.java, FeedItem.Video::class.java).type)
            } ?: return@launch
            if (rows.isEmpty()) return@launch
            _state.update { state ->
                if (liveResultReceived || accountKey() != account || state.selectedChip != chip ||
                    generation > seedGeneration + 1 ||
                    state.content !is HomeContent.Empty && state.content !is HomeContent.Loading) return@update state
                state.copy(content = HomeContent.Feed(HomePage(items = rows), refreshing = true))
            }
        }
    }

    fun setHideShorts(hidden: Boolean) { _state.update { it.copy(hideShorts = hidden) } }

    fun setSignedInPreview(signedIn: Boolean) {
        savedState["signedInPreview"] = signedIn
        _state.update { it.copy(signedInPreview = signedIn) }
    }

    private var sessionKey: Long? = null
    private var lastFeedLoadedAtMs = 0L
    private var lastSeenReportSignal = -1L
    /** A progress signal arrived for a request still in flight; consumed once it completes. */
    private var pendingReportRevision = false

    fun updateSession(signedIn: Boolean, key: Long, avatar: String? = null) {
        val changed = sessionKey != null && sessionKey != key
        sessionKey = key
        setSignedInPreview(signedIn)
        _state.update { it.copy(avatar = avatar, sessionGeneration = key) }
        if (changed) {
            feedbackJob?.cancel()
            overlay(HomeOverlay.None)
            lastSeenReportSignal = reportSignal?.invoke() ?: -1L
            load(append = false)
        }
    }

    /**
     * Foreground entry refreshes quietly at most once per report signal or staleness
     * window, on both a feed and an empty home (a real watch may have created the
     * first recommendations). An existing feed/empty card keeps its rows, query and
     * scroll; a busy load merges the signal as a pending revision instead of
     * discarding it — the finished request consumes it.
     */
    fun onForeground() {
        // Adopt the signal even while busy: the finished request re-checks it.
        val signal = reportSignal?.invoke() ?: -1L
        if (signal > lastSeenReportSignal) {
            if (lastSeenReportSignal >= 0) pendingReportRevision = true
            lastSeenReportSignal = signal
        }
        when (val content = _state.value.content) {
            is HomeContent.Feed -> {
                if (content.refreshing || loadJob?.isActive == true) return
                val stale = lastFeedLoadedAtMs > 0 && now() - lastFeedLoadedAtMs >= QUIET_REFRESH_AFTER_MS
                if (pendingReportRevision || stale) {
                    pendingReportRevision = false
                    load(append = false)
                }
            }
            // Staleness alone must not disturb an honest empty state; a real
            // watch signal may. Loading/Failed keep their own retry paths.
            is HomeContent.Empty ->
                if (pendingReportRevision && loadJob?.isActive != true) {
                    pendingReportRevision = false
                    load(append = false, keepEmpty = true)
                }
            else -> {}
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.OpenSearch -> overlay(HomeOverlay.Search)
            HomeAction.CloseOverlay -> overlay(HomeOverlay.None)
            is HomeAction.QueryChanged -> {
                savedState["query"] = action.query
                _state.update { it.copy(query = action.query) }
            }
            is HomeAction.SubmitSearch -> if (action.query.isNotBlank()) {
                onAction(HomeAction.QueryChanged(action.query.trim()))
                // The results page takes over; the overlay must not reappear when it returns.
                overlay(HomeOverlay.None)
                navigation.trySend(HomeNavigationRequest.Search(action.query.trim()))
            }
            is HomeAction.SelectChip -> {
                if (action.chip == uiState.value.selectedChip || action.chip !in uiState.value.chips) return
                savedState["chip"] = action.chip
                _state.update { it.copy(selectedChip = action.chip) }
                load(append = false)
            }
            is HomeAction.OpenMenu -> overlay(HomeOverlay.Menu(action.itemId, action.kind))
            is HomeAction.MenuSelected -> {
                val menu = uiState.value.overlay as? HomeOverlay.Menu ?: return
                if (action.action !in menu.kind.actions) return
                if (menu.kind == FeedMenuKind.Video && action.action == FeedMenuAction.SaveToPlaylist) {
                    if (saveRepository == null) overlay(HomeOverlay.Save(menu.itemId)) else {
                        overlay(HomeOverlay.Save(menu.itemId, live = true))
                        loadSaveChoices()
                    }
                    return
                }
                if (menu.kind == FeedMenuKind.Video && action.action == FeedMenuAction.WatchLater && saveRepository != null) {
                    overlay(HomeOverlay.None)
                    saveWatchLater(menu.itemId)
                    return
                }
                if (saveRepository != null && action.action in setOf(FeedMenuAction.NotInterested, FeedMenuAction.DontRecommend)) {
                    sendFeedback(menu.itemId, action.action)
                    return
                }
                val feedback = when (action.action) {
                    FeedMenuAction.NotInterested -> HomeFeedbackKind.NotInterested
                    FeedMenuAction.DontRecommend -> HomeFeedbackKind.DontRecommend
                    FeedMenuAction.SendFeedback -> HomeFeedbackKind.SendFeedback
                    FeedMenuAction.Report -> HomeFeedbackKind.Report
                    else -> null
                }
                if (feedback != null) {
                    overlay(HomeOverlay.Feedback(menu.itemId, feedback))
                    return
                }
                overlay(HomeOverlay.None)
                navigation.trySend(HomeNavigationRequest.MenuAction(menu.itemId, action.action))
            }
            is HomeAction.SaveDestinationSelected -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                overlay(HomeOverlay.None)
                when (val destination = action.destination) {
                    com.hhst.youtubelite.saves.domain.SaveDestination.WatchLater ->
                        navigation.trySend(HomeNavigationRequest.SaveToWatchLater(save.videoId))
                    is com.hhst.youtubelite.saves.domain.SaveDestination.Playlist ->
                        navigation.trySend(
                            HomeNavigationRequest.SaveToPlaylist(save.videoId, destination.id, destination.title),
                        )
                }
            }
            HomeAction.OpenNewPlaylist -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                overlay(save.copy(newPlaylist = true, title = "", description = ""))
            }
            is HomeAction.SaveDraftChanged -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                overlay(save.copy(title = action.title, description = action.description))
            }
            HomeAction.SubmitNewPlaylist -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                val title = save.title.trim()
                if (title.isEmpty() || save.pending) return
                if (!save.live) {
                    overlay(HomeOverlay.None)
                    navigation.trySend(HomeNavigationRequest.CreatePlaylist(save.videoId, title, save.description.trim()))
                    return
                }
                val repository = saveRepository ?: return
                editSave(save.copy(pending = true))
                saveJob = viewModelScope.launch {
                    try {
                        val created = repository.create(title, save.description.trim(), save.videoId)
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, newPlaylist = false, title = "", description = "",
                                choices = (current.choices ?: emptyList()) + created)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) { overlay(HomeOverlay.None); notices.trySend(AppNotice.SignIn) }
                    catch (_: UnconfirmedActionException) { saveFailed(save.videoId, "The server did not confirm this change. Please refresh before trying again.") }
                    catch (_: Exception) { saveFailed(save.videoId, "Couldn't create this playlist. Try again.") }
                }
            }
            is HomeAction.SaveChoiceToggled -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                val repository = saveRepository ?: return
                if (!save.live || save.pending) return
                val target = !action.choice.contains
                editSave(save.copy(pending = true))
                saveJob = viewModelScope.launch {
                    try {
                        repository.setContaining(action.choice.id, save.videoId, target)
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, choices = current.choices?.map {
                                if (it.id == action.choice.id) it.copy(contains = target) else it
                            })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) { overlay(HomeOverlay.None); notices.trySend(AppNotice.SignIn) }
                    catch (_: UnconfirmedActionException) { saveFailed(save.videoId, "The server did not confirm this change. Please refresh before trying again.") }
                    catch (_: Exception) { saveFailed(save.videoId, "Couldn't update this playlist. Try again.") }
                }
            }
            HomeAction.SaveChoicesRetry -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                if (save.live) {
                    editSave(save.copy(choicesFailed = false, choices = null))
                    loadSaveChoices()
                }
            }
            is HomeAction.FeedbackReason -> {
                val feedback = uiState.value.overlay as? HomeOverlay.Feedback ?: return
                overlay(HomeOverlay.None)
                navigation.trySend(HomeNavigationRequest.Feedback(feedback.itemId, feedback.kind, action.reason))
            }
            is HomeAction.Navigate -> navigation.trySend(action.request)
            HomeAction.Refresh -> load(append = false)
            HomeAction.LoadNext -> load(append = true)
            HomeAction.DismissVerification -> _state.update { it.copy(verification = null) }
        }
    }

    /**
     * The host reports the verification screen finished with RESULT_OK. The read
     * retries once with the current identity — never automatically on another
     * failure, so no request loop can form.
     */
    fun verificationCompleted(challengeUrl: String? = uiState.value.verification?.challengeUrl) {
        if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
        _state.update { it.copy(verification = null) }
        notices.trySend(AppNotice.Message("Verification finished. Retrying…"))
        load(append = false)
    }

    private fun restoreOverlay(): HomeOverlay = when (savedState.get<String>("homeOverlay")) {
        "search" -> HomeOverlay.Search
        "menu" -> savedState.get<String>("overlayItem")?.let { HomeOverlay.Menu(it,
            FeedMenuKind.entries.firstOrNull { kind -> kind.name == savedState.get<String>("overlayKind") } ?: FeedMenuKind.Video) } ?: HomeOverlay.None
        "save" -> savedState.get<String>("overlayItem")?.let { HomeOverlay.Save(it,
            savedState.get<Boolean>("newPlaylist") == true, savedState.get<String>("saveTitle").orEmpty(),
            savedState.get<String>("saveDescription").orEmpty(), live = saveRepository != null) } ?: HomeOverlay.None
        "feedback" -> {
            val id = savedState.get<String>("overlayItem")
            val kind = HomeFeedbackKind.entries.firstOrNull { it.name == savedState.get<String>("overlayKind") }
            if (id != null && kind != null) HomeOverlay.Feedback(id, kind) else HomeOverlay.None
        }
        else -> if (savedState.get<Boolean>("searchOpen") == true) HomeOverlay.Search else HomeOverlay.None
    }

    private fun overlay(overlay: HomeOverlay) {
        savedState["searchOpen"] = overlay == HomeOverlay.Search
        savedState["homeOverlay"] = when (overlay) {
            HomeOverlay.None -> "none"
            HomeOverlay.Search -> "search"
            is HomeOverlay.Menu -> {
                savedState["overlayItem"] = overlay.itemId
                savedState["overlayKind"] = overlay.kind.name
                "menu"
            }
            is HomeOverlay.Save -> {
                savedState["overlayItem"] = overlay.videoId
                savedState["newPlaylist"] = overlay.newPlaylist
                savedState["saveTitle"] = overlay.title
                savedState["saveDescription"] = overlay.description
                "save"
            }
            is HomeOverlay.Feedback -> {
                savedState["overlayItem"] = overlay.itemId
                savedState["overlayKind"] = overlay.kind.name
                "feedback"
            }
        }
        _state.update { it.copy(overlay = overlay) }
    }

    private fun loadSaveChoices() {
        val save = uiState.value.overlay as? HomeOverlay.Save ?: return
        val repository = saveRepository ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try {
                val choices = repository.choices(save.videoId)
                editSaveOverlay(save.videoId) { it.copy(choices = choices, choicesFailed = false, pending = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { overlay(HomeOverlay.None); notices.trySend(AppNotice.SignIn) }
            catch (_: Exception) { editSaveOverlay(save.videoId) { it.copy(choices = null, choicesFailed = true, pending = false) } }
        }
    }

    private fun saveWatchLater(videoId: String) {
        val repository = saveRepository ?: return
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            try {
                repository.saveWatchLater(videoId)
                notices.trySend(AppNotice.Message("Saved to Watch later"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { notices.trySend(AppNotice.SignIn) }
            catch (_: UnconfirmedActionException) { notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again.")) }
            catch (_: Exception) { notices.trySend(AppNotice.Message("Couldn't save this video. Try again.")) }
        }
    }

    private fun sendFeedback(itemId: String, action: FeedMenuAction) {
        if (feedbackJob?.isActive == true) return
        val state = _state.value
        val video = (state.content as? HomeContent.Feed)?.page?.items
            ?.filterIsInstance<FeedItem.Video>()?.firstOrNull { it.id == itemId }
            ?.takeIf { it.feedbackGeneration == state.sessionGeneration } ?: return
        if (action !in FeedMenuKind.Video.productionActions(state.signedInPreview, video)) return
        overlay(HomeOverlay.None)
        val requestGeneration = generation
        feedbackJob = viewModelScope.launch {
            try {
                repository.feedback(video, action)
                if (generation != requestGeneration || _state.value.sessionGeneration != video.feedbackGeneration) return@launch
                _state.update { current ->
                    val feed = current.content as? HomeContent.Feed ?: return@update current
                    current.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.filterNot {
                        it.id == itemId || action == FeedMenuAction.DontRecommend && it is FeedItem.Video &&
                            video.channelId.isNotBlank() && it.channelId == video.channelId
                    })))
                }
                notices.trySend(AppNotice.Message("Removed from recommendations"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { notices.trySend(AppNotice.SignIn) }
            catch (_: Exception) { notices.trySend(AppNotice.Message("Couldn't update recommendations. Try again.")) }
        }
    }

    private fun saveFailed(videoId: String, message: String) {
        editSaveOverlay(videoId) { it.copy(pending = false) }
        notices.trySend(AppNotice.Message(message))
    }

    private fun editSaveOverlay(videoId: String, edit: (HomeOverlay.Save) -> HomeOverlay.Save) {
        _state.update { state ->
            val save = state.overlay as? HomeOverlay.Save
            if (save == null || save.videoId != videoId) state else state.copy(overlay = edit(save))
        }
    }

    private fun editSave(save: HomeOverlay.Save) {
        _state.update { if (it.overlay is HomeOverlay.Save) it.copy(overlay = save) else it }
        savedState["newPlaylist"] = save.newPlaylist
        savedState["saveTitle"] = save.title
        savedState["saveDescription"] = save.description
    }

    /** A shorts rail split across the page boundary reads as one shelf; ids stay unique per rail. */
    private fun mergeFeed(loaded: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
        val merged = ArrayList(loaded)
        val tail = merged.lastOrNull() as? FeedItem.Shorts
        val head = incoming.firstOrNull() as? FeedItem.Shorts
        if (tail != null && head != null) {
            merged[merged.size - 1] = FeedItem.Shorts(tail.id, tail.videos + head.videos)
            merged.addAll(incoming.drop(1))
        } else {
            merged.addAll(incoming)
        }
        return merged.distinctBy { it.id }
    }

    /**
     * One shared directory read per channel, capped and cached there; a stale application
     * is identity-safe because it only rewrites rows still missing their avatar.
     */
    private var avatarJob: Job? = null
    private fun fillMissingAvatars(items: List<FeedItem>) {
        val directory = avatars ?: return
        val missing = items.filterIsInstance<FeedItem.Video>()
            .filter { it.avatar == null && it.channelId.isNotBlank() }
            .distinctBy { it.channelId }.takeIf { it.isNotEmpty() } ?: return
        avatarJob?.cancel()
        avatarJob = viewModelScope.launch {
            directory.resolveAll(missing.map { it.channelId }) { channelId, avatar ->
                _state.update { state ->
                    val feed = state.content as? HomeContent.Feed ?: return@update state
                    state.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.map { item ->
                        if (item is FeedItem.Video && item.avatar == null && item.channelId == channelId)
                            item.copy(avatar = avatar) else item
                    })))
                }
            }
        }
    }

    private fun load(append: Boolean, keepEmpty: Boolean = false) {
        val feed = uiState.value.content as? HomeContent.Feed
        val empty = uiState.value.content as? HomeContent.Empty
        val next = if (append) feed?.page?.nextKey ?: return else null
        if (append && (feed?.append == AppendState.Loading || feed?.refreshing == true)) return
        loadJob?.cancel()
        val requestGeneration = ++generation
        val requestAccount = accountKey()
        val filter = uiState.value.selectedChip
        _state.update { state -> state.copy(verification = null, content = when {
            append && feed != null -> feed.copy(append = AppendState.Loading)
            feed != null -> feed.copy(refreshing = true, append = AppendState.Idle, refreshFailed = false)
            // A quiet re-check keeps the empty card painted (no Loading flash).
            keepEmpty && empty != null -> empty.copy(refreshing = true)
            else -> HomeContent.Loading
        }) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.loadHome(filter, next)
                if (requestGeneration != generation) return@launch
                liveResultReceived = true
                if (!append) lastFeedLoadedAtMs = now()
                val combined = if (append && feed != null) page.copy(items = mergeFeed(feed.page.items, page.items)) else page
                _state.update { it.copy(content = if (combined.items.isEmpty()) HomeContent.Empty(
                    title = combined.messages.firstOrNull() ?: HomeContent.Empty().title,
                ) else HomeContent.Feed(combined)) }
                val store = snapshots
                if (store != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    store.write("home", requestAccount, filter, combined.items.filterIsInstance<FeedItem.Video>())
                }
                if (requestGeneration != generation) return@launch
                fillMissingAvatars(combined.items)
                consumePendingRevisionAfterLoad()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
                liveResultReceived = true
                if (error.failure.isTrafficChallenge()) {
                    // Keep a working feed browsable and surface the challenge as a banner;
                    // a first-load challenge becomes the state the card renders over.
                    _state.update { state -> state.copy(
                        content = if (feed != null) feed.copy(refreshing = false,
                            append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = false)
                        else HomeContent.Failed,
                        verification = com.hhst.youtubelite.core.VerificationRequired(error.failure.detail!!),
                    ) }
                } else {
                    _state.update { it.copy(content = if (feed != null) feed.copy(refreshing = false,
                        append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = !append) else HomeContent.Failed) }
                    consumePendingRevisionAfterLoad()
                }
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                liveResultReceived = true
                _state.update { it.copy(content = if (feed != null) feed.copy(refreshing = false,
                    append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = !append) else HomeContent.Failed) }
                consumePendingRevisionAfterLoad()
            }
        }
    }

    /**
     * A progress signal that arrived while a request was in flight merges as a
     * pending revision and is consumed here — one quiet re-check, never discarded.
     * A traffic challenge is excluded: its banner owns the retry.
     */
    private fun consumePendingRevisionAfterLoad() {
        if (!pendingReportRevision) return
        pendingReportRevision = false
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { load(append = false, keepEmpty = true) }
    }

    override fun onCleared() { navigation.close() }

    private companion object {
        /** A feed older than this refreshes quietly when the screen returns to front. */
        const val QUIET_REFRESH_AFTER_MS = 15 * 60 * 1000L
    }
}
