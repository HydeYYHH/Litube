package com.hhst.youtubelite.explore

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import com.hhst.youtubelite.ui.design.setAppContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.core.trafficChallenge
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.data.FixtureExploreRepository
import com.hhst.youtubelite.explore.domain.ExploreAction
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExploreContent
import com.hhst.youtubelite.explore.domain.ExploreNavigationRequest
import com.hhst.youtubelite.explore.presentation.ExploreScreen
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExploreViewModel(private val repository: ExploreRepository, private val scenario: ExploreScenario,
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    /** Shared per-channel avatar completion; null in fixtures. */
    private val avatars: com.hhst.youtubelite.data.media.MediaAvatarDirectory? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(com.hhst.youtubelite.explore.domain.ExploreUiState(category =
        ExploreCategory.entries.firstOrNull { it.name == savedState.get<String>("category") } ?: ExploreCategory.Music))
    val uiState: StateFlow<com.hhst.youtubelite.explore.domain.ExploreUiState> = _uiState.asStateFlow()
    private val navigation = Channel<ExploreNavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<ExploreNavigationRequest> = navigation.receiveAsFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    private var generation = 0
    private var sessionGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation

    /** Loaded category content survives tab switches; identity changes drop it. */
    private val pages = java.util.EnumMap<ExploreCategory, com.hhst.youtubelite.explore.domain.ExploreContent>(ExploreCategory::class.java)
    private var appendJob: kotlinx.coroutines.Job? = null
    private val consumedPages = mutableMapOf<ExploreCategory, MutableSet<String>>()
    private val consumedShelves = mutableMapOf<Pair<ExploreCategory, Int>, MutableSet<String>>()
    /** Live shelf appends by shelf position; a full load or a tab switch cancels them. */
    private val shelfJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    /** Identity-scoped sections re-read after an account switch. */
    fun updateSession(generation: Long) {
        if (generation == sessionGeneration) return
        sessionGeneration = generation
        pages.clear()
        consumedPages.clear()
        consumedShelves.clear()
        load(_uiState.value.category)
    }

    init {
        if (scenario == ExploreScenario.Loading) {
            _uiState.update { it.copy(content = ExploreContent.Loading) }
        } else load(_uiState.value.category)
    }

    fun onAction(action: ExploreAction) {
        when (action) {
            ExploreAction.Retry -> load(_uiState.value.category)
            is ExploreAction.SelectCategory -> {
                if (action.category == _uiState.value.category) return
                savedState["category"] = action.category.name
                val current = _uiState.value
                // Retain the outgoing page with appends reset: their reads die with the switch.
                pages[current.category] = (current.content as? ExploreContent.Ready)?.let {
                    it.copy(append = AppendState.Idle,
                        page = it.page.copy(sections = it.page.sections.map { section -> section.copy(append = AppendState.Idle) }))
                } ?: current.content
                loadJob?.cancel()
                generation++
                cancelAppends()
                _uiState.update { it.copy(category = action.category) }
                val retained = pages[action.category]
                if (retained is ExploreContent.Ready) _uiState.update { it.copy(content = retained) }
                else load(action.category)
            }
            ExploreAction.LoadMore -> loadMore()
            ExploreAction.AutomaticLoadMore -> loadMore(automatic = true)
            is ExploreAction.LoadShelf -> loadShelf(action.index)
            is ExploreAction.AutomaticLoadShelf -> loadShelf(action.index, automatic = true)
            is ExploreAction.OpenVideo -> navigation.trySend(ExploreNavigationRequest.Watch(action.videoId))
            is ExploreAction.OpenChannel -> navigation.trySend(ExploreNavigationRequest.Channel(action.channelId))
            is ExploreAction.OpenPlaylist -> navigation.trySend(ExploreNavigationRequest.Playlist(action.playlistId))
            ExploreAction.DismissVerification -> _uiState.update { it.copy(verification = null) }
            is ExploreAction.OpenVerification -> navigation.trySend(ExploreNavigationRequest.OpenVerification(action.challengeUrl))
        }
    }

    private fun cancelAppends() {
        appendJob?.cancel()
        appendJob = null
        shelfJobs.values.forEach { it.cancel() }
        shelfJobs.clear()
    }

    private fun load(category: ExploreCategory) {
        loadJob?.cancel()
        cancelAppends()
        val request = ++generation
        consumedPages.remove(category)
        consumedShelves.keys.removeAll { it.first == category }
        _uiState.update { it.copy(content = ExploreContent.Loading) }
        loadJob = viewModelScope.launch {
            runCatching { repository.explore(category) }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    val content = ExploreContent.Ready(page)
                    pages[category] = content
                    _uiState.update { it.copy(content = content) }
                    fillMissingAvatars(page.videos + page.sections.flatMap { it.videos })
                },
                onFailure = { failure -> if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (request != generation) return@fold
                    val challenge = (failure as? com.hhst.youtubelite.data.media.MediaException)
                        ?.let(::trafficChallenge)
                    _uiState.update { it.copy(content = ExploreContent.Failed, verification = challenge ?: it.verification) } },
            )
        }
    }

    /** One in-flight page append per category; a cursor that repeats itself ends the list. */
    private fun loadMore(automatic: Boolean = false) {
        val ready = _uiState.value.content as? ExploreContent.Ready ?: return
        val key = ready.page.nextKey ?: return
        if (ready.append == AppendState.Loading || appendJob?.isActive == true) return
        if (automatic && ready.automaticPagingPaused) return
        val category = _uiState.value.category
        val request = generation
        _uiState.update { it.copy(content = ready.copy(append = AppendState.Loading)) }
        appendJob = viewModelScope.launch {
            runCatching { repository.exploreNext(category, key) }.fold(
                onSuccess = { page ->
                    if (request != generation) return@fold
                    consumedPages.getOrPut(category) { mutableSetOf() }.add(key)
                    val current = (_uiState.value.content as? ExploreContent.Ready)
                        ?.takeIf { _uiState.value.category == category && it.page.nextKey == key } ?: return@fold
                    val merged = mergePage(current, page, key)
                    pages[category] = merged
                    _uiState.update { it.copy(content = merged) }
                    fillMissingAvatars(page.videos + page.sections.flatMap { it.videos })
                },
                onFailure = { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (request != generation) return@fold
                    val current = (_uiState.value.content as? ExploreContent.Ready)
                        ?.takeIf { _uiState.value.category == category && it.page.nextKey == key } ?: return@fold
                    _uiState.update { it.copy(content = current.copy(append = AppendState.Failed)) }
                },
            )
        }
    }

    private fun mergePage(current: ExploreContent.Ready, page: com.hhst.youtubelite.explore.domain.ExplorePage,
        key: String): ExploreContent.Ready {
        val looped = page.nextKey == key || page.nextKey in consumedPages[_uiState.value.category].orEmpty()
        val sections = current.page.sections.toMutableList()
        val incomingSections = if (sections.isNotEmpty() && page.sections.isEmpty() && (page.videos.isNotEmpty() || page.playlists.isNotEmpty()))
            listOf(com.hhst.youtubelite.explore.domain.ExploreSection(null, page.videos, playlists = page.playlists)) else page.sections
        for (section in incomingSections) {
            val index = sections.indexOfFirst {
                it.title == section.title && (it.videos.firstOrNull()?.id?.let { first ->
                    first == section.videos.firstOrNull()?.id
                } ?: it.playlists.firstOrNull()?.id?.let { first -> first == section.playlists.firstOrNull()?.id }
                    ?: (it.nextKey != null && it.nextKey == section.nextKey))
            }
            if (index < 0) sections += section
            else {
                val previous = sections[index]
                sections[index] = previous.copy(
                    videos = (previous.videos + section.videos).distinctBy { it.id },
                    playlists = (previous.playlists + section.playlists).distinctBy { it.id },
                    nextKey = section.nextKey ?: previous.nextKey,
                )
            }
        }
        val videos = (current.page.videos + page.videos).distinctBy { it.id }
        val combined = current.page.copy(videos = videos, sections = sections,
            playlists = (current.page.playlists + page.playlists).distinctBy { it.id },
            nextKey = if (looped) null else page.nextKey)
        val previousCards = current.page.cardKeys()
        val addedCards = combined.cardKeys().any { it !in previousCards }
        return current.copy(page = combined, append = AppendState.Idle,
            emptyAutoLoads = if (addedCards) 0 else current.emptyAutoLoads + 1)
    }

    private fun com.hhst.youtubelite.explore.domain.ExplorePage.cardKeys(): Set<String> = buildSet {
        videos.forEach { add("video:${it.id}") }
        playlists.forEach { add("playlist:${it.id}") }
        sections.forEach { section ->
            section.videos.forEach { add("video:${it.id}") }
            section.playlists.forEach { add("playlist:${it.id}") }
        }
    }

    /** Shelf appends are scoped to the shelf's position plus the cursor that launched them. */
    private fun loadShelf(index: Int, automatic: Boolean = false) {
        val ready = _uiState.value.content as? ExploreContent.Ready ?: return
        val section = ready.page.sections.getOrNull(index) ?: return
        val key = section.nextKey ?: return
        if (section.append == AppendState.Loading || shelfJobs[index]?.isActive == true) return
        if (automatic && section.automaticPagingPaused) return
        val category = _uiState.value.category
        val request = generation
        updateShelf(index, key) { it.copy(append = AppendState.Loading) }
        shelfJobs[index] = viewModelScope.launch {
            runCatching { repository.exploreShelf(category, key) }.fold(
                onSuccess = { result ->
                    if (request != generation || category != _uiState.value.category) return@fold
                    val visited = consumedShelves.getOrPut(category to index) { mutableSetOf() }
                    visited.add(key)
                    updateShelf(index, key) {
                        val looped = result.nextKey == key || result.nextKey in visited
                        val videos = (it.videos + result.videos).distinctBy { video -> video.id }
                        val playlists = (it.playlists + result.playlists).distinctBy { playlist -> playlist.id }
                        val addedCards = videos.size > it.videos.size || playlists.size > it.playlists.size
                        it.copy(videos = videos,
                            playlists = playlists,
                            nextKey = if (looped) null else result.nextKey,
                            append = AppendState.Idle, emptyAutoLoads = if (addedCards) 0 else it.emptyAutoLoads + 1)
                    }
                    fillMissingAvatars(result.videos)
                },
                onFailure = { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    if (request == generation && category == _uiState.value.category)
                        updateShelf(index, key) { it.copy(append = AppendState.Failed) }
                },
            )
            if (request == generation) shelfJobs.remove(index)
        }
    }

    /** Applies [edit] only while the shelf still owns the cursor that launched the read. */
    private fun updateShelf(index: Int, key: String, edit: (com.hhst.youtubelite.explore.domain.ExploreSection) -> com.hhst.youtubelite.explore.domain.ExploreSection) {
        val ready = _uiState.value.content as? ExploreContent.Ready ?: return
        val section = ready.page.sections.getOrNull(index) ?: return
        if (section.nextKey != key) return
        val sections = ready.page.sections.toMutableList()
        sections[index] = edit(section)
        _uiState.update { it.copy(content = ready.copy(page = ready.page.copy(sections = sections))) }
    }

    /**
     * One coalesced directory read per channel; a real-missing channel keeps its
     * null and the card's stable placeholder, never a perpetual loading state.
     */
    private fun fillMissingAvatars(items: List<com.hhst.youtubelite.home.domain.FeedItem.Video>) {
        val directory = avatars ?: return
        val missing = items.filter { it.avatar == null && it.channelId.isNotBlank() }
            .distinctBy { it.channelId }.takeIf { it.isNotEmpty() } ?: return
        val requestSession = sessionGeneration
        viewModelScope.launch {
            directory.resolveAll(missing.map { it.channelId }) { channelId, avatar ->
                if (requestSession != sessionGeneration) return@resolveAll
                fun patchContent(value: ExploreContent): ExploreContent {
                    val content = value as? ExploreContent.Ready ?: return value
                    fun patch(videos: List<com.hhst.youtubelite.home.domain.FeedItem.Video>) = videos.map { item ->
                        if (item.avatar == null && item.channelId == channelId) item.copy(avatar = avatar) else item
                    }
                    val page = content.page
                    return content.copy(page = page.copy(
                        videos = patch(page.videos),
                        sections = page.sections.map { section -> section.copy(videos = patch(section.videos)) }))
                }
                pages.replaceAll { _, content -> patchContent(content) }
                _uiState.update { it.copy(content = patchContent(it.content)) }
            }
        }
    }

}

