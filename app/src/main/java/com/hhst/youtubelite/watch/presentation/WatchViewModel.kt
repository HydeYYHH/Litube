package com.hhst.youtubelite.watch.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.isTrafficChallenge

class WatchViewModel(
    private val repository: WatchRepository,
    private val savedState: SavedStateHandle,
    videoId: String,
    scenario: WatchScenario = WatchScenario.Content,
    private val voteRepository: VoteCountRepository? = null,
    private val videoActions: VideoActions? = null,
    private val saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
    provisional: WatchProvisional? = null,
) : ViewModel() {
    private val restoredId = savedState.get<String>("videoId") ?: videoId
    private val restoredOverlay = when (savedState.get<String>("overlay")) {
        "search" -> WatchOverlay.Search
        "menu" -> savedState.get<String>("menuVideo")?.let { WatchOverlay.ItemMenu(it) }
        "save" -> savedState.get<String>("saveVideo")?.let {
            WatchOverlay.Save(
                it,
                savedState.get<Boolean>("saveNewPlaylist") == true,
                savedState.get<String>("saveTitle").orEmpty(),
                savedState.get<String>("saveDescription").orEmpty(),
                live = saveRepository != null,
            )
        }
        "feedback" -> {
            val id = savedState.get<String>("feedbackVideo")
            val kind = savedState.get<String>("feedbackKind")?.let { WatchFeedbackKind.valueOf(it) }
            if (id != null && kind != null) WatchOverlay.Feedback(id, kind) else null
        }
        else -> WatchOverlay.None
    } ?: WatchOverlay.None
    private val _state = MutableStateFlow(
        WatchUiState(
            videoId = restoredId,
            content = scenario.initialContent(),
            descriptionExpanded = savedState["descriptionExpanded"] ?: false,
            overlay = restoredOverlay,
            draftQuery = savedState["draftQuery"] ?: "",
            commentsOpen = savedState["commentsOpen"] ?: false,
            live = saveRepository != null,
            provisional = provisional,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<WatchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var actionJob: Job? = null
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var menuJob: Job? = null
    private var menuRevision = 0
    private val consumedCursors = mutableSetOf<String>()
    private var saveJob: Job? = null
    private var generation = 0
    /** Identity epoch captured by save writes so a switched session's unwind cannot touch the new one. */
    private var sessionGeneration = 0L
    private fun sessionStale(request: Long) = sessionGeneration != request
    private var voteJob: Job? = null
    private var voteFlags: Pair<Boolean, Boolean>? = null
    private var counts: VoteCounts? = null

    fun refreshSession() {
        sessionGeneration++
        actionJob?.cancel()
        saveJob?.cancel()
        _state.update { it.copy(actionPending = false) }
        overlay(WatchOverlay.None)
        load()
    }

    fun updateCommentsPreview(videoId: String, status: WatchCommentsStatus, total: String, comment: WatchComment) {
        if (videoId != _state.value.videoId) return
        _state.update { state ->
            val ready = state.content as? WatchContent.Ready ?: return@update state
            state.copy(content = ready.copy(page = ready.page.copy(video = ready.page.video.copy(
                commentsStatus = status, commentCount = total.ifBlank { ready.page.video.commentCount }, topComment = comment))))
        }
    }

    fun selectVideo(id: String, provisional: WatchProvisional? = null) {
        if (id == uiState.value.videoId) return
        voteJob?.cancel()
        actionJob?.cancel()
        counts = null
        val flags = voteFlags
        voteFlags = null
        savedState["videoId"] = id
        savedState["commentsOpen"] = false
        savedState["descriptionExpanded"] = false
        _state.value = WatchUiState(videoId = id, live = saveRepository != null, provisional = provisional)
        persistOverlay(WatchOverlay.None)
        load()
        flags?.let { refreshVoteCounts(it.first, it.second) }
    }

    fun refreshVoteCounts(showLikes: Boolean, showDislikes: Boolean) {
        if (voteRepository == null) return
        val flags = showLikes to showDislikes
        if (voteFlags == flags) return
        voteFlags = flags
        voteJob?.cancel()
        updateCounts()
        if (!showLikes && !showDislikes) return
        voteJob = viewModelScope.launch {
            val id = uiState.value.videoId
            try {
                val result = voteRepository.counts(id)
                if (uiState.value.videoId != id || voteFlags != flags) return@launch
                counts = result
                updateCounts()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Counts are optional; metadata and playback remain available. */ }
        }
    }

    private fun updateCounts() {
        val flags = voteFlags ?: return
        _state.update { state ->
            val content = state.content as? WatchContent.Ready ?: return@update state
            val likes = counts?.likes?.takeIf { flags.first }?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) }
            val dislikes = counts?.dislikes?.takeIf { flags.second }?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) }
            state.copy(content = content.copy(page = content.page.copy(video = content.page.video.copy(
                likeCount = likes, dislikeCount = dislikes, likes = likes?.let { "$it likes" } ?: ""))))
        }
    }

    init {
        savedState["videoId"] = _state.value.videoId
        persistOverlay(restoredOverlay)
        if (scenario.loadsContent() && _state.value.content is WatchContent.Loading) load()
        if ((_state.value.overlay as? WatchOverlay.Save)?.live == true) loadSaveChoices()
    }

    fun onAction(action: WatchAction) {
        val videoId = uiState.value.videoId
        when (action) {
            WatchAction.Retry -> load(fresh = true)
            WatchAction.LoadRelated -> loadRelated()
            WatchAction.VoiceSearch -> navigation.trySend(WatchNavigationRequest.VoiceSearch)
            WatchAction.ToggleDescription -> toggleDescription()
            is WatchAction.SeekToTimestamp -> {
                val video = (uiState.value.content as? WatchContent.Ready)?.page?.video ?: return
                // A live stream has no fixed timeline: explain instead of seeking.
                if (video.live) notices.trySend(AppNotice.Message("Live streams can't be seeked to a timestamp."))
                else navigation.trySend(WatchNavigationRequest.SeekTo(action.seconds))
            }
            is WatchAction.OpenDescriptionLink -> navigation.trySend(WatchNavigationRequest.OpenLink(action.url))
            WatchAction.OpenSearch -> editSearch(true, uiState.value.draftQuery)
            WatchAction.CloseSearch -> editSearch(false, uiState.value.draftQuery)
            is WatchAction.QueryChanged -> editSearch(true, action.query, reopen = uiState.value.overlay is WatchOverlay.Search)
            is WatchAction.SubmitSearch -> {
                val query = action.query.trim()
                if (query.isNotEmpty()) {
                    editSearch(false, query)
                    navigation.trySend(WatchNavigationRequest.Search(query))
                }
            }
            WatchAction.OpenPageMenu -> navigation.trySend(WatchNavigationRequest.PageMenu)
            WatchAction.Like -> if (videoActions == null) navigation.trySend(WatchNavigationRequest.Like(videoId)) else rate(VideoRating.Like)
            WatchAction.Dislike -> if (videoActions == null) navigation.trySend(WatchNavigationRequest.Dislike(videoId)) else rate(VideoRating.Dislike)
            WatchAction.Download -> navigation.trySend(WatchNavigationRequest.Download(videoId))
            WatchAction.Queue -> navigation.trySend(WatchNavigationRequest.Queue(videoId))
            WatchAction.OpenWith -> navigation.trySend(WatchNavigationRequest.OpenWith(videoId))
            WatchAction.Share -> navigation.trySend(WatchNavigationRequest.Share(videoId))
            WatchAction.Save -> openSave(videoId)
            is WatchAction.SaveChoiceToggled -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                val repository = saveRepository ?: return
                if (!save.live || save.pending) return
                val target = !action.choice.contains
                editSave(save.copy(pending = true))
                val requestSession = sessionGeneration
                saveJob = viewModelScope.launch {
                    try {
                        repository.setContaining(action.choice.id, save.videoId, target)
                        if (sessionStale(requestSession)) return@launch
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, choices = current.choices?.map {
                                if (it.id == action.choice.id) it.copy(contains = target) else it
                            })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) { if (!sessionStale(requestSession)) { overlay(WatchOverlay.None); notices.trySend(AppNotice.SignIn) } }
                    catch (error: UnconfirmedActionException) { if (!sessionStale(requestSession)) saveFailed(save.videoId, report("watch_save_toggle", error, save.videoId,
                        message = UNCONFIRMED_SAVE)) }
                    catch (error: Exception) { if (!sessionStale(requestSession)) saveFailed(save.videoId, report("watch_save_toggle", error, save.videoId,
                        message = "Couldn't update this playlist. Try again.")) }
                }
            }
            WatchAction.SaveChoicesRetry -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                if (save.live) {
                    editSave(save.copy(choicesFailed = false, choices = null))
                    loadSaveChoices()
                }
            }
            is WatchAction.SaveDestinationSelected -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                overlay(WatchOverlay.None)
                when (val destination = action.destination) {
                    com.hhst.youtubelite.saves.domain.SaveDestination.WatchLater ->
                        navigation.trySend(WatchNavigationRequest.SaveToWatchLater(save.videoId))
                    is com.hhst.youtubelite.saves.domain.SaveDestination.Playlist ->
                        navigation.trySend(
                            WatchNavigationRequest.SaveToPlaylist(save.videoId, destination.id, destination.title),
                        )
                }
            }
            WatchAction.OpenNewPlaylist -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                overlay(save.copy(newPlaylist = true, title = "", description = ""))
            }
            is WatchAction.SaveDraftChanged -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                overlay(save.copy(title = action.title, description = action.description))
            }
            WatchAction.SubmitNewPlaylist -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                val title = save.title.trim()
                if (title.isEmpty() || save.pending) return
                if (!save.live) {
                    overlay(WatchOverlay.None)
                    navigation.trySend(WatchNavigationRequest.CreatePlaylist(save.videoId, title, save.description.trim()))
                    return
                }
                val repository = saveRepository ?: return
                editSave(save.copy(pending = true))
                val requestSession = sessionGeneration
                saveJob = viewModelScope.launch {
                    try {
                        val created = repository.create(title, save.description.trim(), save.videoId)
                        if (sessionStale(requestSession)) return@launch
                        editSaveOverlay(save.videoId) { current ->
                            current.copy(pending = false, newPlaylist = false, title = "", description = "",
                                choices = (current.choices ?: emptyList()) + created)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SignInRequiredException) { if (!sessionStale(requestSession)) { overlay(WatchOverlay.None); notices.trySend(AppNotice.SignIn) } }
                    catch (error: UnconfirmedActionException) { if (!sessionStale(requestSession)) saveFailed(save.videoId, report("watch_playlist_create", error, save.videoId,
                        message = UNCONFIRMED_SAVE)) }
                    catch (error: Exception) { if (!sessionStale(requestSession)) saveFailed(save.videoId, report("watch_playlist_create", error, save.videoId,
                        message = "Couldn't create this playlist. Try again.")) }
                }
            }
            WatchAction.Report -> overlay(WatchOverlay.Feedback(videoId, WatchFeedbackKind.Report))
            is WatchAction.FeedbackReason -> {
                val feedback = uiState.value.overlay as? WatchOverlay.Feedback ?: return
                overlay(WatchOverlay.None)
                navigation.trySend(WatchNavigationRequest.Feedback(feedback.videoId, feedback.kind, action.reason))
            }
            WatchAction.Subscribe -> {
                val channel = (uiState.value.content as? WatchContent.Ready)?.page?.video?.channelId ?: return
                if (videoActions == null) navigation.trySend(WatchNavigationRequest.Subscribe(channel))
                else performVideoAction("watch_subscribe") { current ->
                    val subscribed = !current.subscribed
                    videoActions.subscribe(channel, subscribed)
                    current.copy(subscribed = subscribed)
                }
            }
            is WatchAction.OpenChannel -> navigation.trySend(WatchNavigationRequest.Channel(action.channelId))
            is WatchAction.OpenComments -> {
                // A current live stream without a chat key has no usable chat; the
                // comments panel must not silently take its place.
                val video = (uiState.value.content as? WatchContent.Ready)?.page?.video
                if (video?.live == true && video.chatKey.isNullOrBlank()) return
                overlay(WatchOverlay.None)
                editComments(true)
                navigation.trySend(WatchNavigationRequest.Comments(action.videoId))
            }
            WatchAction.CloseComments -> editComments(false)
            is WatchAction.OpenRelated -> navigation.trySend(WatchNavigationRequest.Watch(action.videoId))
            is WatchAction.OpenItemMenu -> {
                overlay(WatchOverlay.ItemMenu(action.videoId))
                loadMenu(action.videoId)
            }
            WatchAction.RetryMenu -> (uiState.value.overlay as? WatchOverlay.ItemMenu)?.let { loadMenu(it.videoId) }
            WatchAction.CloseOverlay -> overlay(WatchOverlay.None)
            WatchAction.DismissVerification -> _state.update { it.copy(verification = null) }
            WatchAction.OpenVerification -> uiState.value.verification?.let {
                navigation.trySend(WatchNavigationRequest.OpenVerification(it.challengeUrl))
            }
            is WatchAction.MenuAction -> {
                val menu = uiState.value.overlay as? WatchOverlay.ItemMenu ?: return
                when (action.action) {
                    WatchVideoMenuAction.Queue, WatchVideoMenuAction.Download -> {
                        overlay(WatchOverlay.None)
                        navigation.trySend(if (action.action == WatchVideoMenuAction.Queue) WatchNavigationRequest.Queue(menu.videoId) else WatchNavigationRequest.Download(menu.videoId))
                    }
                    WatchVideoMenuAction.WatchLater -> {
                        overlay(WatchOverlay.None)
                        if (saveRepository == null) navigation.trySend(WatchNavigationRequest.SaveToWatchLater(menu.videoId))
                        else saveWatchLater(menu.videoId)
                    }
                    WatchVideoMenuAction.SaveToPlaylist -> openSave(menu.videoId)
                    WatchVideoMenuAction.Share -> {
                        overlay(WatchOverlay.None)
                        navigation.trySend(WatchNavigationRequest.Share(menu.videoId))
                    }
                    WatchVideoMenuAction.NotInterested, WatchVideoMenuAction.DontRecommend -> if (uiState.value.live) {
                        overlay(WatchOverlay.None)
                        sendFeedback(menu.videoId, action.action)
                    } else overlay(WatchOverlay.Feedback(menu.videoId,
                        if (action.action == WatchVideoMenuAction.NotInterested) WatchFeedbackKind.NotInterested else WatchFeedbackKind.DontRecommend))
                }
            }
        }
    }

    private fun sendFeedback(id: String, action: WatchVideoMenuAction) {
        if (actionJob?.isActive == true) return
        val ready = uiState.value.content as? WatchContent.Ready ?: return
        val video = ready.page.related.firstOrNull { it.id == id } ?: return
        val requestGeneration = generation
        actionJob = viewModelScope.launch {
            try {
                repository.feedback(video, action)
                if (generation != requestGeneration) return@launch
                _state.update { state ->
                    val content = state.content as? WatchContent.Ready ?: return@update state
                    state.copy(content = content.copy(page = content.page.copy(related = content.page.related.filterNot {
                        it.id == id || action == WatchVideoMenuAction.DontRecommend && video.channelId.isNotBlank() && it.channelId == video.channelId
                    })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { if (generation == requestGeneration) notices.trySend(AppNotice.SignIn) }
            catch (error: Exception) { if (generation == requestGeneration) notices.trySend(report(
                "watch_feedback_${action.name.lowercase()}", error, id)) }
        }
    }

    private fun loadMenu(id: String) {
        menuJob?.cancel()
        val revision = ++menuRevision
        val requestGeneration = generation
        val video = (uiState.value.content as? WatchContent.Ready)?.page?.related?.firstOrNull { it.id == id } ?: return
        if (!uiState.value.live || video.menuEndpoint == null) return
        _state.update { it.copy(menuLoading = true, menuFailed = false) }
        menuJob = viewModelScope.launch {
            try {
                val loaded = repository.menu(video)
                if (revision != menuRevision || requestGeneration != generation) return@launch
                _state.update { current ->
                    val content = current.content as? WatchContent.Ready ?: return@update current
                    current.copy(menuLoading = false, content = content.copy(page = content.page.copy(
                        related = content.page.related.map { if (it.id == id) loaded else it })))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == menuRevision && requestGeneration == generation)
                    _state.update { it.copy(menuLoading = false, menuFailed = true) }
            }
        }
    }

    private fun rate(selection: VideoRating) = performVideoAction("watch_rate") { video ->
        val next = if (video.rating == selection) VideoRating.None else selection
        videoActions!!.rate(video.id, next, video.rating)
        video.copy(rating = next)
    }

    private fun openSave(videoId: String) {
        overlay(WatchOverlay.Save(videoId, live = saveRepository != null))
        if (saveRepository != null) loadSaveChoices()
    }

    private fun loadSaveChoices() {
        val save = uiState.value.overlay as? WatchOverlay.Save ?: return
        val repository = saveRepository ?: return
        saveJob?.cancel()
        val requestSession = sessionGeneration
        saveJob = viewModelScope.launch {
            try {
                val choices = repository.choices(save.videoId)
                if (sessionStale(requestSession)) return@launch
                editSaveOverlay(save.videoId) { it.copy(choices = choices, choicesFailed = false, pending = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { if (!sessionStale(requestSession)) { overlay(WatchOverlay.None); notices.trySend(AppNotice.SignIn) } }
            catch (_: Exception) { if (!sessionStale(requestSession)) editSaveOverlay(save.videoId) { it.copy(choices = null, choicesFailed = true, pending = false) } }
        }
    }

    private fun saveWatchLater(videoId: String) {
        val repository = saveRepository ?: return
        if (saveJob?.isActive == true) return
        val requestSession = sessionGeneration
        saveJob = viewModelScope.launch {
            try {
                repository.saveWatchLater(videoId)
                if (!sessionStale(requestSession)) notices.trySend(AppNotice.Message("Saved to Watch later"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { if (!sessionStale(requestSession)) notices.trySend(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { if (!sessionStale(requestSession)) notices.trySend(report("watch_save_watch_later", error, videoId, UNCONFIRMED_SAVE)) }
            catch (error: Exception) { if (!sessionStale(requestSession)) notices.trySend(report("watch_save_watch_later", error, videoId,
                message = "Couldn't save this video. Try again.")) }
        }
    }

    private fun saveFailed(videoId: String, notice: AppNotice) {
        editSaveOverlay(videoId) { it.copy(pending = false) }
        notices.trySend(notice)
    }

    private companion object {
        const val UNCONFIRMED_SAVE = "The server did not confirm this change. Please refresh before trying again."
    }

    /** Copyable failure notice for an account write, carrying the sanitized report. */
    private fun report(operation: String, error: Exception, videoId: String? = null,
        message: String? = null): AppNotice =
        com.hhst.youtubelite.diagnostics.writeFailureNotice(operation, error,
            videoId?.let { mapOf("video_id" to it) } ?: emptyMap(), message)

    /** Edits the live Save overlay only while it still belongs to [videoId]; dismissals and
     *  video switches make in-flight writes no-ops instead of resurrecting a stale sheet. */
    private fun editSaveOverlay(videoId: String, edit: (WatchOverlay.Save) -> WatchOverlay.Save) {
        _state.update { state ->
            val save = state.overlay as? WatchOverlay.Save
            if (save == null || save.videoId != videoId) state else state.copy(overlay = edit(save))
        }
        (uiState.value.overlay as? WatchOverlay.Save)?.let { persistOverlay(it) }
    }

    private fun editSave(save: WatchOverlay.Save) {
        _state.update { if (it.overlay is WatchOverlay.Save) it.copy(overlay = save) else it }
        persistOverlay(save)
    }

    private fun performVideoAction(operation: String, action: suspend (WatchVideo) -> WatchVideo) {
        if (actionJob?.isActive == true) return
        val ready = _state.value.content as? WatchContent.Ready ?: return
        val id = _state.value.videoId
        val requestGeneration = generation
        fun isCurrent() = generation == requestGeneration && _state.value.videoId == id
        _state.update { it.copy(actionPending = true) }
        actionJob = viewModelScope.launch {
            try {
                val video = action(ready.page.video)
                currentCoroutineContext().ensureActive()
                if (isCurrent()) _state.update { state ->
                    val content = state.content as? WatchContent.Ready ?: return@update state
                    state.copy(content = content.copy(page = content.page.copy(video = content.page.video.copy(
                        rating = video.rating, subscribed = video.subscribed))))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { if (isCurrent()) notices.trySend(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { if (isCurrent()) notices.trySend(report(operation, error, message = UNCONFIRMED_SAVE)) }
            catch (error: Exception) { if (isCurrent()) notices.trySend(report(operation, error, message = "Couldn't update this video. Try again.")) }
            finally { if (isCurrent()) _state.update { it.copy(actionPending = false) } }
        }
    }

    private fun editSearch(open: Boolean, query: String, reopen: Boolean = true) {
        savedState["draftQuery"] = query
        _state.update {
            // A stale IME commit can outlive a submit that just closed the panel;
            // a typing event never re-opens a search the user already left.
            it.copy(overlay = if (open && reopen) WatchOverlay.Search else WatchOverlay.None, draftQuery = query)
        }
        persistOverlay(uiState.value.overlay)
    }

    private fun overlay(next: WatchOverlay) {
        menuJob?.cancel()
        menuRevision++
        _state.update { it.copy(overlay = next, menuLoading = false, menuFailed = false) }
        persistOverlay(next)
    }

    private fun persistOverlay(overlay: WatchOverlay) {
        savedState["overlay"] = when (overlay) {
            WatchOverlay.Search -> "search"
            is WatchOverlay.ItemMenu -> {
                savedState["menuVideo"] = overlay.videoId
                "menu"
            }
            is WatchOverlay.Save -> {
                savedState["saveVideo"] = overlay.videoId
                savedState["saveNewPlaylist"] = overlay.newPlaylist
                savedState["saveTitle"] = overlay.title
                savedState["saveDescription"] = overlay.description
                "save"
            }
            is WatchOverlay.Feedback -> {
                savedState["feedbackVideo"] = overlay.videoId
                savedState["feedbackKind"] = overlay.kind.name
                "feedback"
            }
            WatchOverlay.None -> {
                savedState.remove<String>("menuVideo")
                savedState.remove<String>("saveVideo")
                savedState.remove<Boolean>("saveNewPlaylist")
                savedState.remove<String>("saveTitle")
                savedState.remove<String>("saveDescription")
                savedState.remove<String>("feedbackVideo")
                savedState.remove<String>("feedbackKind")
                "none"
            }
        }
    }

    private fun editComments(open: Boolean) {
        savedState["commentsOpen"] = open
        _state.update { it.copy(commentsOpen = open) }
    }

    private fun toggleDescription() {
        val expanded = !uiState.value.descriptionExpanded
        savedState["descriptionExpanded"] = expanded
        _state.update { it.copy(descriptionExpanded = expanded) }
    }

    private fun loadRelated() {
        val current = _state.value
        val ready = current.content as? WatchContent.Ready ?: return
        val cursor = ready.page.nextKey ?: return
        if (current.relatedLoading || cursor in consumedCursors) return
        val requestGeneration = generation
        _state.update { it.copy(relatedLoading = true, relatedFailed = false) }
        relatedJob = viewModelScope.launch {
            try {
                val page = repository.next(current.videoId, cursor)
                if (requestGeneration != generation) return@launch
                consumedCursors += cursor
                _state.update { state ->
                    val loaded = state.content as? WatchContent.Ready ?: return@update state
                    state.copy(content = loaded.copy(page = loaded.page.copy(
                        related = (loaded.page.related + page.items).distinctBy { it.id },
                        nextKey = page.nextKey?.takeUnless { it in consumedCursors })),
                        relatedLoading = false, relatedFailed = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation) _state.update { it.copy(relatedLoading = false, relatedFailed = true) }
            }
        }
    }

    private fun load(fresh: Boolean = false) {
        val videoId = uiState.value.videoId
        loadJob?.cancel()
        menuJob?.cancel()
        menuRevision++
        relatedJob?.cancel()
        consumedCursors.clear()
        val requestGeneration = ++generation
        _state.update { it.copy(content = WatchContent.Loading, verification = null, relatedLoading = false, relatedFailed = false,
            menuLoading = false, menuFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val page = if (fresh) repository.watchFresh(videoId) else repository.watch(videoId)
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = WatchContent.Ready(page)) }
                updateCounts()
                (uiState.value.overlay as? WatchOverlay.ItemMenu)?.let { loadMenu(it.videoId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
                if (error.failure.isTrafficChallenge()) {
                    _state.update { it.copy(content = WatchContent.Failed,
                        verification = com.hhst.youtubelite.core.VerificationRequired(error.failure.detail!!)) }
                } else {
                    _state.update { it.copy(content = WatchContent.Failed) }
                }
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = WatchContent.Failed) }
            }
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
        load()
    }

    override fun onCleared() { navigation.close() }
}
