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

class WatchViewModel(
    private val repository: WatchRepository,
    private val savedState: SavedStateHandle,
    videoId: String,
    scenario: WatchScenario = WatchScenario.Content,
    private val voteRepository: VoteCountRepository? = null,
    private val videoActions: VideoActions? = null,
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
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<WatchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<WatchNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var actionJob: Job? = null
    private var loadJob: Job? = null
    private var generation = 0
    private var voteJob: Job? = null
    private var voteFlags: Pair<Boolean, Boolean>? = null
    private var counts: VoteCounts? = null

    fun selectVideo(id: String) {
        if (id == uiState.value.videoId) return
        voteJob?.cancel()
        actionJob?.cancel()
        counts = null
        val flags = voteFlags
        voteFlags = null
        savedState["videoId"] = id
        savedState["commentsOpen"] = false
        savedState["descriptionExpanded"] = false
        _state.value = WatchUiState(videoId = id)
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
            try {
                counts = voteRepository.counts(uiState.value.videoId)
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
    }

    fun onAction(action: WatchAction) {
        val videoId = uiState.value.videoId
        when (action) {
            WatchAction.Retry -> load()
            WatchAction.VoiceSearch -> navigation.trySend(WatchNavigationRequest.VoiceSearch)
            WatchAction.ToggleDescription -> toggleDescription()
            WatchAction.OpenSearch -> editSearch(true, uiState.value.draftQuery)
            WatchAction.CloseSearch -> editSearch(false, uiState.value.draftQuery)
            is WatchAction.QueryChanged -> editSearch(true, action.query)
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
            WatchAction.Save -> overlay(WatchOverlay.Save(videoId))
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
                if (title.isEmpty()) return
                overlay(WatchOverlay.None)
                navigation.trySend(WatchNavigationRequest.CreatePlaylist(save.videoId, title, save.description.trim()))
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
                else performVideoAction { current ->
                    val subscribed = !current.subscribed
                    videoActions.subscribe(channel, subscribed)
                    current.copy(subscribed = subscribed)
                }
            }
            is WatchAction.OpenChannel -> navigation.trySend(WatchNavigationRequest.Channel(action.channelId))
            is WatchAction.OpenComments -> {
                overlay(WatchOverlay.None)
                editComments(true)
                navigation.trySend(WatchNavigationRequest.Comments(action.videoId))
            }
            WatchAction.CloseComments -> editComments(false)
            is WatchAction.OpenRelated -> navigation.trySend(WatchNavigationRequest.Watch(action.videoId))
            is WatchAction.OpenItemMenu -> overlay(WatchOverlay.ItemMenu(action.videoId))
            WatchAction.CloseOverlay -> overlay(WatchOverlay.None)
            is WatchAction.MenuAction -> {
                val menu = uiState.value.overlay as? WatchOverlay.ItemMenu ?: return
                when (action.action) {
                    WatchVideoMenuAction.WatchLater -> {
                        overlay(WatchOverlay.None)
                        navigation.trySend(WatchNavigationRequest.SaveToWatchLater(menu.videoId))
                    }
                    WatchVideoMenuAction.SaveToPlaylist -> overlay(WatchOverlay.Save(menu.videoId))
                    WatchVideoMenuAction.Share -> {
                        overlay(WatchOverlay.None)
                        navigation.trySend(WatchNavigationRequest.Share(menu.videoId))
                    }
                    WatchVideoMenuAction.NotInterested -> overlay(WatchOverlay.Feedback(menu.videoId, WatchFeedbackKind.NotInterested))
                    WatchVideoMenuAction.DontRecommend -> overlay(WatchOverlay.Feedback(menu.videoId, WatchFeedbackKind.DontRecommend))
                }
            }
        }
    }

    private fun rate(selection: VideoRating) = performVideoAction { video ->
        val next = if (video.rating == selection) VideoRating.None else selection
        videoActions!!.rate(video.id, next, video.rating)
        video.copy(rating = next)
    }

    private fun performVideoAction(action: suspend (WatchVideo) -> WatchVideo) {
        if (actionJob?.isActive == true) return
        val ready = _state.value.content as? WatchContent.Ready ?: return
        val id = _state.value.videoId
        _state.update { it.copy(actionPending = true) }
        actionJob = viewModelScope.launch {
            try {
                val video = action(ready.page.video)
                if (_state.value.videoId == id) _state.update { state ->
                    val content = state.content as? WatchContent.Ready ?: return@update state
                    state.copy(content = content.copy(page = content.page.copy(video = video)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { notices.trySend(WatchNotice.SignIn) }
            catch (_: UnconfirmedActionException) { notices.trySend(WatchNotice.Message("The server did not confirm this change. Please refresh before trying again.")) }
            catch (_: Exception) { notices.trySend(WatchNotice.Message("Couldn't update this video. Try again.")) }
            finally { if (_state.value.videoId == id) _state.update { it.copy(actionPending = false) } }
        }
    }

    private fun editSearch(open: Boolean, query: String) {
        savedState["draftQuery"] = query
        _state.update { it.copy(overlay = if (open) WatchOverlay.Search else WatchOverlay.None, draftQuery = query) }
        persistOverlay(uiState.value.overlay)
    }

    private fun overlay(next: WatchOverlay) {
        _state.update { it.copy(overlay = next) }
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

    private fun load() {
        val videoId = uiState.value.videoId
        loadJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = WatchContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.watch(videoId)
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = WatchContent.Ready(page)) }
                updateCounts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = WatchContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