class ExploreActivity : com.hhst.youtubelite.ui.ContentActivity() {
    lateinit var exploreViewModel: ExploreViewModel
        private set
    var lastNavigationRequest: ExploreNavigationRequest? = null
        private set

    private val verificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancelled verification keeps the card; a completed one grants one retry.
        if (result.resultCode == RESULT_OK) {
            val challenge = result.data?.getStringExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE)
            if (challenge != null && exploreViewModel.uiState.value.verification?.challengeUrl == challenge) {
                exploreViewModel.onAction(ExploreAction.DismissVerification)
                exploreViewModel.onAction(ExploreAction.Retry)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Explore sections are identity-scoped server-side (visitor vs account).
        if (!com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)) {
            exploreViewModel.updateSession(com.hhst.youtubelite.data.media.MediaSession.snapshot().generation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sources = org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.data.media.ContentRepositories>()
        val fixtures = com.hhst.youtubelite.data.media.ContentRepositories.fixtures(intent)
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val scenario = if (BuildConfig.DEBUG) ExploreScenario.parse(intent.getStringExtra(EXTRA_SCENARIO)) else ExploreScenario.Content
        val category = ExploreCategory.parse(intent.getStringExtra(EXTRA_CATEGORY))
        exploreViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass == ExploreViewModel::class.java)
                val handle = extras.createSavedStateHandle()
                if (!handle.contains("category")) handle["category"] = category.name
                return ExploreViewModel(if (fixtures) FixtureExploreRepository(scenario) else sources.explore, scenario, handle,
                    if (fixtures) null else sources.avatars) as T
            }
        })[ExploreViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                exploreViewModel.navigationRequests.collect { request ->
                    lastNavigationRequest = request
                    when (request) {
                        is ExploreNavigationRequest.Watch -> launchNavigation(WatchActivity.intent(this@ExploreActivity, request.videoId))
                        is ExploreNavigationRequest.Channel -> launchNavigation(ChannelActivity.intent(this@ExploreActivity, request.channelId))
                        is ExploreNavigationRequest.Playlist -> launchNavigation(com.hhst.youtubelite.playlist.PlaylistActivity.intent(this@ExploreActivity, request.playlistId))
                        is ExploreNavigationRequest.OpenVerification -> verificationLauncher.launch(
                            android.content.Intent(this@ExploreActivity, com.hhst.youtubelite.core.TrafficVerificationActivity::class.java)
                                .putExtra(com.hhst.youtubelite.core.TrafficVerificationActivity.EXTRA_CHALLENGE, request.challengeUrl))
                        // No `else`: a new request type must fail to compile here.
                    }
                }
            }
        }
        setAppContent {
            val state by exploreViewModel.uiState.collectAsStateWithLifecycle()
            ExploreScreen(state, exploreViewModel::onAction, onBack = { finish() },
                modifier = Modifier.fillMaxSize().background(com.hhst.youtubelite.ui.design.DesignTokens.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "explore_scenario"
        const val EXTRA_CATEGORY = "explore_category"

        fun intent(context: Context, category: ExploreCategory? = null, scenario: ExploreScenario? = null) =
            Intent(context, ExploreActivity::class.java)
                .apply {
                    if (category != null) putExtra(EXTRA_CATEGORY, category.name)
                    if (scenario != null) putExtra(EXTRA_SCENARIO, scenario.argument)
                }
    }
}
