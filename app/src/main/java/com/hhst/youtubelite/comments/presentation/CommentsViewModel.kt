package com.hhst.youtubelite.comments.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentsRepository
import com.hhst.youtubelite.comments.domain.CommentsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Panel state for one video. Sorting and expanded threads survive recreation through
 * [SavedStateHandle]; the visible list itself is refetched from the local fixture.
 */
class CommentsViewModel(
    private val repository: CommentsRepository,
    private val savedState: SavedStateHandle,
    videoId: String,
) : ViewModel() {
    private val sort = runCatching {
        CommentSort.valueOf(savedState.get<String>(KEY_SORT) ?: CommentSort.Top.name)
    }.getOrDefault(CommentSort.Top)
    private val expanded = savedState.get<ArrayList<String>>(KEY_EXPANDED)?.toSet() ?: emptySet()
    private val videoId = savedState.get<String>(KEY_VIDEO) ?: videoId
    private val restoredOverlay: CommentsOverlay = when (savedState.get<String>(KEY_OVERLAY)) {
        "sort" -> CommentsOverlay.SortMenu
        "thread" -> savedState.get<String>(KEY_THREAD)?.let { CommentsOverlay.ThreadMenu(it) }
        "reply" -> {
            val thread = savedState.get<String>(KEY_THREAD)
            val reply = savedState.get<String>(KEY_REPLY)
            if (thread != null && reply != null) CommentsOverlay.ReplyMenu(thread, reply) else null
        }
        else -> null
    } ?: CommentsOverlay.None

    private val _state = MutableStateFlow(
        CommentsUiState(videoId = this.videoId, sort = sort, expanded = expanded, overlay = restoredOverlay),
    )
    val uiState = _state.asStateFlow()

    private val navigation = Channel<CommentNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0

    init {
        savedState[KEY_VIDEO] = this.videoId
        savedState[KEY_SORT] = sort.name
        savedState[KEY_EXPANDED] = ArrayList(expanded)
        persistOverlay(restoredOverlay)
        load()
    }

    fun onAction(action: CommentAction) {
        when (action) {
            CommentAction.Retry -> load()
            CommentAction.LoadMore -> loadMore()
            is CommentAction.SelectSort -> {
                overlay(CommentsOverlay.None)
                selectSort(action.sort)
            }
            CommentAction.OpenSortMenu -> overlay(CommentsOverlay.SortMenu)
            CommentAction.CloseMenu -> overlay(CommentsOverlay.None)
            is CommentAction.MenuAction -> selectMenuAction(action.action)
            is CommentAction.ToggleReplies -> toggleReplies(action.commentId)
            CommentAction.Compose -> navigation.trySend(CommentNavigationRequest.Compose(videoId))
            is CommentAction.LikeThread -> navigation.trySend(CommentNavigationRequest.LikeComment(videoId, action.commentId))
            is CommentAction.DislikeThread -> navigation.trySend(CommentNavigationRequest.DislikeComment(videoId, action.commentId))
            is CommentAction.ReportThread -> navigation.trySend(
                CommentNavigationRequest.ReportComment(videoId, action.commentId, action.commentId),
            )
            is CommentAction.OpenThreadMenu -> {
                overlay(CommentsOverlay.ThreadMenu(action.commentId))
                navigation.trySend(CommentNavigationRequest.OpenCommentMenu(videoId, action.commentId))
            }
            is CommentAction.OpenAuthor -> navigation.trySend(CommentNavigationRequest.OpenAuthor(action.channelId))
            is CommentAction.Reply -> navigation.trySend(CommentNavigationRequest.Reply(videoId, action.threadId, action.commentId))
            is CommentAction.LikeReply -> navigation.trySend(CommentNavigationRequest.LikeComment(videoId, action.replyId))
            is CommentAction.DislikeReply -> navigation.trySend(CommentNavigationRequest.DislikeComment(videoId, action.replyId))
            is CommentAction.ReportReply -> navigation.trySend(
                CommentNavigationRequest.ReportComment(videoId, action.commentId, action.replyId),
            )
            is CommentAction.OpenReplyMenu -> {
                overlay(CommentsOverlay.ReplyMenu(action.commentId, action.replyId))
                navigation.trySend(CommentNavigationRequest.OpenReplyMenu(videoId, action.commentId, action.replyId))
            }
        }
    }

    /**
     * Both menu kinds share two public rows, but their typed requests keep the thread and
     * the concrete comment identity separate.
     */
    private fun selectMenuAction(action: CommentMenuAction) {
        when (val current = uiState.value.overlay) {
            is CommentsOverlay.ThreadMenu -> {
                overlay(CommentsOverlay.None)
                when (action) {
                    CommentMenuAction.Reply -> navigation.trySend(
                        CommentNavigationRequest.Reply(videoId, current.threadId, current.threadId),
                    )
                    CommentMenuAction.Report -> navigation.trySend(
                        CommentNavigationRequest.ReportComment(videoId, current.threadId, current.threadId),
                    )
                }
            }
            is CommentsOverlay.ReplyMenu -> {
                overlay(CommentsOverlay.None)
                when (action) {
                    CommentMenuAction.Reply -> navigation.trySend(
                        CommentNavigationRequest.Reply(videoId, current.threadId, current.replyId),
                    )
                    CommentMenuAction.Report -> navigation.trySend(
                        CommentNavigationRequest.ReportComment(videoId, current.threadId, current.replyId),
                    )
                }
            }
            else -> Unit
        }
    }

    private fun overlay(next: CommentsOverlay) {
        _state.update { it.copy(overlay = next) }
        persistOverlay(next)
    }

    private fun persistOverlay(overlay: CommentsOverlay) {
        savedState[KEY_OVERLAY] = when (overlay) {
            CommentsOverlay.SortMenu -> "sort"
            is CommentsOverlay.ThreadMenu -> {
                savedState[KEY_THREAD] = overlay.threadId
                "thread"
            }
            is CommentsOverlay.ReplyMenu -> {
                savedState[KEY_THREAD] = overlay.threadId
                savedState[KEY_REPLY] = overlay.replyId
                "reply"
            }
            CommentsOverlay.None -> {
                savedState.remove<String>(KEY_THREAD)
                savedState.remove<String>(KEY_REPLY)
                "none"
            }
        }
    }

    private fun selectSort(next: CommentSort) {
        if (next == _state.value.sort) return
        savedState[KEY_SORT] = next.name
        savedState[KEY_LOADED] = 0
        _state.update { it.copy(sort = next) }
        load()
    }

    private fun toggleReplies(commentId: String) {
        val current = _state.value.expanded
        val next = if (commentId in current) current - commentId else current + commentId
        savedState[KEY_EXPANDED] = ArrayList(next)
        _state.update { it.copy(expanded = next) }
    }

    private fun load() {
        loadJob?.cancel()
        pageJob?.cancel()
        val requestGeneration = ++generation
        val requestedSort = _state.value.sort
        val targetCount = savedState.get<Int>(KEY_LOADED) ?: 0
        _state.update { it.copy(content = CommentsContent.Loading) }
        loadJob = viewModelScope.launch {
            var threads = emptyList<CommentThread>()
            var cursor: String? = null
            var total = ""
            val page = try {
                var page = repository.comments(videoId, requestedSort, cursor = null)
                total = page.total
                threads = page.threads
                cursor = page.nextCursor
                val visited = mutableSetOf<String>()
                while (!page.turnedOff && threads.size < targetCount && cursor != null && visited.add(cursor)) {
                    page = repository.comments(videoId, requestedSort, cursor)
                    threads = (threads + page.threads).distinctBy { it.id }
                    cursor = page.nextCursor
                }
                page
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(total = total, content = if (threads.isEmpty()) CommentsContent.Failed
                    else CommentsContent.Ready(threads, cursor, loadMoreFailed = true))
                }
                return@launch
            }
            if (requestGeneration != generation) return@launch
            val content = when {
                page.turnedOff -> CommentsContent.Disabled
                threads.isEmpty() -> CommentsContent.Empty
                else -> CommentsContent.Ready(threads, cursor)
            }
            savedState[KEY_LOADED] = threads.size
            _state.update { it.copy(content = content, total = if (content == CommentsContent.Empty) "0" else total) }
        }
    }

    private fun loadMore() {
        val ready = _state.value.content as? CommentsContent.Ready ?: return
        if (ready.loadingMore || ready.nextCursor == null) return
        val requestGeneration = generation
        val requestedSort = _state.value.sort
        _state.update { it.copy(content = ready.copy(loadingMore = true, loadMoreFailed = false)) }
        pageJob = viewModelScope.launch {
            val page = try {
                repository.comments(videoId, requestedSort, ready.nextCursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration != generation) return@launch
                _state.update { state ->
                    val current = state.content as? CommentsContent.Ready ?: return@update state
                    state.copy(content = current.copy(loadingMore = false, loadMoreFailed = true))
                }
                return@launch
            }
            if (requestGeneration != generation) return@launch
            _state.update { state ->
                val current = state.content as? CommentsContent.Ready ?: return@update state
                val threads = (current.threads + page.threads).distinctBy { it.id }
                savedState[KEY_LOADED] = threads.size
                state.copy(
                    total = page.total,
                    content = current.copy(
                        threads = threads,
                        nextCursor = page.nextCursor.takeUnless { it == ready.nextCursor },
                        loadingMore = false,
                        loadMoreFailed = false,
                    ),
                )
            }
        }
    }

    override fun onCleared() { navigation.close() }

    private companion object {
        const val KEY_VIDEO = "commentsVideoId"
        const val KEY_SORT = "commentsSort"
        const val KEY_EXPANDED = "commentsExpanded"
        const val KEY_LOADED = "commentsLoadedCount"
        const val KEY_OVERLAY = "commentsOverlay"
        const val KEY_THREAD = "commentsMenuThread"
        const val KEY_REPLY = "commentsMenuReply"
    }
}
