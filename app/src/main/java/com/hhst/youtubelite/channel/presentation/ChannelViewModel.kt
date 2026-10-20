package com.hhst.youtubelite.channel.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChannelViewModel(
    private val repository: ChannelRepository,
    private val savedState: SavedStateHandle,
    channelId: String,
    scenario: ChannelScenario = ChannelScenario.Content,
) : ViewModel() {
    private val restoredId = savedState.get<String>("channelId") ?: channelId
    private val _state = MutableStateFlow(
        ChannelUiState(
            channelId = restoredId,
            content = scenario.initialContent(restoredId),
            selectedTab = savedState.get<String>("tab")?.let { name -> listOf(ChannelTab.Home, ChannelTab.Videos, ChannelTab.Playlists).firstOrNull { it.name == name } } ?: ChannelTab.Home,
            overlay = savedState.get<String>("playlistMenu")?.let(ChannelOverlay::PlaylistMenu) ?: ChannelOverlay.None,
            playlistSort = ChannelPlaylistSort.entries.firstOrNull { it.name == savedState.get<String>("playlistSort") } ?: ChannelPlaylistSort.DateAdded,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<ChannelNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var generation = 0

    init {
        savedState["channelId"] = restoredId
        if (scenario.loadsContent() && _state.value.content is ChannelContent.Loading) load()
    }

    fun onAction(action: ChannelAction) {
        when (action) {
            ChannelAction.Retry -> load()
            is ChannelAction.SelectDestination -> navigation.trySend(ChannelNavigationRequest.Destination(action.destination))
            is ChannelAction.SelectTab -> selectTab(action.tab)
            is ChannelAction.OpenItemMenu -> navigation.trySend(ChannelNavigationRequest.ItemMenu(action.videoId))
            is ChannelAction.OpenVideo -> navigation.trySend(ChannelNavigationRequest.Watch(action.videoId))
            is ChannelAction.OpenPlaylist -> navigation.trySend(ChannelNavigationRequest.Playlist(action.playlistId))
            is ChannelAction.OpenPlaylistMenu -> {
                savedState["playlistMenu"] = action.playlistId
                _state.update { it.copy(overlay = ChannelOverlay.PlaylistMenu(action.playlistId)) }
            }
            ChannelAction.CloseOverlay -> {
                savedState.remove<String>("playlistMenu")
                _state.update { it.copy(overlay = ChannelOverlay.None) }
            }
            ChannelAction.SharePlaylist -> {
                val menu = uiState.value.overlay as? ChannelOverlay.PlaylistMenu ?: return
                onAction(ChannelAction.CloseOverlay)
                navigation.trySend(ChannelNavigationRequest.SharePlaylist(menu.playlistId))
            }
            is ChannelAction.SortPlaylists -> {
                savedState["playlistSort"] = action.sort.name
                _state.update { it.copy(playlistSort = action.sort) }
            }
            ChannelAction.Subscribe -> navigation.trySend(ChannelNavigationRequest.Subscribe(uiState.value.channelId))
            ChannelAction.Share -> navigation.trySend(ChannelNavigationRequest.Share)
            ChannelAction.ChannelSearch -> navigation.trySend(ChannelNavigationRequest.ChannelSearch)
            ChannelAction.OpenFullDescription -> navigation.trySend(ChannelNavigationRequest.FullDescription)
            ChannelAction.OpenSectionMenu -> navigation.trySend(ChannelNavigationRequest.SectionMenu)
        }
    }

    private fun selectTab(tab: ChannelTab) {
        if (tab == _state.value.selectedTab) return
        if (tab !in listOf(ChannelTab.Home, ChannelTab.Videos, ChannelTab.Playlists)) {
            navigation.trySend(ChannelNavigationRequest.OtherTab(tab))
            return
        }
        savedState["tab"] = tab.name
        _state.update { it.copy(selectedTab = tab) }
    }

    private fun load() {
        loadJob?.cancel()
        val requestGeneration = ++generation
        _state.update { it.copy(content = ChannelContent.Loading) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.channel(uiState.value.channelId)
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = ChannelContent.Ready(page)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = ChannelContent.Failed) }
            }
        }
    }

    override fun onCleared() { navigation.close() }
}
