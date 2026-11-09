package com.hhst.youtubelite.watch.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.WriteFeedback
import com.hhst.youtubelite.core.isTrafficChallenge
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.watch.domain.VideoActions
import com.hhst.youtubelite.watch.domain.VideoRating
import com.hhst.youtubelite.watch.domain.VoteCountRepository
import com.hhst.youtubelite.watch.domain.VoteCounts
import com.hhst.youtubelite.watch.domain.WatchAction
import com.hhst.youtubelite.watch.domain.WatchComment
import com.hhst.youtubelite.watch.domain.WatchCommentsStatus
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchFeedbackKind
import com.hhst.youtubelite.watch.domain.WatchNavigationRequest
import com.hhst.youtubelite.watch.domain.WatchOverlay
import com.hhst.youtubelite.watch.domain.WatchProvisional
import com.hhst.youtubelite.watch.domain.WatchRepository
import com.hhst.youtubelite.watch.domain.WatchUiState
import com.hhst.youtubelite.watch.domain.WatchVideo
import com.hhst.youtubelite.watch.domain.WatchVideoMenuAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WatchViewModel(
    private val repository: WatchRepository,
    private val savedState: SavedStateHandle,
    videoId: String,
    scenario: WatchScenario = WatchScenario.Content,
    private val voteRepository: VoteCountRepository? = null,
    private val videoActions: VideoActions? = null,
    private val saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
    provisional: WatchProvisional? = null,
    loadOnStart: Boolean = true,
    private val avatars: com.hhst.youtubelite.data.media.MediaAvatarDirectory? = null,
) : ViewModel() {
    private var loadsEnabled = loadOnStart

    fun startLoading() {
        if (loadsEnabled) return
        loadsEnabled = true
        val pendingVotes = voteFlags
        voteFlags = null
        load()
        pendingVotes?.let { refreshVoteCounts(it.first, it.second) }
    }
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
    val writes = WriteFeedback()
    private var actionJob: Job? = null
    private var feedbackJob: Job? = null
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var avatarJob: Job? = null
    private var menuJob: Job? = null
    private var menuRevision = 0
    private val consumedCursors = mutableSetOf<String>()
    private var sheetJob: Job? = null
    private var backgroundWriteJob: Job? = null
    private var sheetEpoch = 0L
    private data class SaveOwner(val session: Long, val sheet: Long)
    private fun ownsSheet(owner: SaveOwner) = owner.session == sessionGeneration && owner.sheet == sheetEpoch
    private var generation = 0
    /** Identity epoch captured by save writes so a switched session's unwind cannot touch the new one. */
    private var sessionGeneration = 0L
    private fun sessionStale(request: Long) = sessionGeneration != request
    private var voteJob: Job? = null
    private var voteFlags: Pair<Boolean, Boolean>? = null
    private var counts: VoteCounts? = null
    // Aggregate counts lag behind account writes. Keep confirmed viewer changes
    // separate so a late count response cannot erase them or double-apply them.
    private var likeCountDelta = 0L
    private var dislikeCountDelta = 0L

    fun refreshSession() {
        sessionGeneration++
        writes.reset()
        actionJob?.cancel()
        feedbackJob?.cancel()
        sheetJob?.cancel()
        sheetJob = null
        backgroundWriteJob?.cancel()
        backgroundWriteJob = null
        _state.update { it.copy(actionPending = false, pendingVideoAction = null) }
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
        feedbackJob?.cancel()
        counts = null
        likeCountDelta = 0L
        dislikeCountDelta = 0L
        val flags = voteFlags
        voteFlags = null
        savedState["videoId"] = id
        savedState["commentsOpen"] = false
        savedState["descriptionExpanded"] = false
        overlay(WatchOverlay.None)
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
        if (!loadsEnabled || !showLikes && !showDislikes || counts != null) return
        voteJob = viewModelScope.launch {
            val id = uiState.value.videoId
            try {
                val result = voteRepository.counts(id)
                currentCoroutineContext().ensureActive()
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
            val likes = counts?.likes?.takeIf { flags.first }?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format((it + likeCountDelta).coerceAtLeast(0)) }
            val dislikes = counts?.dislikes?.takeIf { flags.second }?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format((it + dislikeCountDelta).coerceAtLeast(0)) }
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
        startLoading()
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
                sheetEpoch++
                sheetJob?.cancel()
                val owner = SaveOwner(sessionGeneration, sheetEpoch)
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
                        overlay(WatchOverlay.None)
                        notices.trySend(AppNotice.SignIn)
                    }
                    catch (error: UnconfirmedActionException) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, report("watch_save_toggle", error, save.videoId,
                                message = UNCONFIRMED_SAVE))
                        }
                    }
                    catch (error: Exception) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, report("watch_save_toggle", error, save.videoId,
                                message = "Couldn't update this playlist. Try again."))
                        }
                    }
                }
            }
            WatchAction.SaveChoicesRetry -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                if (save.live && !save.pending) {
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
                if (save.pending) return
                overlay(save.copy(newPlaylist = true, title = "", description = ""))
            }
            is WatchAction.SaveDraftChanged -> {
                val save = uiState.value.overlay as? WatchOverlay.Save ?: return
                if (save.pending) return
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
                sheetEpoch++
                sheetJob?.cancel()
                val owner = SaveOwner(sessionGeneration, sheetEpoch)
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
                        overlay(WatchOverlay.None)
                        notices.trySend(AppNotice.SignIn)
                    }
                    catch (error: UnconfirmedActionException) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, report("watch_playlist_create", error, save.videoId,
                                message = UNCONFIRMED_SAVE))
                        }
                    }
                    catch (error: Exception) {
                        if (ownsSheet(owner)) {
                            saveFailed(save.videoId, report("watch_playlist_create", error, save.videoId,
                                message = "Couldn't create this playlist. Try again."))
                        }
                    }
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
                else performVideoAction("watch_subscribe", WatchAction.Subscribe) { current ->
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
            WatchAction.CloseOverlay -> {
                if ((uiState.value.overlay as? WatchOverlay.Save)?.pending != true) overlay(WatchOverlay.None)
            }
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
        if (feedbackJob?.isActive == true) return
        val ready = uiState.value.content as? WatchContent.Ready ?: return
        val video = ready.page.related.firstOrNull { it.id == id } ?: return
        val requestGeneration = generation
        feedbackJob = writes.launch(viewModelScope) {
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
        if (!uiState.value.live || video.menuEndpoint == null && video.feedbackGeneration == null) return
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

    private fun rate(selection: VideoRating) = performVideoAction("watch_rate", if (selection == VideoRating.Like) WatchAction.Like else WatchAction.Dislike) { video ->
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
        sheetEpoch++
        sheetJob?.cancel()
        val owner = SaveOwner(sessionGeneration, sheetEpoch)
        sheetJob = viewModelScope.launch {
            try {
                val choices = repository.choices(save.videoId)
                if (!ownsSheet(owner)) return@launch
                editSaveOverlay(save.videoId) { it.copy(choices = choices, choicesFailed = false, pending = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) {
                if (!ownsSheet(owner)) return@launch
                overlay(WatchOverlay.None)
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
        val requestSession = sessionGeneration
        backgroundWriteJob = writes.launch(viewModelScope) {
            try {
                repository.saveWatchLater(videoId)
                if (!sessionStale(requestSession)) notices.trySend(AppNotice.Message("Saved to Watch later"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) {
                if (!sessionStale(requestSession)) {
                    notices.trySend(AppNotice.SignIn)
                }
            }
            catch (error: UnconfirmedActionException) {
                if (!sessionStale(requestSession)) {
                    notices.trySend(report("watch_save_watch_later", error, videoId, UNCONFIRMED_SAVE))
                }
            }
            catch (error: Exception) {
                if (!sessionStale(requestSession)) {
                    notices.trySend(report("watch_save_watch_later", error, videoId,
                        message = "Couldn't save this video. Try again."))
                }
            }
        }
    }

    private fun saveFailed(videoId: String, notice: AppNotice) {
        editSaveOverlay(videoId) { it.copy(pending = false) }
        notices.trySend(notice)
    }

    private companion object {
        const val UNCONFIRMED_SAVE = "The server did not confirm this change. Please refresh before trying again."
    }

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

    private fun performVideoAction(operation: String, pending: WatchAction, action: suspend (WatchVideo) -> WatchVideo) {
        if (actionJob?.isActive == true) return
        val ready = _state.value.content as? WatchContent.Ready ?: return
        val id = _state.value.videoId
        val requestGeneration = generation
        fun isCurrent() = generation == requestGeneration && _state.value.videoId == id
        _state.update { it.copy(actionPending = true, pendingVideoAction = pending) }
        actionJob = viewModelScope.launch {
            try {
                val video = action(ready.page.video)
                currentCoroutineContext().ensureActive()
                if (isCurrent()) {
                    val previous = ready.page.video.rating
                    likeCountDelta += (if (video.rating == VideoRating.Like) 1L else 0L) - (if (previous == VideoRating.Like) 1L else 0L)
                    dislikeCountDelta += (if (video.rating == VideoRating.Dislike) 1L else 0L) - (if (previous == VideoRating.Dislike) 1L else 0L)
                    _state.update { state ->
                        val content = state.content as? WatchContent.Ready ?: return@update state
                        state.copy(content = content.copy(page = content.page.copy(video = content.page.video.copy(
                            rating = video.rating, subscribed = video.subscribed))))
                    }
                    updateCounts()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { if (isCurrent()) notices.trySend(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { if (isCurrent()) notices.trySend(report(operation, error, id, message = UNCONFIRMED_SAVE)) }
            catch (error: Exception) { if (isCurrent()) notices.trySend(report(operation, error, id, message = "Couldn't update this video. Try again.")) }
            finally { if (isCurrent()) _state.update { it.copy(actionPending = false, pendingVideoAction = null) } }
        }
    }

    private fun editSearch(open: Boolean, query: String, reopen: Boolean = true) {
        savedState["draftQuery"] = query
        // A stale IME commit cannot reopen a submitted search.
        overlay(if (open && reopen) WatchOverlay.Search else WatchOverlay.None)
        _state.update { it.copy(draftQuery = query) }
    }

    private fun overlay(next: WatchOverlay) {
        val previousSave = _state.value.overlay as? WatchOverlay.Save
        val nextSave = next as? WatchOverlay.Save
        if (previousSave?.videoId != nextSave?.videoId) {
            sheetEpoch++
            sheetJob?.cancel()
            sheetJob = null
        }
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
                    val merged = loaded.page.related.associateByTo(linkedMapOf()) { it.id }
                    page.items.forEach { item ->
                        val known = merged[item.id]
                        merged[item.id] = if (known == null) item else known.copy(
                            title = known.title.ifBlank { item.title },
                            channel = known.channel.ifBlank { item.channel },
                            channelId = known.channelId.ifBlank { item.channelId },
                            views = known.views.ifBlank { item.views },
                            age = known.age.ifBlank { item.age },
                            duration = known.duration.ifBlank { item.duration },
                            artwork = known.artwork.ifBlank { item.artwork },
                            avatar = known.avatar?.takeIf { it.isNotBlank() } ?: item.avatar,
                        )
                    }
                    state.copy(content = loaded.copy(page = loaded.page.copy(
                        related = merged.values.toList(),
                        nextKey = page.nextKey?.takeUnless { it in consumedCursors })),
                        relatedLoading = false, relatedFailed = false)
                }
                fillMissingAvatars()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation) _state.update { it.copy(relatedLoading = false, relatedFailed = true) }
            }
        }
    }

    /** Optional avatar IO never holds up the page or its continuations. */
    private fun fillMissingAvatars() {
        val directory = avatars ?: return
        avatarJob?.cancel()
        val requestGeneration = generation
        val requestSession = sessionGeneration
        avatarJob = viewModelScope.launch {
            repeat(3) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(if (attempt == 1) 5_100 else 10_100)
                if (requestGeneration != generation || requestSession != sessionGeneration) return@launch
                val page = (_state.value.content as? WatchContent.Ready)?.page ?: return@launch
                val ids = buildSet {
                    if (page.video.avatar.isNullOrBlank()) add(page.video.channelId)
                    page.related.filter { it.avatar.isNullOrBlank() }.forEach { add(it.channelId) }
                }.filter { it.startsWith("UC") }
                if (ids.isEmpty()) return@launch
                directory.resolveAll(ids) { channelId, avatar ->
                    _state.update { state ->
                        if (requestGeneration != generation || requestSession != sessionGeneration) return@update state
                        val ready = state.content as? WatchContent.Ready ?: return@update state
                        val video = ready.page.video
                        state.copy(content = ready.copy(page = ready.page.copy(
                            video = if (video.channelId == channelId && video.avatar.isNullOrBlank()) video.copy(avatar = avatar) else video,
                            related = ready.page.related.map { item ->
                                if (item.channelId == channelId && item.avatar.isNullOrBlank()) item.copy(avatar = avatar) else item
                            },
                        )))
                    }
                }
            }
        }
    }

    private fun load(fresh: Boolean = false) {
        if (!loadsEnabled) return
        val videoId = uiState.value.videoId
        val previous = (uiState.value.content as? WatchContent.Ready)?.page?.video
            ?.takeIf { it.id == videoId }
        loadJob?.cancel()
        menuJob?.cancel()
        menuRevision++
        relatedJob?.cancel()
        avatarJob?.cancel()
        consumedCursors.clear()
        val requestGeneration = ++generation
        _state.update { it.copy(content = WatchContent.Loading, verification = null, relatedLoading = false, relatedFailed = false,
            menuLoading = false, menuFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val page = if (fresh) repository.watchFresh(videoId) else repository.watch(videoId)
                if (requestGeneration != generation) return@launch
                _state.update { state ->
                    val known = state.provisional?.takeIf { it.videoId == page.video.id }
                    val video = page.video
                    // Thin responses must not erase fields the tapped card or a
                    // previous read already resolved. Viewer state and endpoints
                    // always come from the new response.
                    val filled = video.copy(
                        title = video.title.ifBlank { previous?.title.orEmpty().ifBlank { known?.title.orEmpty() } },
                        channel = video.channel.ifBlank { previous?.channel.orEmpty().ifBlank { known?.channel.orEmpty() } },
                        handle = video.handle.ifBlank { previous?.handle.orEmpty() },
                        channelId = video.channelId.ifBlank { previous?.channelId.orEmpty().ifBlank { known?.channelId.orEmpty() } },
                        artwork = video.artwork.ifBlank { previous?.artwork.orEmpty().ifBlank { known?.artwork.orEmpty() } },
                        views = video.views.ifBlank { previous?.views.orEmpty().ifBlank { known?.views.orEmpty() } },
                        age = video.age.ifBlank { previous?.age.orEmpty().ifBlank { known?.age.orEmpty() } },
                        avatar = video.avatar?.takeIf { it.isNotBlank() }
                            ?: previous?.avatar?.takeIf { it.isNotBlank() } ?: known?.avatar?.takeIf { it.isNotBlank() },
                    )
                    state.copy(content = WatchContent.Ready(page.copy(video = filled)))
                }
                fillMissingAvatars()
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
