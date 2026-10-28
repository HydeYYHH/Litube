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
    private var searchNextJob: Job? = null
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
            videosTab = ChannelSection.Loading, playlists = ChannelSection.Loading,
            shortsNextKey = null, liveNextKey = null, postsNextKey = null, searchNextKey = null,
            playlistsNextKey = null,
            shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            liveAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            postsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            searchAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            playlistsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
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
            ChannelAction.LoadShortsNext -> loadSectionNext(ChannelTab.Shorts)
            ChannelAction.LoadLiveNext -> loadSectionNext(ChannelTab.Live)
            ChannelAction.LoadPostsNext -> loadSectionNext(ChannelTab.Posts)
            ChannelAction.LoadPlaylistsNext -> loadSectionNext(ChannelTab.Playlists)
            ChannelAction.LoadSearchNext -> loadSearchNext()
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
            ChannelAction.DismissVerification -> _state.update { it.copy(verification = null) }
            is ChannelAction.OpenVerification -> navigation.trySend(ChannelNavigationRequest.OpenVerification(action.challengeUrl))
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
            ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts,
            ChannelTab.Videos, ChannelTab.Playlists -> ensureSectionLoaded(tab)
            else -> Unit
        }
    }

    private fun ensureSectionLoaded(tab: ChannelTab) {
        val page = (_state.value.content as? ChannelContent.Ready)?.page
        val section = when (tab) {
            ChannelTab.Shorts -> _state.value.shorts
            ChannelTab.Live -> _state.value.live
            ChannelTab.Posts -> _state.value.posts
            // An inline videos tab is already populated; only a deferred one reads on demand.
            ChannelTab.Videos -> if (page?.videosDeferred == true) _state.value.videosTab else return
            ChannelTab.Playlists -> _state.value.playlists
            else -> return
        }
        if (section is ChannelSection.Loading && sectionJobs[tab]?.isActive != true) loadSection(tab)
    }

    private fun loadSection(tab: ChannelTab) {
        if (tab !in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts, ChannelTab.Videos, ChannelTab.Playlists)) return
        sectionJobs[tab]?.cancel()
        val current = (sectionGenerations[tab] ?: 0) + 1
        sectionGenerations[tab] = current
        setSectionLoading(tab)
        sectionJobs[tab] = viewModelScope.launch {
            try {
                when (tab) {
                    ChannelTab.Shorts -> {
                        val page = repository.shorts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(shorts = if (page.shorts.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(page.shorts),
                                shortsNextKey = page.nextKey, shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Live -> {
                        val live = repository.live(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(live = if (live.all.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(live),
                                liveNextKey = live.nextKey, liveAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Posts -> {
                        val posts = repository.posts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(posts = if (posts.posts.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(posts.posts),
                                postsNextKey = posts.nextKey, postsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Videos -> {
                        val page = repository.videos(uiState.value.channelId, null)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update { state ->
                            val content = state.content as? ChannelContent.Ready ?: return@update state
                            state.copy(content = content.copy(page = content.page.copy(
                                videos = page.videos, videosNextKey = page.nextKey, videosDeferred = false)),
                                videosTab = ChannelSection.Ready(Unit),
                                videosAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Playlists -> {
                        val page = repository.playlists(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        _state.update {
                            it.copy(playlists = if (page.playlists.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(page.playlists),
                                playlistsNextKey = page.nextKey, playlistsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
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
                            ChannelTab.Videos -> it.copy(videosTab = ChannelSection.Failed)
                            ChannelTab.Playlists -> it.copy(playlists = ChannelSection.Failed)
                            else -> it.copy(posts = ChannelSection.Failed)
                        }
                    }
                }
            }
        }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        searchNextJob?.cancel()
        _state.update { it.copy(searchResults = ChannelSection.Loading, searchNextKey = null,
            searchAppend = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        searchJob = viewModelScope.launch {
            try {
                val results = repository.searchVideos(uiState.value.channelId, query)
                if (query != uiState.value.submittedQuery) return@launch
                _state.update {
                    it.copy(searchResults = if (results.videos.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(results.videos),
                        searchNextKey = results.nextKey)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (query == uiState.value.submittedQuery) _state.update { it.copy(searchResults = ChannelSection.Failed) }
            }
        }
    }

    /** Appends the next page of a paginated section; each section guards its own generation. */
    private fun loadSectionNext(tab: ChannelTab) {
        val state = _state.value
        val cursor = when (tab) {
            ChannelTab.Shorts -> state.shortsNextKey
            ChannelTab.Live -> state.liveNextKey
            ChannelTab.Posts -> state.postsNextKey
            ChannelTab.Playlists -> state.playlistsNextKey
            else -> return
        } ?: return
        val append = when (tab) {
            ChannelTab.Shorts -> state.shortsAppend
            ChannelTab.Live -> state.liveAppend
            ChannelTab.Playlists -> state.playlistsAppend
            else -> state.postsAppend
        }
        if (append == com.hhst.youtubelite.home.domain.AppendState.Loading) return
        sectionJobs[tab]?.cancel()
        val current = (sectionGenerations[tab] ?: 0) + 1
        sectionGenerations[tab] = current
        val loading = com.hhst.youtubelite.home.domain.AppendState.Loading
        _state.update {
            when (tab) {
                ChannelTab.Shorts -> it.copy(shortsAppend = loading)
                ChannelTab.Live -> it.copy(liveAppend = loading)
                ChannelTab.Playlists -> it.copy(playlistsAppend = loading)
                else -> it.copy(postsAppend = loading)
            }
        }
        sectionJobs[tab] = viewModelScope.launch {
            try {
                val appended: Pair<List<Any>, String?> = when (tab) {
                    ChannelTab.Shorts -> {
                        val page = repository.shortsNext(state.channelId, cursor)
                        Pair(page.shorts, page.nextKey)
                    }
                    ChannelTab.Live -> {
                        val page = repository.liveNext(state.channelId, cursor)
                        Pair(page.all, page.nextKey)
                    }
                    ChannelTab.Playlists -> {
                        val page = repository.playlistsNext(state.channelId, cursor)
                        Pair(page.playlists, page.nextKey)
                    }
                    else -> {
                        val page = repository.postsNext(state.channelId, cursor)
                        Pair(page.posts, page.nextKey)
                    }
                }
                if (current != sectionGenerations[tab]) return@launch
                _state.update {
                    when (tab) {
                        ChannelTab.Shorts -> {
                            val ready = it.shorts as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<ChannelShort>
                            // order is the loaded-sequence index: renumber so page-local
                            // indices from each append cannot interleave the local sorts.
                            val merged = (ready.value + added).distinctBy { s -> s.id }
                                .mapIndexed { index, short -> short.copy(order = index) }
                            it.copy(shorts = ChannelSection.Ready(merged),
                                shortsNextKey = appended.second.takeUnless { c -> c == cursor },
                                shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                        ChannelTab.Live -> {
                            val ready = it.live as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<ChannelLiveVideo>
                            val page = ready.value
                            // Live pages re-partition the combined list by liveNow, then
                            // renumber each partition so local sorts span every loaded page.
                            val combined = (page.all + added).distinctBy { v -> v.id }
                            it.copy(live = ChannelSection.Ready(page.copy(
                                current = combined.filter { v -> v.liveNow }
                                    .mapIndexed { index, video -> video.copy(order = index) },
                                past = combined.filterNot { v -> v.liveNow }
                                    .mapIndexed { index, video -> video.copy(order = index) },
                                nextKey = appended.second.takeUnless { c -> c == cursor })),
                                liveAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                        ChannelTab.Playlists -> {
                            val ready = it.playlists as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<com.hhst.youtubelite.playlist.domain.PlaylistSummary>
                            it.copy(playlists = ChannelSection.Ready((ready.value + added).distinctBy { p -> p.id }),
                                playlistsNextKey = appended.second.takeUnless { c -> c == cursor },
                                playlistsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                        else -> {
                            val ready = it.posts as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<PostSummary>
                            it.copy(posts = ChannelSection.Ready((ready.value + added).distinctBy { p -> p.identity.postId }),
                                postsNextKey = appended.second.takeUnless { c -> c == cursor },
                                postsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current == sectionGenerations[tab]) _state.update {
                    when (tab) {
                        ChannelTab.Shorts -> it.copy(shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Failed)
                        ChannelTab.Live -> it.copy(liveAppend = com.hhst.youtubelite.home.domain.AppendState.Failed)
                        ChannelTab.Playlists -> it.copy(playlistsAppend = com.hhst.youtubelite.home.domain.AppendState.Failed)
                        else -> it.copy(postsAppend = com.hhst.youtubelite.home.domain.AppendState.Failed)
                    }
                }
            }
        }
    }

    private fun loadSearchNext() {
        val state = _state.value
        val cursor = state.searchNextKey ?: return
        if (state.searchAppend == com.hhst.youtubelite.home.domain.AppendState.Loading) return
        searchNextJob?.cancel()
        _state.update { it.copy(searchAppend = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        searchNextJob = viewModelScope.launch {
            try {
                val page = repository.searchNext(state.channelId, cursor)
                if (page.videos.isEmpty() && page.nextKey == null && _state.value.searchNextKey != cursor) return@launch
                _state.update {
                    val ready = it.searchResults as? ChannelSection.Ready ?: return@update it
                    it.copy(searchResults = ChannelSection.Ready((ready.value + page.videos).distinctBy { v -> v.id }),
                        searchNextKey = page.nextKey.takeUnless { c -> c == cursor },
                        searchAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { it.copy(searchAppend = com.hhst.youtubelite.home.domain.AppendState.Failed) }
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
                ChannelTab.Videos -> it.copy(videosTab = ChannelSection.Loading)
                ChannelTab.Playlists -> it.copy(playlists = ChannelSection.Loading)
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
                _state.update { it.copy(content = ChannelContent.Ready(page),
                    videosTab = if (page.videosDeferred) ChannelSection.Loading else ChannelSection.Ready(Unit),
                    playlists = if (page.playlistsDeferred) ChannelSection.Loading
                        else if (page.playlists.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(page.playlists)) }
                ensureSectionLoaded(_state.value.selectedTab)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = ChannelContent.Failed,
                    verification = com.hhst.youtubelite.core.trafficChallenge(error) ?: it.verification) }
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = ChannelContent.Failed) }
            }
        }
    }

    /**
     * The host reports the verification screen finished with RESULT_OK. The channel
     * retries once with the current identity — never automatically on another
     * failure, so no request loop can form.
     */
    fun verificationCompleted(challengeUrl: String? = _state.value.verification?.challengeUrl) {
        if (challengeUrl == null || _state.value.verification?.challengeUrl != challengeUrl) return
        _state.update { it.copy(verification = null) }
        load()
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
