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
) : ViewModel() {
    private val restoredId = savedState.get<String>("videoId") ?: videoId
    private val _state = MutableStateFlow(
        WatchUiState(
            videoId = restoredId,
            content = scenario.initialContent(),
            descriptionExpanded = savedState["descriptionExpanded"] ?: false,
            searchOpen = savedState["searchOpen"] ?: false,
            draftQuery = savedState["draftQuery"] ?: "",
            commentsOpen = savedState["commentsOpen"] ?: false,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<WatchNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    init {
        savedState["videoId"] = _state.value.videoId
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
            WatchAction.Like -> navigation.trySend(WatchNavigationRequest.Like(videoId))
            WatchAction.Dislike -> navigation.trySend(WatchNavigationRequest.Dislike(videoId))
            WatchAction.Download -> navigation.trySend(WatchNavigationRequest.Download(videoId))
            WatchAction.Queue -> navigation.trySend(WatchNavigationRequest.Queue(videoId))
            WatchAction.OpenWith -> navigation.trySend(WatchNavigationRequest.OpenWith(videoId))
            WatchAction.Share -> navigation.trySend(WatchNavigationRequest.Share(videoId))
            WatchAction.Save -> navigation.trySend(WatchNavigationRequest.Save(videoId))
            WatchAction.Report -> navigation.trySend(WatchNavigationRequest.Report(videoId))
            WatchAction.Subscribe -> {
                val channel = (uiState.value.content as? WatchContent.Ready)?.page?.video?.channelId ?: return
                navigation.trySend(WatchNavigationRequest.Subscribe(channel))
            }
            is WatchAction.OpenChannel -> navigation.trySend(WatchNavigationRequest.Channel(action.channelId))
            is WatchAction.OpenComments -> {
                editComments(true)
                navigation.trySend(WatchNavigationRequest.Comments(action.videoId))
            }
            WatchAction.CloseComments -> editComments(false)
            is WatchAction.OpenRelated -> navigation.trySend(WatchNavigationRequest.Watch(action.videoId))
            is WatchAction.OpenItemMenu -> navigation.trySend(WatchNavigationRequest.ItemMenu(action.videoId))
        }
    }

    private fun editSearch(open: Boolean, query: String) {
        savedState["searchOpen"] = open
        savedState["draftQuery"] = query
        _state.update { it.copy(searchOpen = open, draftQuery = query) }
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
