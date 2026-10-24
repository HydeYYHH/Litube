package com.hhst.youtubelite.channel.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostMenuAction
import com.hhst.youtubelite.post.domain.PostSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hhst.youtubelite.core.AppNotice

class ChannelViewModel(
    private val repository: ChannelRepository,
    private val savedState: SavedStateHandle,
    channelId: String,
    scenario: ChannelScenario = ChannelScenario.Content,
    private val videoActions: com.hhst.youtubelite.watch.domain.VideoActions? = null,
) : ViewModel() {
    private val restoredId = savedState.get<String>("channelId") ?: channelId
    private val _state = MutableStateFlow(
        ChannelUiState(
            channelId = restoredId,
            content = scenario.initialContent(restoredId),
            selectedTab = savedState.get<String>("tab")?.let { name ->
                ChannelTab.entries.firstOrNull { it.name == name }
            } ?: ChannelTab.Home,
            overlay = restoredOverlay(),
            searchQuery = savedState["channelQuery"] ?: "",
            submittedQuery = savedState["submittedQuery"] ?: "",
            playlistSort = ChannelPlaylistSort.entries.firstOrNull {
                it.name == savedState.get<String>("playlistSort")
            } ?: ChannelPlaylistSort.DateAdded,
            shorts = scenario.initialSection(),
            live = scenario.initialSection(),
            posts = scenario.initialSection(),
            shortsSort = ChannelSortOption.entries.firstOrNull {
                it.name == savedState.get<String>("shortsSort")
            } ?: ChannelSortOption.Latest,
            liveSort = ChannelSortOption.entries.firstOrNull {
                it.name == savedState.get<String>("liveSort")
            } ?: ChannelSortOption.Latest,
            expandedPosts = savedState.get<ArrayList<String>>("expandedPosts")?.toSet() ?: emptySet(),
            production = videoActions != null,
        )
    )
    val uiState = _state.asStateFlow()
    private val navigation = Channel<ChannelNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var loadJob: Job? = null
    private var subscribeJob: Job? = null
    private var searchJob: Job? = null
    private var appendJob: Job? = null
    private var generation = 0
    private val sectionJobs = mutableMapOf<ChannelTab, Job>()
    private val sectionGenerations = mutableMapOf<ChannelTab, Int>()

    fun refreshSession() {
        subscribeJob?.cancel()
        searchJob?.cancel()
        appendJob?.cancel()
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        sectionGenerations.keys.toList().forEach { sectionGenerations[it] = sectionGenerations.getValue(it) + 1 }
        _state.update { it.copy(subscribePending = false, shorts = ChannelSection.Loading,
            live = ChannelSection.Loading, posts = ChannelSection.Loading,
            videosAppend = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        setOverlay(ChannelOverlay.None)
        load()
        if (uiState.value.submittedQuery.isNotBlank()) runSearch(uiState.value.submittedQuery)
    }

    init {
        savedState["channelId"] = restoredId
        if (scenario.loadsContent() && _state.value.content is ChannelContent.Loading) load()
        if (scenario.loadsContent() && repository.serverSearch && uiState.value.submittedQuery.isNotBlank())
            runSearch(uiState.value.submittedQuery)
    }

    fun onAction(action: ChannelAction) {
        when (action) {
            ChannelAction.Retry -> load()
            is ChannelAction.SelectDestination -> navigation.trySend(ChannelNavigationRequest.Destination(action.destination))
            is ChannelAction.SelectTab -> selectTab(action.tab)
            is ChannelAction.OpenItemMenu -> navigation.trySend(ChannelNavigationRequest.ItemMenu(action.videoId))
            is ChannelAction.OpenVideo -> navigation.trySend(ChannelNavigationRequest.Watch(action.videoId))
            is ChannelAction.OpenPlaylist -> navigation.trySend(ChannelNavigationRequest.Playlist(action.playlistId))
            is ChannelAction.OpenPlaylistMenu -> setOverlay(ChannelOverlay.PlaylistMenu(action.playlistId))
            ChannelAction.CloseOverlay -> setOverlay(ChannelOverlay.None)
            ChannelAction.SharePlaylist -> {
                val menu = uiState.value.overlay as? ChannelOverlay.PlaylistMenu ?: return
                setOverlay(ChannelOverlay.None)
                navigation.trySend(ChannelNavigationRequest.SharePlaylist(menu.playlistId))
            }
            is ChannelAction.SortPlaylists -> {
                savedState["playlistSort"] = action.sort.name
                _state.update { it.copy(playlistSort = action.sort) }
            }
            ChannelAction.Subscribe -> {
                val actions = videoActions
                if (actions == null) navigation.trySend(ChannelNavigationRequest.Subscribe(uiState.value.channelId))
                else if (subscribeJob?.isActive != true) {
                    val ready = uiState.value.content as? ChannelContent.Ready ?: return
                    val target = ready.page.profile.subscribed != true
                    _state.update { it.copy(subscribePending = true) }
                    subscribeJob = viewModelScope.launch {
                        try {
                            actions.subscribe(uiState.value.channelId, target)
                            _state.update { state ->
                                val current = state.content as? ChannelContent.Ready ?: return@update state
                                state.copy(content = current.copy(page = current.page.copy(
                                    profile = current.page.profile.copy(subscribed = target))), subscribePending = false)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                            _state.update { it.copy(subscribePending = false) }
                            notices.trySend(AppNotice.SignIn)
                        }
                        catch (_: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                            _state.update { it.copy(subscribePending = false) }
                            notices.trySend(AppNotice.Message("The server did not confirm this change. Please refresh before trying again."))
                        }
                        catch (_: Exception) {
                            _state.update { it.copy(subscribePending = false) }
                            notices.trySend(AppNotice.Message("Couldn't update this subscription. Try again."))
                        }
                    }
                }
            }
            ChannelAction.Share -> navigation.trySend(ChannelNavigationRequest.Share)
            ChannelAction.ChannelSearch -> {
                selectTab(ChannelTab.Search)
                navigation.trySend(ChannelNavigationRequest.ChannelSearch)
            }
            is ChannelAction.QueryChanged -> {
                savedState["channelQuery"] = action.query
                _state.update { it.copy(searchQuery = action.query) }
            }
            ChannelAction.SubmitSearch -> {
                val query = uiState.value.searchQuery.trim()
                savedState["submittedQuery"] = query
                _state.update { it.copy(submittedQuery = query) }
                if (repository.serverSearch && query.isNotEmpty()) runSearch(query)
                else _state.update { it.copy(searchResults = null) }
            }
            ChannelAction.RetrySearch -> {
                val query = uiState.value.submittedQuery
                if (repository.serverSearch && query.isNotEmpty()) runSearch(query)
            }
            ChannelAction.LoadVideosNext -> loadVideosNext()
            is ChannelAction.OpenLink -> navigation.trySend(ChannelNavigationRequest.OpenLink(action.url))
            ChannelAction.OpenFullDescription -> {
                setOverlay(ChannelOverlay.About)
                navigation.trySend(ChannelNavigationRequest.FullDescription)
            }
            ChannelAction.OpenSectionMenu -> navigation.trySend(ChannelNavigationRequest.SectionMenu)

            is ChannelAction.SortShorts -> {
                savedState["shortsSort"] = action.option.name
                _state.update { it.copy(shortsSort = action.option) }
            }
            is ChannelAction.SortLive -> {
                savedState["liveSort"] = action.option.name
                _state.update { it.copy(liveSort = action.option) }
            }
            is ChannelAction.RetrySection -> loadSection(action.tab)
            is ChannelAction.OpenShort -> navigation.trySend(
                ChannelNavigationRequest.Short(uiState.value.channelId, action.shortId)
            )
            is ChannelAction.OpenShortMenu -> setOverlay(ChannelOverlay.ShortMenu(action.shortId))
            is ChannelAction.SelectShortMenu -> {
                val menu = uiState.value.overlay as? ChannelOverlay.ShortMenu ?: return
                setOverlay(ChannelOverlay.None)
                navigation.trySend(
                    ChannelNavigationRequest.ShortMenu(uiState.value.channelId, menu.shortId, action.action)
                )
            }
            is ChannelAction.OpenLive -> {
                val page = (uiState.value.live as? ChannelSection.Ready)?.value ?: return
                val video = page.all.firstOrNull { it.id == action.videoId } ?: return
                if (video.liveNow) {
                    navigation.trySend(ChannelNavigationRequest.LiveStream(uiState.value.channelId, video.id))
                } else {
                    navigation.trySend(ChannelNavigationRequest.Watch(video.id))
                }
            }
            is ChannelAction.OpenPost -> navigation.trySend(ChannelNavigationRequest.Post(action.identity))
            is ChannelAction.TogglePostExpanded -> {
                val next = _state.value.expandedPosts.toMutableSet()
                if (!next.add(action.identity.postId)) next.remove(action.identity.postId)
                savedState["expandedPosts"] = ArrayList(next)
                _state.update { it.copy(expandedPosts = next) }
            }
            is ChannelAction.OpenPostMenu -> setOverlay(ChannelOverlay.PostMenu(action.identity))
            is ChannelAction.SelectPostMenu -> {
                val menu = uiState.value.overlay as? ChannelOverlay.PostMenu ?: return
                setOverlay(ChannelOverlay.None)
                navigation.trySend(ChannelNavigationRequest.PostMenu(menu.identity, action.action))
            }
            is ChannelAction.PostFeedback -> navigation.trySend(
                ChannelNavigationRequest.PostFeedback(action.identity, action.liked)
            )
            is ChannelAction.PostComments -> navigation.trySend(
                ChannelNavigationRequest.PostComments(action.identity)
            )
        }
    }

    private fun selectTab(tab: ChannelTab) {
        if (tab != _state.value.selectedTab) {
            savedState["tab"] = tab.name
            _state.update { it.copy(selectedTab = tab) }
        }
        when (tab) {
            ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts -> ensureSectionLoaded(tab)
            else -> Unit
        }
    }

    private fun ensureSectionLoaded(tab: ChannelTab) {
        val section = when (tab) {
            ChannelTab.Shorts -> _state.value.shorts
            ChannelTab.Live -> _state.value.live
            ChannelTab.Posts -> _state.value.posts
            else -> return
        }
        if (section is ChannelSection.Loading && sectionJobs[tab]?.isActive != true) loadSection(tab)
    }

    private fun loadSection(tab: ChannelTab) {
        if (tab !in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts)) return
        sectionJobs[tab]?.cancel()
        val current = (sectionGenerations[tab] ?: 0) + 1
        sectionGenerations[tab] = current
        setSectionLoading(tab)
        sectionJobs[tab] = viewModelScope.launch {
            try {
                when (tab) {
                    ChannelTab.Shorts -> {
                        val shorts = repository.shorts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(shorts = if (shorts.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(shorts))
                        }
                    }
                    ChannelTab.Live -> {
                        val live = repository.live(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(live = if (live.all.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(live))
                        }
                    }
                    ChannelTab.Posts -> {
                        val posts = repository.posts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(posts = if (posts.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(posts))
                        }
                    }
                    else -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current == sectionGenerations[tab]) {
                    _state.update {
                        when (tab) {
                            ChannelTab.Shorts -> it.copy(shorts = ChannelSection.Failed)
                            ChannelTab.Live -> it.copy(live = ChannelSection.Failed)
                            else -> it.copy(posts = ChannelSection.Failed)
                        }
                    }
                }
            }
        }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        _state.update { it.copy(searchResults = ChannelSection.Loading) }
        searchJob = viewModelScope.launch {
            try {
                val results = repository.searchVideos(uiState.value.channelId, query)
                if (query != uiState.value.submittedQuery) return@launch
                _state.update {
                    it.copy(searchResults = if (results.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(results))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (query == uiState.value.submittedQuery) _state.update { it.copy(searchResults = ChannelSection.Failed) }
            }
        }
    }

    private fun loadVideosNext() {
        val ready = uiState.value.content as? ChannelContent.Ready ?: return
        val next = ready.page.videosNextKey ?: return
        if (appendJob?.isActive == true) return
        _state.update { it.copy(videosAppend = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.videos(uiState.value.channelId, next)
                _state.update { state ->
                    val current = state.content as? ChannelContent.Ready ?: return@update state
                    if (current.page.videosNextKey != next) return@update state
                    state.copy(
                        content = current.copy(page = current.page.copy(
                            videos = (current.page.videos + page.videos).distinctBy { it.id },
                            videosNextKey = page.nextKey,
                        )),
                        videosAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { it.copy(videosAppend = com.hhst.youtubelite.home.domain.AppendState.Failed) }
            }
        }
    }

    private fun setSectionLoading(tab: ChannelTab) {
        _state.update {
            when (tab) {
                ChannelTab.Shorts -> it.copy(shorts = ChannelSection.Loading)
                ChannelTab.Live -> it.copy(live = ChannelSection.Loading)
                else -> it.copy(posts = ChannelSection.Loading)
            }
        }
    }

    private fun setOverlay(overlay: ChannelOverlay) {
        savedState.remove<String>("shortMenu")
        savedState.remove<String>("postMenu")
        savedState.remove<String>("playlistMenu")
        savedState["about"] = overlay == ChannelOverlay.About
        when (overlay) {
            is ChannelOverlay.ShortMenu -> savedState["shortMenu"] = overlay.shortId
            is ChannelOverlay.PostMenu -> savedState["postMenu"] = overlay.identity.postId
            is ChannelOverlay.PlaylistMenu -> savedState["playlistMenu"] = overlay.playlistId
            else -> Unit
        }
        _state.update { it.copy(overlay = overlay) }
    }

    private fun restoredOverlay(): ChannelOverlay = when {
        savedState.get<Boolean>("about") == true -> ChannelOverlay.About
        savedState.get<String>("playlistMenu") != null ->
            ChannelOverlay.PlaylistMenu(savedState.get<String>("playlistMenu")!!)
        savedState.get<String>("shortMenu") != null ->
            ChannelOverlay.ShortMenu(savedState.get<String>("shortMenu")!!)
        savedState.get<String>("postMenu") != null ->
            ChannelOverlay.PostMenu(PostIdentity(restoredId, savedState.get<String>("postMenu")!!))
        else -> ChannelOverlay.None
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
                ensureSectionLoaded(_state.value.selectedTab)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = ChannelContent.Failed) }
            }
        }
    }

    override fun onCleared() {
        navigation.close()
    }
}

private fun ChannelScenario.initialSection(): ChannelSection<Nothing> = when (this) {
    ChannelScenario.Content, ChannelScenario.Loading -> ChannelSection.Loading
    ChannelScenario.Empty -> ChannelSection.Empty
    ChannelScenario.Error -> ChannelSection.Failed
}
