package com.hhst.youtubelite.channel.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.watch.domain.toProvisional
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
import com.hhst.youtubelite.core.ContinuationHistory
import com.hhst.youtubelite.home.domain.AppendState

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
    private var searchGeneration = 0
    private val sectionJobs = mutableMapOf<ChannelTab, Job>()
    private val sectionGenerations = mutableMapOf<ChannelTab, Int>()
    private val emptyAppends = mutableMapOf<ChannelTab, Int>()
    private val continuations = mutableMapOf<ChannelTab, ContinuationHistory>()

    /** Identity epoch captured by in-flight writes so a switched session cannot publish into the new one. */
    private var sessionGeneration = 0L
    fun updateSignedIn(signedIn: Boolean) {
        _state.update { it.copy(signedIn = signedIn) }
    }

    fun refreshSession() {
        sessionGeneration++
        subscribeJob?.cancel()
        searchJob?.cancel()
        searchNextJob?.cancel()
        searchGeneration++
        appendJob?.cancel()
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        continuations.clear()
        emptyAppends.clear()
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
            ChannelAction.Retry -> load(keepContent = true)
            is ChannelAction.SelectDestination -> navigation.trySend(ChannelNavigationRequest.Destination(action.destination))
            is ChannelAction.SelectTab -> selectTab(action.tab)
            is ChannelAction.OpenItemMenu -> navigation.trySend(ChannelNavigationRequest.ItemMenu(action.videoId))
            is ChannelAction.OpenVideo -> {
                rememberProvisional(action.videoId)
                navigation.trySend(ChannelNavigationRequest.Watch(action.videoId))
            }
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
                    // A switched session's late failure must not publish into the new one.
                    val requestSession = sessionGeneration
                    _state.update { it.copy(subscribePending = true) }
                    subscribeJob = viewModelScope.launch {
                        try {
                            actions.subscribe(uiState.value.channelId, target)
                            if (sessionGeneration != requestSession) return@launch
                            _state.update { state ->
                                val current = state.content as? ChannelContent.Ready ?: return@update state
                                state.copy(content = current.copy(page = current.page.copy(
                                    profile = current.page.profile.copy(subscribed = target))), subscribePending = false)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) {
                            if (sessionGeneration == requestSession) {
                                _state.update { it.copy(subscribePending = false) }
                                notices.trySend(AppNotice.SignIn)
                            }
                        }
                        catch (error: com.hhst.youtubelite.watch.domain.UnconfirmedActionException) {
                            if (sessionGeneration == requestSession) {
                                _state.update { it.copy(subscribePending = false) }
                                notices.trySend(report("channel_subscribe", error))
                            }
                        }
                        catch (error: Exception) {
                            if (sessionGeneration == requestSession) {
                                _state.update { it.copy(subscribePending = false) }
                                notices.trySend(report("channel_subscribe", error))
                            }
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
                else {
                    searchGeneration++
                    searchJob?.cancel()
                    searchNextJob?.cancel()
                    resetContinuation(ChannelTab.Search)
                    _state.update { it.copy(searchResults = null, searchNextKey = null,
                        searchAppend = com.hhst.youtubelite.home.domain.AppendState.Idle) }
                }
            }
            ChannelAction.RetrySearch -> {
                val query = uiState.value.submittedQuery
                if (repository.serverSearch && query.isNotEmpty()) runSearch(query, keepContent = true)
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
            is ChannelAction.RetrySection -> loadSection(action.tab, keepContent = true)
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
                    val profile = (uiState.value.content as? ChannelContent.Ready)?.page?.profile
                    com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(
                        com.hhst.youtubelite.watch.domain.WatchProvisional(
                            videoId = video.id, title = video.title, channel = profile?.name.orEmpty(),
                            channelId = uiState.value.channelId, avatar = profile?.avatar,
                            artwork = video.artwork, views = video.views, age = video.time,
                            duration = video.duration,
                        ))
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

    private fun report(operation: String, error: Exception): AppNotice =
        com.hhst.youtubelite.diagnostics.writeFailureNotice(operation, error, mapOf(
            "channel_id" to (_state.value.content as? ChannelContent.Ready)?.page?.profile?.channelId))

    /** Hands the tapped video's known fields to the Watch page before navigating. */
    private fun rememberProvisional(videoId: String) {
        val page = (uiState.value.content as? ChannelContent.Ready)?.page ?: return
        val video = page.videos.firstOrNull { it.id == videoId }
            ?: page.homeShelves.asSequence().flatMap { it.videos.asSequence() }.firstOrNull { it.id == videoId }
            ?: (uiState.value.searchResults as? ChannelSection.Ready)?.value
                ?.firstOrNull { it.id == videoId }
            ?: return
        com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(
            video.toProvisional(page.profile.name, uiState.value.channelId, page.profile.avatar))
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

    private fun loadSection(tab: ChannelTab, keepContent: Boolean = false) {
        if (tab !in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts, ChannelTab.Videos, ChannelTab.Playlists)) return
        val previous = _state.value
        val section = when (tab) {
            ChannelTab.Shorts -> previous.shorts
            ChannelTab.Live -> previous.live
            ChannelTab.Videos -> previous.videosTab
            ChannelTab.Playlists -> previous.playlists
            else -> previous.posts
        }
        val retainSection = keepContent && (section is ChannelSection.Ready || section == ChannelSection.Empty)
        sectionJobs[tab]?.cancel()
        if (tab == ChannelTab.Videos) appendJob?.cancel()
        val current = (sectionGenerations[tab] ?: 0) + 1
        sectionGenerations[tab] = current
        setSectionLoading(tab)
        val opened = (uiState.value.content as? ChannelContent.Ready)?.page?.takeIf { it.readReference != null }
        sectionJobs[tab] = viewModelScope.launch {
            try {
                when (tab) {
                    ChannelTab.Shorts -> {
                        val page = opened?.let { repository.shorts(it.profile.channelId, it) }
                            ?: repository.shorts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        resetContinuation(tab)
                        _state.update {
                            it.copy(shorts = if (page.shorts.isEmpty() && page.nextKey == null) ChannelSection.Empty else ChannelSection.Ready(page.shorts),
                                shortsNextKey = page.nextKey, shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Live -> {
                        val live = opened?.let { repository.live(it.profile.channelId, it) }
                            ?: repository.live(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        resetContinuation(tab)
                        _state.update {
                            it.copy(live = if (live.all.isEmpty() && live.nextKey == null) ChannelSection.Empty else ChannelSection.Ready(live),
                                liveNextKey = live.nextKey, liveAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Posts -> {
                        val posts = repository.posts(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        resetContinuation(tab)
                        _state.update {
                            it.copy(posts = if (posts.posts.isEmpty() && posts.nextKey == null) ChannelSection.Empty else ChannelSection.Ready(posts.posts),
                                postsNextKey = posts.nextKey, postsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Videos -> {
                        val page = opened?.let { repository.videos(it.profile.channelId, it) }
                            ?: repository.videos(uiState.value.channelId, null)
                        if (current != sectionGenerations[tab]) return@launch
                        resetContinuation(tab)
                        _state.update { state ->
                            val content = state.content as? ChannelContent.Ready ?: return@update state
                            state.copy(content = content.copy(page = content.page.copy(
                                videos = page.videos, videosNextKey = page.nextKey, videosDeferred = false)),
                                videosTab = ChannelSection.Ready(Unit),
                                videosAppend = com.hhst.youtubelite.home.domain.AppendState.Idle)
                        }
                    }
                    ChannelTab.Playlists -> {
                        val page = opened?.let { repository.playlists(it.profile.channelId, it) }
                            ?: repository.playlists(uiState.value.channelId)
                        if (current != sectionGenerations[tab]) return@launch
                        resetContinuation(tab)
                        _state.update {
                            it.copy(playlists = if (page.playlists.isEmpty() && page.nextKey == null) ChannelSection.Empty else ChannelSection.Ready(page.playlists),
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
                            ChannelTab.Shorts -> it.copy(shorts = if (retainSection) previous.shorts else ChannelSection.Failed)
                            ChannelTab.Live -> it.copy(live = if (retainSection) previous.live else ChannelSection.Failed)
                            ChannelTab.Videos -> it.copy(videosTab = if (retainSection) previous.videosTab else ChannelSection.Failed)
                            ChannelTab.Playlists -> it.copy(playlists = if (retainSection) previous.playlists else ChannelSection.Failed)
                            else -> it.copy(posts = if (retainSection) previous.posts else ChannelSection.Failed)
                        }
                    }
                    if (retainSection) notices.trySend(AppNotice.Message("Couldn't refresh channel. Try again."))
                }
            }
        }
    }

    private fun runSearch(query: String, keepContent: Boolean = false) {
        val previous = _state.value
        val retained = previous.searchResults.takeIf { keepContent &&
            (it is ChannelSection.Ready || it == ChannelSection.Empty) }
        searchJob?.cancel()
        searchNextJob?.cancel()
        val request = ++searchGeneration
        _state.update { it.copy(searchResults = ChannelSection.Loading, searchNextKey = null,
            searchAppend = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        searchJob = viewModelScope.launch {
            try {
                val results = repository.searchVideos(uiState.value.channelId, query)
                if (request != searchGeneration || query != uiState.value.submittedQuery) return@launch
                resetContinuation(ChannelTab.Search)
                _state.update {
                    it.copy(searchResults = if (results.videos.isEmpty() && results.nextKey == null) ChannelSection.Empty else ChannelSection.Ready(results.videos),
                        searchNextKey = results.nextKey)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == searchGeneration && query == uiState.value.submittedQuery) {
                    _state.update { it.copy(searchResults = retained ?: ChannelSection.Failed,
                        searchNextKey = previous.searchNextKey.takeIf { retained != null }) }
                    if (retained != null) notices.trySend(AppNotice.Message("Couldn't refresh results. Try again."))
                }
            }
        }
    }

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
                val existingIds = when (tab) {
                    ChannelTab.Shorts -> (state.shorts as? ChannelSection.Ready)?.value.orEmpty().map { it.id }
                    ChannelTab.Live -> (state.live as? ChannelSection.Ready)?.value?.all.orEmpty().map { it.id }
                    ChannelTab.Playlists -> (state.playlists as? ChannelSection.Ready)?.value.orEmpty().map { it.id }
                    else -> (state.posts as? ChannelSection.Ready)?.value.orEmpty().map { it.identity.postId }
                }.toHashSet()
                val gainedItems = appended.first.any { added ->
                    val id = when (added) {
                        is ChannelShort -> added.id
                        is ChannelLiveVideo -> added.id
                        is com.hhst.youtubelite.playlist.domain.PlaylistSummary -> added.id
                        is PostSummary -> added.identity.postId
                        else -> return@any false
                    }
                    id !in existingIds
                }
                val nextKey = continuation(tab).nextAfter(cursor, appended.second)
                val nextAppend = recordAppendProgress(tab, gainedItems, nextKey)
                _state.update {
                    when (tab) {
                        ChannelTab.Shorts -> {
                            val ready = it.shorts as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<ChannelShort>
                            // order is the loaded-sequence index; renumber so page-local
                            // indices from each append cannot interleave the local sorts.
                            val merged = (ready.value + added).distinctBy { s -> s.id }
                                .mapIndexed { index, short -> short.copy(order = index) }
                            it.copy(shorts = ChannelSection.Ready(merged),
                                shortsNextKey = nextKey,
                                shortsAppend = nextAppend)
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
                                nextKey = nextKey)),
                                liveNextKey = nextKey,
                                liveAppend = nextAppend)
                        }
                        ChannelTab.Playlists -> {
                            val ready = it.playlists as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<com.hhst.youtubelite.playlist.domain.PlaylistSummary>
                            it.copy(playlists = ChannelSection.Ready((ready.value + added).distinctBy { p -> p.id }),
                                playlistsNextKey = nextKey,
                                playlistsAppend = nextAppend)
                        }
                        else -> {
                            val ready = it.posts as? ChannelSection.Ready ?: return@update it
                            @Suppress("UNCHECKED_CAST")
                            val added = appended.first as List<PostSummary>
                            it.copy(posts = ChannelSection.Ready((ready.value + added).distinctBy { p -> p.identity.postId }),
                                postsNextKey = nextKey,
                                postsAppend = nextAppend)
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
        val request = searchGeneration
        searchNextJob?.cancel()
        _state.update { it.copy(searchAppend = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        searchNextJob = viewModelScope.launch {
            try {
                val page = repository.searchNext(state.channelId, cursor)
                if (request != searchGeneration || state.submittedQuery != _state.value.submittedQuery) return@launch
                val existingIds = (state.searchResults as? ChannelSection.Ready)?.value.orEmpty().mapTo(hashSetOf()) { it.id }
                val nextKey = continuation(ChannelTab.Search).nextAfter(cursor, page.nextKey)
                val nextAppend = recordAppendProgress(ChannelTab.Search, page.videos.any { it.id !in existingIds },
                    nextKey)
                _state.update {
                    val ready = it.searchResults as? ChannelSection.Ready ?: return@update it
                    it.copy(searchResults = ChannelSection.Ready((ready.value + page.videos).distinctBy { v -> v.id }),
                        searchNextKey = nextKey,
                        searchAppend = nextAppend)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == searchGeneration && state.submittedQuery == _state.value.submittedQuery)
                    _state.update { it.copy(searchAppend = com.hhst.youtubelite.home.domain.AppendState.Failed) }
            }
        }
    }

    private fun loadVideosNext() {
        val ready = uiState.value.content as? ChannelContent.Ready ?: return
        val next = ready.page.videosNextKey ?: return
        if (appendJob?.isActive == true || sectionJobs[ChannelTab.Videos]?.isActive == true) return
        val requestGeneration = generation
        val sectionGeneration = sectionGenerations[ChannelTab.Videos]
        _state.update { it.copy(videosAppend = com.hhst.youtubelite.home.domain.AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.videos(uiState.value.channelId, next)
                if (requestGeneration != generation || sectionGeneration != sectionGenerations[ChannelTab.Videos]) return@launch
                val existingIds = ready.page.videos.mapTo(hashSetOf()) { it.id }
                val nextKey = continuation(ChannelTab.Videos).nextAfter(next, page.nextKey)
                val nextAppend = recordAppendProgress(ChannelTab.Videos, page.videos.any { it.id !in existingIds },
                    nextKey)
                _state.update { state ->
                    val current = state.content as? ChannelContent.Ready ?: return@update state
                    if (current.page.videosNextKey != next) return@update state
                    state.copy(
                        content = current.copy(page = current.page.copy(
                            videos = (current.page.videos + page.videos).distinctBy { it.id },
                            videosNextKey = nextKey,
                        )),
                        videosAppend = nextAppend,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation && sectionGeneration == sectionGenerations[ChannelTab.Videos])
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

    private fun load(keepContent: Boolean = false) {
        val retained = _state.value.content.takeIf { keepContent && it is ChannelContent.Ready }
        loadJob?.cancel()
        appendJob?.cancel()
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        sectionGenerations.keys.toList().forEach { sectionGenerations[it] = sectionGenerations.getValue(it) + 1 }
        val requestGeneration = ++generation
        _state.update { it.copy(content = ChannelContent.Loading,
            videosAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            shortsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            liveAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            postsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle,
            playlistsAppend = com.hhst.youtubelite.home.domain.AppendState.Idle) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.channel(uiState.value.channelId)
                if (requestGeneration != generation) return@launch
                resetContinuation(ChannelTab.Videos)
                resetContinuation(ChannelTab.Playlists)
                savedState["channelId"] = page.profile.channelId
                _state.update { it.copy(channelId = page.profile.channelId, content = ChannelContent.Ready(page),
                    playlistsNextKey = null,
                    videosTab = if (page.videosDeferred) ChannelSection.Loading else ChannelSection.Ready(Unit),
                    playlists = if (page.playlistsDeferred) ChannelSection.Loading
                        else if (page.playlists.isEmpty()) ChannelSection.Empty else ChannelSection.Ready(page.playlists)) }
                ensureSectionLoaded(_state.value.selectedTab)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = retained ?: ChannelContent.Failed,
                    verification = com.hhst.youtubelite.core.trafficChallenge(error) ?: it.verification) }
                if (retained != null) {
                    notices.trySend(AppNotice.Message("Couldn't refresh channel. Try again."))
                    // Page refresh cancelled section reads; resume only the visible
                    // section if it had not produced content yet.
                    ensureSectionLoaded(_state.value.selectedTab)
                }
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { it.copy(content = retained ?: ChannelContent.Failed) }
                if (retained != null) {
                    notices.trySend(AppNotice.Message("Couldn't refresh channel. Try again."))
                    // Page refresh cancelled section reads; resume only the visible
                    // section if it had not produced content yet.
                    ensureSectionLoaded(_state.value.selectedTab)
                }
            }
        }
    }

    /**
     * Verification finished (RESULT_OK): retry once with the current identity, never
     * automatically on another failure, so no request loop can form.
     */
    fun verificationCompleted(challengeUrl: String? = _state.value.verification?.challengeUrl) {
        if (challengeUrl == null || _state.value.verification?.challengeUrl != challengeUrl) return
        _state.update { it.copy(verification = null) }
        load()
    }

    private fun continuation(tab: ChannelTab): ContinuationHistory =
        continuations.getOrPut(tab) { ContinuationHistory() }

    /** A successful first page replaces this section's browse cycle; failed refreshes keep it. */
    private fun resetContinuation(tab: ChannelTab) {
        continuations[tab]?.clear()
        emptyAppends.remove(tab)
    }

    /** A server can keep changing cursors without adding cards. Pause until an explicit retry. */
    private fun recordAppendProgress(tab: ChannelTab, gainedItems: Boolean, nextKey: String?): AppendState {
        val count = if (gainedItems) 0 else (emptyAppends[tab] ?: 0) + 1
        emptyAppends[tab] = count
        return if (nextKey != null && count >= MAX_EMPTY_APPENDS) AppendState.Failed else AppendState.Idle
    }

    private companion object { const val MAX_EMPTY_APPENDS = 3 }

    override fun onCleared() {
        navigation.close()
    }
}

private fun ChannelScenario.initialSection(): ChannelSection<Nothing> = when (this) {
    ChannelScenario.Content, ChannelScenario.Loading -> ChannelSection.Loading
    ChannelScenario.Empty -> ChannelSection.Empty
    ChannelScenario.Error -> ChannelSection.Failed
}
