package com.hhst.youtubelite.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.WriteFeedback
import com.hhst.youtubelite.core.isTrafficChallenge
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.home.domain.FeedMenuKind
import com.hhst.youtubelite.home.domain.HomeAction
import com.hhst.youtubelite.home.domain.HomeCatalog
import com.hhst.youtubelite.home.domain.HomeContent
import com.hhst.youtubelite.home.domain.HomeFeedbackKind
import com.hhst.youtubelite.home.domain.HomeNavigationRequest
import com.hhst.youtubelite.home.domain.HomeOverlay
import com.hhst.youtubelite.home.domain.HomePage
import com.hhst.youtubelite.home.domain.HomeRepository
import com.hhst.youtubelite.home.domain.HomeUiState
import com.hhst.youtubelite.home.domain.productionActions
import com.hhst.youtubelite.home.domain.withMissingDisplayFrom
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Speculative warm-up for the first card of the first page; keeps setup off the tap path. */
    private val warm: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Snapshot seeding IO runs here; tests inject the runTest scheduler. */
    private val seedDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(
        content = scenario.initialContent(), query = savedState["query"] ?: "",
        selectedChip = savedState.get<String>("chip")?.takeIf {
            it in if (savedState.get<Boolean>("catalog") == true) HomeCatalog.filters else repository.filters
        } ?: "All",
        signedInPreview = savedState.get<Boolean>("signedInPreview") ?: (scenario != HomeScenario.Empty),
        overlay = restoreOverlay(), chips = if (savedState.get<Boolean>("catalog") == true) HomeCatalog.filters else repository.filters, live = saveRepository != null,
    ))
    val uiState = _state.asStateFlow()
    private val navigation = Channel<HomeNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var loadJob: Job? = null
    private var sheetJob: Job? = null
    private var backgroundWriteJob: Job? = null
    // Feedback survives reads, but belongs to one account and one visit to a filter.
    private var sessionEpoch = 0L
    private var scopeEpoch = 0L
    private var sheetEpoch = 0L
    private data class SaveOwner(val session: Long, val sheet: Long)
    private fun ownsSheet(owner: SaveOwner) = owner.session == sessionEpoch && owner.sheet == sheetEpoch
    val writes = WriteFeedback()
    private var feedbackJob: Job? = null
    private var menuJob: Job? = null
    private var menuRevision = 0L
    private var generation = 0
    private val consumedContinuations = mutableSetOf<String>()
    private var liveResultReceived = false
    private var avatarJob: Job? = null

    init {
        seedSnapshot()
        if (loadOnStart) load(append = false)
        if ((_state.value.overlay as? HomeOverlay.Save)?.live == true) loadSaveChoices()
    }

    /** Cold start paints the last captured rows and the live load replaces them. */
    private fun seedSnapshot() {
        val store = snapshots ?: return
        if (savedState.get<Boolean>("catalog") == true) return
        val seedGeneration = generation
        val account = accountKey()
        val chip = _state.value.selectedChip
        // Snapshot seeding paints ahead of the network load and never over a live result.
        viewModelScope.launch {
            val rows = withContext(seedDispatcher) {
                store.read("home", account, chip, FeedItem.Video::class.java)
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
            sessionEpoch++
            writes.reset()
            scopeEpoch++
            loadJob?.cancel()
            loadJob = null
            feedbackJob?.cancel()
            feedbackJob = null
            sheetJob?.cancel()
            sheetJob = null
            backgroundWriteJob?.cancel()
            backgroundWriteJob = null
            savedState["catalog"] = false
            _state.update { it.copy(chips = repository.filters,
                selectedChip = it.selectedChip.takeIf(repository.filters::contains) ?: "All") }
            savedState["chip"] = _state.value.selectedChip
            overlay(HomeOverlay.None)
            lastSeenReportSignal = reportSignal?.invoke() ?: -1L
            load(append = false, userInitiated = true)
        }
    }

    /** A request already owns the slot; a user refresh must not restart it. */
    private fun refreshInFlight() = loadJob?.isActive == true

    /**
     * Quiet refresh at most once per report signal or staleness window, on feed and
     * empty alike; a busy load merges the signal as a pending revision instead of
     * discarding it — the finished request consumes it.
     */
    fun onForeground() {
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
            // Staleness must not disturb an honest empty state; only a real watch signal refreshes it.
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
            HomeAction.CloseOverlay -> {
                if ((uiState.value.overlay as? HomeOverlay.Save)?.pending != true) overlay(HomeOverlay.None)
            }
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
                scopeEpoch++
                feedbackJob?.cancel()
                feedbackJob = null
                savedState["chip"] = action.chip
                _state.update { it.copy(selectedChip = action.chip) }
                load(append = false, userInitiated = true)
            }
            is HomeAction.OpenMenu -> {
                overlay(HomeOverlay.Menu(action.itemId, action.kind))
                loadMenu()
            }
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
                if (save.pending) return
                overlay(save.copy(newPlaylist = true, title = "", description = ""))
            }
            is HomeAction.SaveDraftChanged -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                if (save.pending) return
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
                sheetEpoch++
                sheetJob?.cancel()
                val owner = SaveOwner(sessionEpoch, sheetEpoch)
                sheetJob = viewModelScope.launch {
                    try {
                        val created = repository.create(title, save.description.trim(), save.videoId)
                        if (!ownsSheet(owner)) return@launch
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, newPlaylist = false, title = "", description = "",
                                choices = (current.choices ?: emptyList()) + created)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) {
                        if (!ownsSheet(owner)) return@launch
                        overlay(HomeOverlay.None)
                        notices.trySend(AppNotice.SignIn)
                    }
                    catch (_: UnconfirmedActionException) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, "The server did not confirm this change. Please refresh before trying again.")
                        }
                    }
                    catch (_: Exception) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, "Couldn't create this playlist. Try again.")
                        }
                    }
                }
            }
            is HomeAction.SaveChoiceToggled -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                val repository = saveRepository ?: return
                if (!save.live || save.pending) return
                val target = !action.choice.contains
                editSave(save.copy(pending = true))
                sheetEpoch++
                sheetJob?.cancel()
                val owner = SaveOwner(sessionEpoch, sheetEpoch)
                sheetJob = viewModelScope.launch {
                    try {
                        repository.setContaining(action.choice.id, save.videoId, target)
                        if (!ownsSheet(owner)) return@launch
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, choices = current.choices?.map {
                                if (it.id == action.choice.id) it.copy(contains = target) else it
                            })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) {
                        if (!ownsSheet(owner)) return@launch
                        overlay(HomeOverlay.None)
                        notices.trySend(AppNotice.SignIn)
                    }
                    catch (_: UnconfirmedActionException) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, "The server did not confirm this change. Please refresh before trying again.")
                        }
                    }
                    catch (_: Exception) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, "Couldn't update this playlist. Try again.")
                        }
                    }
                }
            }
            HomeAction.SaveChoicesRetry -> {
                val save = uiState.value.overlay as? HomeOverlay.Save ?: return
                if (save.live && !save.pending) {
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
            HomeAction.Refresh -> if (!refreshInFlight()) load(append = false, keepEmpty = true, userInitiated = true)
            HomeAction.LoadNext -> load(append = true)
            HomeAction.AutomaticLoadNext -> load(append = true, automatic = true)
            HomeAction.DismissVerification -> _state.update { it.copy(verification = null) }
        }
    }

    /**
     * Verification finished (RESULT_OK): retry once with the current identity, never
     * automatically on another failure, so no request loop can form.
     */
    fun verificationCompleted(challengeUrl: String? = uiState.value.verification?.challengeUrl) {
        if (challengeUrl == null || uiState.value.verification?.challengeUrl != challengeUrl) return
        _state.update { it.copy(verification = null) }
        notices.trySend(AppNotice.Message("Verification finished. Retrying…"))
        load(append = false, userInitiated = true)
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
        val previous = _state.value.overlay as? HomeOverlay.Save
        val next = overlay as? HomeOverlay.Save
        if (previous?.videoId != next?.videoId) {
            sheetEpoch++
            sheetJob?.cancel()
            sheetJob = null
        }
        menuRevision++
        menuJob?.cancel()
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
                persistSaveDraft(overlay)
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

    private fun loadMenu() {
        val state = _state.value
        val menu = state.overlay as? HomeOverlay.Menu ?: return
        if (menu.kind != FeedMenuKind.Video || !state.live || !state.signedInPreview) return
        val video = (state.content as? HomeContent.Feed)?.page?.items?.filterIsInstance<FeedItem.Video>()
            ?.firstOrNull { it.id == menu.itemId } ?: return
        if (video.feedbackGeneration != state.sessionGeneration) return
        val revision = menuRevision
        val feedGeneration = generation
        _state.update { it.copy(overlay = menu.copy(loading = true)) }
        menuJob = viewModelScope.launch {
            try {
                val resolved = repository.menu(video)
                _state.update { current ->
                    if (revision != menuRevision || feedGeneration != generation ||
                        current.sessionGeneration != video.feedbackGeneration) return@update current
                    val active = current.overlay as? HomeOverlay.Menu ?: return@update current
                    val feed = current.content as? HomeContent.Feed ?: return@update current
                    current.copy(overlay = active.copy(loading = false), content = feed.copy(page = feed.page.copy(
                        items = feed.page.items.map { item ->
                            if (item is FeedItem.Video && item.id == video.id) item.copy(
                                notInterestedToken = resolved.notInterestedToken,
                                dontRecommendToken = resolved.dontRecommendToken) else item
                        })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { current ->
                    val active = current.overlay as? HomeOverlay.Menu
                    if (revision != menuRevision || feedGeneration != generation ||
                        current.sessionGeneration != video.feedbackGeneration || active == null) current
                    else current.copy(overlay = active.copy(loading = false, failed = true))
                }
            }
        }
    }

    private fun loadSaveChoices() {
        val save = uiState.value.overlay as? HomeOverlay.Save ?: return
        val repository = saveRepository ?: return
        sheetEpoch++
        sheetJob?.cancel()
        val owner = SaveOwner(sessionEpoch, sheetEpoch)
        sheetJob = viewModelScope.launch {
            try {
                val choices = repository.choices(save.videoId)
                if (!ownsSheet(owner)) return@launch
                editSaveOverlay(save.videoId) { it.copy(choices = choices, choicesFailed = false, pending = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) {
                if (!ownsSheet(owner)) return@launch
                overlay(HomeOverlay.None)
                notices.trySend(AppNotice.SignIn)
            }
            catch (_: Exception) {
                if (ownsSheet(owner)) {
                    editSaveOverlay(save.videoId) { it.copy(choices = null, choicesFailed = true, pending = false) }
                }
            }
        }
    }

    private fun saveWatchLater(videoId: String) {
        val repository = saveRepository ?: return
        if (backgroundWriteJob?.isActive == true) return
        val requestSession = sessionEpoch
        backgroundWriteJob = writes.launch(viewModelScope) {
            try {
                repository.saveWatchLater(videoId)
                if (sessionEpoch == requestSession) notices.trySend(AppNotice.Message("Saved to Watch later"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) {
                if (sessionEpoch == requestSession) {
                    notices.trySend(AppNotice.SignIn)
                }
            }
            catch (_: UnconfirmedActionException) {
                if (sessionEpoch == requestSession) {
                    notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again."))
                }
            }
            catch (_: Exception) {
                if (sessionEpoch == requestSession) {
                    notices.trySend(AppNotice.Message("Couldn't save this video. Try again."))
                }
            }
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
        val requestSession = sessionEpoch
        val requestScope = scopeEpoch
        val previousRead = loadJob
        fun ownsFeedback() = requestSession == sessionEpoch && requestScope == scopeEpoch
        feedbackJob = writes.launch(viewModelScope) {
            try {
                previousRead?.join()
                currentCoroutineContext().ensureActive()
                if (!ownsFeedback()) return@launch
                repository.feedback(video, action)
                currentCoroutineContext().ensureActive()
                if (!ownsFeedback()) return@launch
                _state.update { current ->
                    val feed = current.content as? HomeContent.Feed ?: return@update current
                    current.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.filterNot {
                        it.id == itemId || action == FeedMenuAction.DontRecommend && it is FeedItem.Video &&
                            video.channelId.isNotBlank() && it.channelId == video.channelId
                    })))
                }
                notices.trySend(AppNotice.Message("Removed from recommendations"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) {
                if (ownsFeedback()) {
                    notices.trySend(AppNotice.SignIn)
                }
            }
            catch (error: Exception) {
                if (ownsFeedback()) {
                    notices.trySend(com.hhst.youtubelite.diagnostics.writeFailureNotice(
                        "home_feedback_${action.name.lowercase()}", error,
                        mapOf("video_id" to itemId, "channel_id" to video.channelId),
                        message = "Couldn't update recommendations. Try again."))
                }
            }
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
        (_state.value.overlay as? HomeOverlay.Save)?.takeIf { it.videoId == videoId }?.let(::persistSaveDraft)
    }

    private fun editSave(save: HomeOverlay.Save) {
        _state.update { if (it.overlay is HomeOverlay.Save) it.copy(overlay = save) else it }
        persistSaveDraft(save)
    }

    private fun persistSaveDraft(save: HomeOverlay.Save) {
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
        val unique = linkedMapOf<String, FeedItem>()
        merged.forEach { item ->
            val old = unique[item.id]
            unique[item.id] = if (old is FeedItem.Video && item is FeedItem.Video) old.withMissingDisplayFrom(item) else old ?: item
        }
        return unique.values.toList()
    }

    /**
     * One shared, cached directory read per channel; a stale application is identity-safe
     * because it only rewrites rows still missing their avatar.
     */
    private fun fillMissingAvatars(items: List<FeedItem>) {
        val directory = avatars ?: return
        val missing = items.filterIsInstance<FeedItem.Video>()
            .filter { it.avatar.isNullOrBlank() && (it.channelId.isNotBlank() || it.menuEndpoint != null) }
            .distinctBy { it.channelId.ifBlank { it.id } }.takeIf { it.isNotEmpty() } ?: return
        avatarJob?.cancel()
        val requestAccount = accountKey()
        val requestFilter = _state.value.selectedChip
        val requestSession = _state.value.sessionGeneration
        avatarJob = viewModelScope.launch {
            val applyAvatar: (String, String) -> Unit = { channelId, avatar ->
                _state.update { state ->
                    if (requestAccount != accountKey() || requestFilter != state.selectedChip || requestSession != state.sessionGeneration) return@update state
                    val feed = state.content as? HomeContent.Feed ?: return@update state
                    state.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.map { item ->
                        if (item is FeedItem.Video && item.avatar.isNullOrBlank() && item.channelId == channelId)
                            item.copy(avatar = avatar) else item
                    })))
                }
            }
            val requestedIds = mutableSetOf<String>()
            val next = java.util.concurrent.atomic.AtomicInteger()
            kotlinx.coroutines.coroutineScope {
                repeat(minOf(3, missing.size)) {
                    launch {
                        while (true) {
                            val item = missing.getOrNull(next.getAndIncrement()) ?: break
                            val channelId = item.channelId.takeIf(String::isNotBlank) ?: item.menuEndpoint?.let {
                                directory.channel(item.id, com.liskovsoft.youtubeapi.videoinfo.V2.MenuEndpoint(it.panelId, it.params))
                            } ?: continue
                            requestedIds += channelId
                            if (item.channelId.isBlank()) _state.update { state ->
                                if (requestAccount != accountKey() || requestFilter != state.selectedChip || requestSession != state.sessionGeneration) return@update state
                                val feed = state.content as? HomeContent.Feed ?: return@update state
                                state.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.map { row ->
                                    if (row is FeedItem.Video && row.id == item.id && row.channelId.isBlank()) row.copy(channelId = channelId) else row
                                })))
                            }
                            directory.avatar(channelId)?.let { applyAvatar(channelId, it) }
                        }
                    }
                }
            }
            val pending = (_state.value.content as? HomeContent.Feed)?.page?.items.orEmpty()
                .filterIsInstance<FeedItem.Video>().filter { it.channelId in requestedIds && it.avatar.isNullOrBlank() }
                .map { it.channelId }.distinct()
            if (pending.isNotEmpty()) {
                delay(5_100)
                if (requestAccount == accountKey() && requestFilter == _state.value.selectedChip && requestSession == _state.value.sessionGeneration)
                    directory.resolveAll(pending, applyAvatar)
            }
        }
    }

    private fun load(append: Boolean, keepEmpty: Boolean = false, userInitiated: Boolean = false, automatic: Boolean = false) {
        val feed = uiState.value.content as? HomeContent.Feed
        val empty = uiState.value.content as? HomeContent.Empty
        val next = if (append) feed?.page?.nextKey ?: return else null
        if (append && (feed?.append == AppendState.Loading || feed?.refreshing == true)) return
        if (automatic && feed?.automaticPagingPaused == true) return
        loadJob?.cancel()
        menuJob?.cancel()
        menuRevision++
        _state.update { current ->
            val active = current.overlay as? HomeOverlay.Menu
            if (active == null) current else current.copy(overlay = active.copy(loading = false))
        }
        // Capture predecessors before launch: sibling jobs form a one-way dependency chain.
        val previousWrite = feedbackJob
        val requestGeneration = ++generation
        val requestAccount = accountKey()
        val filter = uiState.value.selectedChip
        _state.update { state -> state.copy(verification = null, content = when {
            append && feed != null -> feed.copy(append = AppendState.Loading)
            feed != null -> feed.copy(refreshing = true, append = AppendState.Idle, refreshFailed = false,
                userRefresh = userInitiated)
            // A quiet re-check keeps the empty card painted (no Loading flash).
            keepEmpty && empty != null -> empty.copy(refreshing = true, userRefresh = userInitiated)
            else -> HomeContent.Loading
        }) }
        loadJob = viewModelScope.launch {
            try {
                previousWrite?.join()
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                val page = repository.loadHome(filter, next)
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                liveResultReceived = true
                // Warm the top card's session/solver while the user browses so opening
                // it does not pay the watch-page read on the tap path.
                if (!append) (page.items.firstOrNull { it is FeedItem.Video } as? FeedItem.Video)?.id?.let(warm)
                if (!append) lastFeedLoadedAtMs = now()
                val currentFeed = _state.value.content as? HomeContent.Feed
                val loadedItems = currentFeed?.page?.items ?: feed?.page?.items.orEmpty()
                var combined = if (append && feed != null) page.copy(
                    items = mergeFeed(loadedItems, page.items),
                ) else page
                if (!append) consumedContinuations.clear()
                else if (!page.partialFailure && next != null) consumedContinuations += next
                if (!page.partialFailure && combined.nextKey in consumedContinuations) combined = combined.copy(nextKey = null)
                savedState["catalog"] = combined.catalog != null
                _state.update { it.copy(chips = if (combined.catalog != null) HomeCatalog.filters else repository.filters,
                    content = if (combined.items.isEmpty() && combined.nextKey == null) HomeContent.Empty(
                    title = combined.messages.firstOrNull() ?: HomeContent.Empty().title,
                ) else HomeContent.Feed(combined, append = if (combined.partialFailure) AppendState.Failed else AppendState.Idle,
                    emptyAutoLoads = if (append && combined.items.size <= loadedItems.size) (currentFeed?.emptyAutoLoads ?: 0) + 1 else 0)) }
                val store = snapshots
                if (store != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    store.write("home", requestAccount, filter,
                        if (combined.catalog == null) combined.items.filterIsInstance<FeedItem.Video>() else emptyList(),
                        FeedItem.Video::class.java)
                }
                if (requestGeneration != generation) return@launch
                fillMissingAvatars(combined.items)
                loadMenu()
                consumePendingRevisionAfterLoad()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
                liveResultReceived = true
                val feed = _state.value.content as? HomeContent.Feed ?: feed
                if (error.failure.isTrafficChallenge()) {
                    // Keep a working feed browsable and surface the challenge as a banner;
                    // a first-load challenge becomes the card's own state.
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
                val feed = _state.value.content as? HomeContent.Feed ?: feed
                _state.update { it.copy(content = if (feed != null) feed.copy(refreshing = false,
                    append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = !append) else HomeContent.Failed) }
                consumePendingRevisionAfterLoad()
            }
        }
    }

    /**
     * Consumes a signal merged while a request was in flight — one quiet re-check,
     * never discarded. Traffic challenges excluded: their banner owns the retry.
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
