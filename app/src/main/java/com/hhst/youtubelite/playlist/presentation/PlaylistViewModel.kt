package com.hhst.youtubelite.playlist.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.playlist.data.PlaylistScenario
import com.hhst.youtubelite.playlist.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val repository: PlaylistRepository,
    private val savedState: SavedStateHandle,
    playlistId: String,
    scenario: PlaylistScenario = PlaylistScenario.Content,
) : ViewModel() {
    private val id = savedState.get<String>("playlistId") ?: playlistId
    private val state = MutableStateFlow(PlaylistUiState(id,
        content = when (scenario) {
            PlaylistScenario.Error -> PlaylistContent.Failed
            PlaylistScenario.Unavailable -> PlaylistContent.Unavailable
            else -> PlaylistContent.Loading
        },
        overlay = when (savedState.get<String>("overlay")) {
            "menu" -> PlaylistOverlay.Menu
            "search" -> PlaylistOverlay.Search
            "video" -> savedState.get<String>("entryId")?.let(PlaylistOverlay::VideoMenu) ?: PlaylistOverlay.None
            else -> PlaylistOverlay.None
        }, query = savedState["query"] ?: "",
        descriptionExpanded = savedState["descriptionExpanded"] ?: false,
    ))
    val uiState = state.asStateFlow()
    private val navigation = Channel<PlaylistNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    init {
        savedState["playlistId"] = id
        if (scenario == PlaylistScenario.Content || scenario == PlaylistScenario.Empty) load()
    }

    fun onAction(action: PlaylistAction) {
        when (action) {
            PlaylistAction.Retry -> load()
            PlaylistAction.OpenSearch -> overlay(PlaylistOverlay.Search)
            PlaylistAction.OpenMenu -> overlay(PlaylistOverlay.Menu)
            PlaylistAction.CloseOverlay -> overlay(PlaylistOverlay.None)
            is PlaylistAction.QueryChanged -> {
                savedState["query"] = action.query
                state.update { it.copy(query = action.query) }
            }
            is PlaylistAction.Search -> action.query.trim().takeIf { it.isNotEmpty() }?.let {
                onAction(PlaylistAction.QueryChanged(it))
                overlay(PlaylistOverlay.None)
                send(PlaylistNavigationRequest.Search(it))
            }
            is PlaylistAction.Destination -> send(PlaylistNavigationRequest.Destination(action.destination))
            PlaylistAction.VoiceSearch -> send(PlaylistNavigationRequest.VoiceSearch)
            else -> {
                val page = (state.value.content as? PlaylistContent.Ready)?.page ?: return
                when (action) {
                    PlaylistAction.PlayAll, PlaylistAction.Shuffle -> {
                        overlay(PlaylistOverlay.None)
                        if (page.entries.isNotEmpty()) send(PlaylistNavigationRequest.Queue(id, page.entries.map { it.videoId }, action == PlaylistAction.Shuffle))
                    }
                    PlaylistAction.Download -> {
                        overlay(PlaylistOverlay.None)
                        send(PlaylistNavigationRequest.Download(id))
                    }
                    PlaylistAction.Save -> send(PlaylistNavigationRequest.Save(id))
                    PlaylistAction.Share -> send(PlaylistNavigationRequest.Share(id))
                    PlaylistAction.OpenOwner -> send(PlaylistNavigationRequest.Channel(page.summary.channelId))
                    PlaylistAction.ToggleDescription -> {
                        val expanded = !state.value.descriptionExpanded
                        savedState["descriptionExpanded"] = expanded
                        state.update { it.copy(descriptionExpanded = expanded) }
                    }
                    is PlaylistAction.OpenVideo -> page.entries.find { it.id == action.entryId }?.let { send(PlaylistNavigationRequest.Watch(it.videoId)) }
                    is PlaylistAction.OpenVideoMenu -> if (page.entries.any { it.id == action.entryId }) overlay(PlaylistOverlay.VideoMenu(action.entryId))
                    is PlaylistAction.VideoMenuAction -> {
                        val entryId = (state.value.overlay as? PlaylistOverlay.VideoMenu)?.entryId ?: return
                        val entry = page.entries.find { it.id == entryId } ?: return
                        overlay(PlaylistOverlay.None)
                        send(PlaylistNavigationRequest.VideoAction(id, entryId, entry.videoId, action.action))
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun send(request: PlaylistNavigationRequest) { navigation.trySend(request) }

    private fun overlay(next: PlaylistOverlay) {
        savedState["overlay"] = when (next) {
            PlaylistOverlay.None -> "none"
            PlaylistOverlay.Menu -> "menu"
            PlaylistOverlay.Search -> "search"
            is PlaylistOverlay.VideoMenu -> "video"
        }
        savedState["entryId"] = (next as? PlaylistOverlay.VideoMenu)?.entryId
        state.update { it.copy(overlay = next) }
    }

    private fun load() {
        loadJob?.cancel()
        val current = ++generation
        state.update { it.copy(content = PlaylistContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.playlist(id)
                if (current != generation) return@launch
                state.update { it.copy(content = page?.let(PlaylistContent::Ready) ?: PlaylistContent.Unavailable) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current == generation) state.update { it.copy(content = PlaylistContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
