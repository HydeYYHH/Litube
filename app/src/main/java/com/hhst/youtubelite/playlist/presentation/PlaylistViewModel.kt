package com.hhst.youtubelite.playlist.presentation

import com.hhst.youtubelite.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.playlist.data.PlaylistScenario
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.watch.domain.toProvisional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.core.ContinuationHistory
import com.hhst.youtubelite.home.domain.AppendState

class PlaylistViewModel(
    private val repository: PlaylistRepository,
    private val savedState: SavedStateHandle,
    playlistId: String,
    scenario: PlaylistScenario = PlaylistScenario.Content,
    saveRepository: com.hhst.youtubelite.saves.domain.SaveRepository? = null,
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
    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    /** Live save flow; null in fixture builds, where typed exits stay the seam. */
    val saveSheet = saveRepository?.let {
        com.hhst.youtubelite.saves.presentation.SaveSheetController(it, viewModelScope) { notice -> notices.trySend(notice) }
    }
    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var enumerationJob: Job? = null
    private var generation = 0
    private var emptyAppends = 0
    private val continuations = ContinuationHistory()
    private var paginationLoop = false
    private var sessionKey: Long? = null

    private var pendingBatch: PendingBatch? = null

    private sealed interface PendingBatch { data class Queue(val shuffle: Boolean) : PendingBatch; data object Download : PendingBatch }

    init {
        savedState["playlistId"] = id
        if (scenario == PlaylistScenario.Content || scenario == PlaylistScenario.Empty) load()
    }

    /** Playlists are account-scoped: a moved generation invalidates the page, overlays and save sheet. */
    fun updateSession(value: Boolean, key: Long? = null) {
        val changed = key != null && sessionKey != null && sessionKey != key
        sessionKey = key
        state.update { it.copy(signedIn = value) }
        if (!changed) return
        ++generation
        loadJob?.cancel()
        appendJob?.cancel()
        enumerationJob?.cancel()
        pendingBatch = null
        saveSheet?.resetSession()
        overlay(PlaylistOverlay.None)
        load()
    }

    fun onAction(action: PlaylistAction) {
        when (action) {
            PlaylistAction.Retry -> load(keepContent = true)
            PlaylistAction.DismissVerification -> state.update { it.copy(verification = null) }
            is PlaylistAction.OpenVerification -> navigation.trySend(PlaylistNavigationRequest.OpenVerification(action.challengeUrl))
            PlaylistAction.LoadNext -> loadNext()
            PlaylistAction.CancelEnumeration -> cancelEnumeration()
            PlaylistAction.RetryEnumeration -> pendingBatch?.let { runBatch(it) }
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
                        // An empty list plays nothing: no batch, no queue churn.
                        if (page.entries.isEmpty()) return
                        overlay(PlaylistOverlay.None)
                        runBatch(PendingBatch.Queue(action == PlaylistAction.Shuffle))
                    }
                    PlaylistAction.Download -> {
                        if (page.entries.isEmpty()) return
                        overlay(PlaylistOverlay.None)
                        runBatch(PendingBatch.Download)
                    }
                    PlaylistAction.Save -> if (saveSheet == null) send(PlaylistNavigationRequest.Save(id)) else {
                        overlay(PlaylistOverlay.None)
                        saveSheet.savePlaylistToLibrary(id)
                    }
                    PlaylistAction.Share -> send(PlaylistNavigationRequest.Share(id))
                    PlaylistAction.OpenOwner -> send(PlaylistNavigationRequest.Channel(page.summary.channelId))
                    PlaylistAction.ToggleDescription -> {
                        val expanded = !state.value.descriptionExpanded
                        savedState["descriptionExpanded"] = expanded
                        state.update { it.copy(descriptionExpanded = expanded) }
                    }
                    is PlaylistAction.OpenVideo -> page.entries.find { it.id == action.entryId }?.let {
                        com.hhst.youtubelite.watch.domain.WatchProvisionalStore.put(it.toProvisional())
                        send(PlaylistNavigationRequest.Watch(it.videoId))
                    }
                    is PlaylistAction.OpenVideoMenu -> if (page.entries.any { it.id == action.entryId }) overlay(PlaylistOverlay.VideoMenu(action.entryId))
                    is PlaylistAction.VideoMenuAction -> {
                        val entryId = (state.value.overlay as? PlaylistOverlay.VideoMenu)?.entryId ?: return
                        val entry = page.entries.find { it.id == entryId } ?: return
                        overlay(PlaylistOverlay.None)
                        when {
                            saveSheet == null -> send(PlaylistNavigationRequest.VideoAction(id, entryId, entry.videoId, action.action))
                            action.action == PlaylistVideoAction.WatchLater -> saveSheet.saveWatchLater(entry.videoId)
                            action.action == PlaylistVideoAction.Save -> saveSheet.open(entry.videoId)
                        }
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

    private fun load(keepContent: Boolean = false) {
        val retained = state.value.content.takeIf { keepContent && it is PlaylistContent.Ready }
        loadJob?.cancel()
        appendJob?.cancel()
        enumerationJob?.cancel()
        emptyAppends = 0
        pendingBatch = null
        val current = ++generation
        state.update { it.copy(content = PlaylistContent.Loading, append = AppendState.Idle, enumeration = PlaylistEnumeration.Idle) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.playlist(id)
                if (current != generation) return@launch
                continuations.clear()
                paginationLoop = false
                state.update { it.copy(content = page?.let(PlaylistContent::Ready) ?: PlaylistContent.Unavailable) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: com.hhst.youtubelite.data.media.MediaException) {
                if (current == generation) {
                    state.update { it.copy(content = retained ?: PlaylistContent.Failed,
                        verification = com.hhst.youtubelite.core.trafficChallenge(error) ?: it.verification) }
                    if (retained != null) notices.trySend(AppNotice.Message("Couldn't refresh playlist. Try again."))
                }
            }
            catch (_: Exception) {
                if (current == generation) {
                    state.update { it.copy(content = retained ?: PlaylistContent.Failed) }
                    if (retained != null) notices.trySend(AppNotice.Message("Couldn't refresh playlist. Try again."))
                }
            }
        }
    }

    /** Retries once after RESULT_OK; never auto-retries another failure, so no request loop. */
    fun verificationCompleted(challengeUrl: String? = state.value.verification?.challengeUrl) {
        if (challengeUrl == null || state.value.verification?.challengeUrl != challengeUrl) return
        state.update { it.copy(verification = null) }
        load()
    }

    /** Failure keeps the loaded rows and offers a retry. */
    private fun loadNext() {
        val ready = state.value.content as? PlaylistContent.Ready ?: return
        val cursor = ready.page.nextKey ?: return
        if (state.value.append == AppendState.Loading || enumerationJob?.isActive == true) return
        appendJob?.cancel()
        val requestGeneration = generation
        state.update { it.copy(append = AppendState.Loading) }
        appendJob = viewModelScope.launch {
            try {
                val page = repository.playlistNext(id, cursor) ?: throw java.io.IOException("missing_append")
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                val existingIds = ready.page.entries.mapTo(hashSetOf()) { it.id }
                emptyAppends = if (page.entries.any { it.id !in existingIds }) 0 else emptyAppends + 1
                val nextKey = continuations.nextAfter(cursor, page.nextKey)
                paginationLoop = page.nextKey != null && nextKey == null
                val nextAppend = if (nextKey != null && emptyAppends >= MAX_EMPTY_APPENDS) AppendState.Failed else AppendState.Idle
                state.update { current ->
                    val existing = (current.content as? PlaylistContent.Ready)?.page ?: return@update current
                    current.copy(content = PlaylistContent.Ready(existing.copy(
                        entries = (existing.entries + page.entries).distinctBy { it.id },
                        // Stop browsing a loop while keeping this page's entries;
                        // batch actions still require a full confirmed list.
                        nextKey = nextKey,
                    )), append = nextAppend)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (requestGeneration == generation) state.update { it.copy(append = AppendState.Failed) }
            }
        }
    }

    /**
     * Batch actions keep full-playlist semantics: if entries remain unloaded, a bounded
     * cancellable enumeration completes the list first, then the action runs on the
     * complete list or fails — never on a truncated prefix.
     */
    private fun runBatch(action: PendingBatch) {
        val ready = state.value.content as? PlaylistContent.Ready ?: return
        if (ready.page.entries.isEmpty()) return
        if (enumerationJob?.isActive == true) return
        // Enumeration owns paging; a late cancelled-append response is rejected by ensureActive.
        appendJob?.cancel()
        appendJob = null
        state.update { it.copy(append = AppendState.Idle) }
        if (paginationLoop) {
            pendingBatch = action
            state.update { it.copy(enumeration = PlaylistEnumeration.Failed) }
            notices.trySend(AppNotice.Message("Couldn't load the full playlist. Refresh and try again."))
            return
        }
        if (ready.page.nextKey == null) {
            markComplete(ready.page.entries)
            execute(action)
            return
        }
        pendingBatch = action
        val requestGeneration = generation
        val base = ready.page
        state.update { it.copy(enumeration = PlaylistEnumeration.Running(base.entries.size, null)) }
        enumerationJob = viewModelScope.launch {
            try {
                var entries = base.entries
                var cursor = base.nextKey
                val seenCursors = mutableSetOf(cursor)
                var capped = false
                var emptyPages = 0
                while (cursor != null) {
                    if (entries.size >= PlaylistLimits.MAX_ENTRIES) { capped = true; break }
                    val page = repository.playlistNext(id, cursor) ?: throw java.io.IOException("missing_append")
                    currentCoroutineContext().ensureActive()
                    if (requestGeneration != generation) return@launch
                    val merged = (entries + page.entries).distinctBy { it.id }
                    emptyPages = if (merged.size == entries.size) emptyPages + 1 else 0
                    entries = merged
                    val next = page.nextKey
                    // Only a null server continuation proves enumeration ended; a loop
                    // must leave the page retryable and never run a batch on a prefix.
                    if (next != null && !seenCursors.add(next)) throw java.io.IOException("playlist_cursor_loop")
                    cursor = next
                    if (cursor != null && emptyPages >= MAX_EMPTY_APPENDS) throw java.io.IOException("playlist_no_progress")
                }
                currentCoroutineContext().ensureActive()
                if (requestGeneration != generation) return@launch
                markComplete(entries)
                if (capped) notices.trySend(AppNotice.LocalizedMessage(R.string.playlist_action_entry_limit, listOf(PlaylistLimits.MAX_ENTRIES, entries.size)))
                currentCoroutineContext().ensureActive()
                execute(pendingBatch)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (requestGeneration == generation) {
                    state.update { it.copy(enumeration = PlaylistEnumeration.Failed) }
                    notices.trySend(AppNotice.Message("Couldn't load the full playlist. Try again."))
                }
            }
        }
    }

    /** Loaded count becomes the whole truth. */
    private fun markComplete(entries: List<PlaylistEntry>) {
        state.update { current ->
            val ready = (current.content as? PlaylistContent.Ready) ?: return@update current
            current.copy(content = PlaylistContent.Ready(ready.page.copy(
                entries = entries, summary = ready.page.summary.copy(videoCount = entries.size), nextKey = null)),
                enumeration = PlaylistEnumeration.Idle)
        }
    }

    private fun execute(action: PendingBatch?) {
        val page = (state.value.content as? PlaylistContent.Ready)?.page ?: return
        when (action) {
            is PendingBatch.Queue -> if (page.entries.isNotEmpty())
                send(PlaylistNavigationRequest.Queue(id, page.entries.map { it.videoId }.distinct(), action.shuffle))
            PendingBatch.Download -> send(PlaylistNavigationRequest.Download(id))
            null -> Unit
        }
        pendingBatch = null
    }

    private fun cancelEnumeration() {
        pendingBatch = null
        enumerationJob?.cancel()
        state.update { it.copy(enumeration = PlaylistEnumeration.Idle) }
    }

    override fun onCleared() {
        navigation.close()
    }

    private companion object { const val MAX_EMPTY_APPENDS = 3 }
}
