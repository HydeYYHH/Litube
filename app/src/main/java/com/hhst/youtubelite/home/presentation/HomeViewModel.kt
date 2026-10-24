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
    private var generation = 0

    init {
        seedSnapshot()
        if (loadOnStart) load(append = false)
        if ((_state.value.overlay as? HomeOverlay.Save)?.live == true) loadSaveChoices()
    }

    /** Cold start paints the last captured rows and the live load replaces them. */
    private fun seedSnapshot() {
        val store = snapshots ?: return
        val rows = store.read<FeedItem.Video>("home", accountKey(), _state.value.selectedChip,
            com.google.gson.reflect.TypeToken.getParameterized(List::class.java, FeedItem.Video::class.java).type) ?: return
        if (rows.isEmpty()) return
        _state.update { state ->
            if (state.content !is HomeContent.Empty && state.content !is HomeContent.Loading) return@update state
            state.copy(content = HomeContent.Feed(HomePage(items = rows), refreshing = true))
        }
    }

    fun setHideShorts(hidden: Boolean) { _state.update { it.copy(hideShorts = hidden) } }

    fun setSignedInPreview(signedIn: Boolean) {
        savedState["signedInPreview"] = signedIn
        _state.update { it.copy(signedInPreview = signedIn) }
    }

    private var sessionKey: Long? = null

    fun updateSession(signedIn: Boolean, key: Long, avatar: String? = null) {
        val changed = sessionKey != null && sessionKey != key
        sessionKey = key
        setSignedInPreview(signedIn)
        _state.update { it.copy(avatar = avatar) }
        if (changed) {
            overlay(HomeOverlay.None)
            load(append = false)
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
            val resolved = missing.mapNotNull { video ->
                directory.avatar(video.channelId)?.let { video.channelId to it }
            }.toMap()
            if (resolved.isEmpty()) return@launch
            _state.update { state ->
                val feed = state.content as? HomeContent.Feed ?: return@update state
                state.copy(content = feed.copy(page = feed.page.copy(items = feed.page.items.map { item ->
                    if (item is FeedItem.Video && item.avatar == null) resolved[item.channelId]?.let { item.copy(avatar = it) } ?: item
                    else item
                })))
            }
        }
    }

    private fun load(append: Boolean) {
        val feed = uiState.value.content as? HomeContent.Feed
        val next = if (append) feed?.page?.nextKey ?: return else null
        if (append && (feed?.append == AppendState.Loading || feed?.refreshing == true)) return
        loadJob?.cancel()
        val requestGeneration = ++generation
        val filter = uiState.value.selectedChip
        _state.update { state -> state.copy(verification = null, content = when {
            append && feed != null -> feed.copy(append = AppendState.Loading)
            feed != null -> feed.copy(refreshing = true, append = AppendState.Idle, refreshFailed = false)
            else -> HomeContent.Loading
        }) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.loadHome(filter, next)
                if (requestGeneration != generation) return@launch
                val combined = if (append && feed != null) page.copy(items = mergeFeed(feed.page.items, page.items)) else page
                _state.update { it.copy(content = if (combined.items.isEmpty()) HomeContent.Empty(
                    title = combined.messages.firstOrNull() ?: HomeContent.Empty().title,
                ) else HomeContent.Feed(combined)) }
                snapshots?.write("home", accountKey(), filter, combined.items.filterIsInstance<FeedItem.Video>())
                fillMissingAvatars(combined.items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
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
                }
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = if (feed != null) feed.copy(refreshing = false,
                    append = if (append) AppendState.Failed else AppendState.Idle, refreshFailed = !append) else HomeContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
