package com.hhst.youtubelite.comments.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.comments.domain.CommentActions
import com.hhst.youtubelite.comments.domain.CommentTarget
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
import com.hhst.youtubelite.core.AppNotice

/**
 * Panel state for one video. Sorting and expanded threads survive recreation through
 * [SavedStateHandle]; the repository supplies the visible list.
 */
class CommentsViewModel(
    private val repository: CommentsRepository,
    private val savedState: SavedStateHandle,
    initialTarget: CommentTarget,
) : ViewModel() {
    constructor(repository: CommentsRepository, savedState: SavedStateHandle, videoId: String) :
        this(repository, savedState, CommentTarget.Video(videoId))
    private var target: CommentTarget = when (savedState.get<String>("commentsTargetKind")) {
        "post" -> CommentTarget.Post(com.hhst.youtubelite.post.domain.PostIdentity(
            savedState.get<String>("commentsTargetChannel").orEmpty(), savedState.get<String>("commentsTargetId").orEmpty()))
        "video" -> CommentTarget.Video(savedState.get<String>("commentsTargetId").orEmpty())
        else -> savedState.get<String>(KEY_VIDEO)?.let { CommentTarget.Video(it) } ?: initialTarget
    }
    private val sort = runCatching {
        CommentSort.valueOf(savedState.get<String>(KEY_SORT) ?: CommentSort.Top.name)
    }.getOrDefault(CommentSort.Top)
    private val expanded = savedState.get<ArrayList<String>>(KEY_EXPANDED)?.toSet() ?: emptySet()
    private var videoId = (target as? CommentTarget.Video)?.id.orEmpty()
    private val restoredOverlay: CommentsOverlay = when (savedState.get<String>(KEY_OVERLAY)) {
        "sort" -> CommentsOverlay.SortMenu
        "thread" -> savedState.get<String>(KEY_THREAD)?.let { CommentsOverlay.ThreadMenu(it) }
        "reply" -> {
            val thread = savedState.get<String>(KEY_THREAD)
            val reply = savedState.get<String>(KEY_REPLY)
            if (thread != null && reply != null) CommentsOverlay.ReplyMenu(thread, reply) else null
        }
        "composer" -> CommentsOverlay.Composer(
            threadId = savedState.get<String>(KEY_THREAD),
            replyId = savedState.get<String>(KEY_REPLY),
            editing = savedState.get<Boolean>(KEY_EDITING) == true,
        )
        "delete" -> savedState.get<String>(KEY_THREAD)?.let {
            CommentsOverlay.DeleteConfirm(it, savedState.get<String>(KEY_REPLY))
        }
        else -> null
    } ?: CommentsOverlay.None
    private val draft = savedState.get<String>(KEY_DRAFT).orEmpty()

    private val _state = MutableStateFlow(
        CommentsUiState(
            videoId = this.videoId, target = target, sort = sort, expanded = expanded,
            overlay = restoredOverlay,
            draft = draft.takeIf { restoredOverlay is CommentsOverlay.Composer }.orEmpty(),
            supportedMenuActions = (repository as? CommentActions)?.supportedMenuActions ?: CommentMenuAction.entries.toSet(),
        ),
    )
    val uiState = _state.asStateFlow()

    private val navigation = Channel<CommentNavigationRequest>(Channel.BUFFERED)
    val navigationRequests = navigation.receiveAsFlow()

    private val notices = Channel<AppNotice>(Channel.BUFFERED)
    val actionNotices = notices.receiveAsFlow()
    private var actionJob: Job? = null
    private val actions = repository as? CommentActions

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0
    private val replyJobs = mutableMapOf<String, Job>()

    init {
        when (val content = this.target) {
            is CommentTarget.Video -> {
                savedState["commentsTargetKind"] = "video"
                savedState["commentsTargetId"] = content.id
            }
            is CommentTarget.Post -> {
                savedState["commentsTargetKind"] = "post"
                savedState["commentsTargetId"] = content.identity.postId
                savedState["commentsTargetChannel"] = content.identity.channelId
            }
        }
        savedState[KEY_VIDEO] = this.videoId
        savedState[KEY_SORT] = sort.name
        savedState[KEY_EXPANDED] = ArrayList(expanded)
        persistOverlay(restoredOverlay)
        load()
    }

    fun selectVideo(id: String) {
        if (target == CommentTarget.Video(id)) return
        actionJob?.cancel()
        target = CommentTarget.Video(id)
        videoId = id
        savedState["commentsTargetKind"] = "video"
        savedState["commentsTargetId"] = id
        savedState[KEY_VIDEO] = id
        savedState[KEY_EXPANDED] = arrayListOf<String>()
        savedState[KEY_SORT] = CommentSort.Top.name
        savedState[KEY_DRAFT] = ""
        _state.value = CommentsUiState(videoId = id, target = target,
            supportedMenuActions = actions?.supportedMenuActions ?: CommentMenuAction.entries.toSet())
        persistOverlay(CommentsOverlay.None)
        load()
    }

    fun refreshSession() {
        actionJob?.cancel()
        _state.update { it.copy(actionPending = false) }
        load()
    }

    fun onAction(action: CommentAction) {
        if (_state.value.actionPending && action != CommentAction.Retry) return
        when (action) {
            CommentAction.Retry -> load()
            CommentAction.LoadMore -> loadMore()
            is CommentAction.SelectSort -> {
                overlay(CommentsOverlay.None)
                selectSort(action.sort)
            }
            CommentAction.OpenSortMenu -> overlay(CommentsOverlay.SortMenu)
            CommentAction.CloseMenu -> when (_state.value.overlay) {
                is CommentsOverlay.Composer -> closeComposer()
                is CommentsOverlay.DeleteConfirm -> overlay(CommentsOverlay.None)
                else -> overlay(CommentsOverlay.None)
            }
            is CommentAction.MenuAction -> selectMenuAction(action.action)
            is CommentAction.ToggleReplies -> toggleReplies(action.commentId)
            CommentAction.Compose -> openComposer(null, null, editing = false)
            is CommentAction.DraftChanged -> {
                if (_state.value.overlay !is CommentsOverlay.Composer || _state.value.actionPending) return
                savedState[KEY_DRAFT] = action.text
                _state.update { it.copy(draft = action.text) }
            }
            CommentAction.SubmitDraft -> submitDraft()
            CommentAction.DismissComposer -> closeComposer()
            is CommentAction.RequestDelete -> {
                if (!owned(action.threadId, action.commentId)) return
                overlay(CommentsOverlay.DeleteConfirm(
                    action.threadId, action.commentId.takeUnless { it == action.threadId },
                ))
            }
            CommentAction.ConfirmDelete -> confirmDelete()
            CommentAction.CancelDelete -> overlay(CommentsOverlay.None)
            is CommentAction.LikeThread -> dispatch(CommentNavigationRequest.LikeComment(target, action.commentId))
            is CommentAction.DislikeThread -> dispatch(CommentNavigationRequest.DislikeComment(target, action.commentId))
            is CommentAction.ReportThread -> dispatch(
                CommentNavigationRequest.ReportComment(target, action.commentId, action.commentId),
            )
            is CommentAction.OpenThreadMenu -> {
                overlay(CommentsOverlay.ThreadMenu(action.commentId))
                navigation.trySend(CommentNavigationRequest.OpenCommentMenu(target, action.commentId))
            }
            is CommentAction.OpenAuthor -> navigation.trySend(CommentNavigationRequest.OpenAuthor(action.channelId))
            is CommentAction.Reply -> openComposer(
                action.threadId,
                action.commentId.takeUnless { it == action.threadId },
                editing = false,
            )
            is CommentAction.LikeReply -> dispatch(CommentNavigationRequest.LikeComment(target, action.replyId))
            is CommentAction.DislikeReply -> dispatch(CommentNavigationRequest.DislikeComment(target, action.replyId))
            is CommentAction.ReportReply -> dispatch(
                CommentNavigationRequest.ReportComment(target, action.commentId, action.replyId),
            )
            is CommentAction.OpenReplyMenu -> {
                overlay(CommentsOverlay.ReplyMenu(action.commentId, action.replyId))
                navigation.trySend(CommentNavigationRequest.OpenReplyMenu(target, action.commentId, action.replyId))
            }
        }
    }

    /**
     * Thread and reply menus share their public rows; owner-only rows open a local
     * composer or confirmation and never mutate the fixture. Every submission leaves
     * as a typed request carrying the concrete comment identity.
     */
    private fun selectMenuAction(action: CommentMenuAction) {
        val current = uiState.value.overlay
        val threadId: String
        val replyId: String?
        val owned: Boolean
        val body: String
        when (current) {
            is CommentsOverlay.ThreadMenu -> {
                val thread = findThread(current.threadId) ?: return
                threadId = thread.id
                replyId = null
                owned = thread.ownedByViewer
                body = thread.body
            }
            is CommentsOverlay.ReplyMenu -> {
                val thread = findThread(current.threadId) ?: return
                val reply = thread.replies.firstOrNull { it.id == current.replyId } ?: return
                threadId = thread.id
                replyId = reply.id
                owned = reply.ownedByViewer
                body = reply.body
            }
            else -> return
        }
        if (action.ownerOnly && !owned) return
        when (action) {
            CommentMenuAction.Reply -> openComposer(threadId, replyId, editing = false)
            CommentMenuAction.Report -> {
                overlay(CommentsOverlay.None)
                dispatch(
                    CommentNavigationRequest.ReportComment(target, threadId, replyId ?: threadId),
                )
            }
            CommentMenuAction.Edit -> openComposer(threadId, replyId, editing = true, initial = body)
            CommentMenuAction.Delete -> {
                overlay(CommentsOverlay.None)
                overlay(CommentsOverlay.DeleteConfirm(threadId, replyId))
            }
        }
    }

    private fun findThread(threadId: String): CommentThread? =
        (_state.value.content as? CommentsContent.Ready)?.threads?.firstOrNull { it.id == threadId }

    private fun owned(threadId: String, commentId: String): Boolean {
        val thread = findThread(threadId) ?: return false
        if (commentId == threadId) return thread.ownedByViewer
        return thread.replies.firstOrNull { it.id == commentId }?.ownedByViewer == true
    }

    private fun openComposer(
        threadId: String?,
        replyId: String?,
        editing: Boolean,
        initial: String = "",
    ) {
        savedState[KEY_DRAFT] = initial
        _state.update { it.copy(draft = initial) }
        overlay(CommentsOverlay.Composer(threadId, replyId, editing))
    }

    private fun closeComposer() {
        if (_state.value.actionPending) return
        savedState[KEY_DRAFT] = ""
        _state.update { it.copy(draft = "") }
        overlay(CommentsOverlay.None)
    }

    private fun submitDraft() {
        val composer = _state.value.overlay as? CommentsOverlay.Composer ?: return
        val body = _state.value.draft.trim()
        if (body.isEmpty()) return
        val threadId = composer.threadId
        val commentId = composer.replyId ?: threadId
        if (threadId == null || commentId == null) {
            dispatch(CommentNavigationRequest.SubmitComment(target, body))
        } else if (composer.editing) {
            dispatch(CommentNavigationRequest.EditComment(target, threadId, commentId, body))
        } else {
            dispatch(CommentNavigationRequest.SubmitReply(target, threadId, commentId, body))
        }
        if (actions == null) closeComposer()
    }

    private fun confirmDelete() {
        val confirm = _state.value.overlay as? CommentsOverlay.DeleteConfirm ?: return
        dispatch(
            CommentNavigationRequest.DeleteComment(
                target, confirm.threadId, confirm.replyId ?: confirm.threadId,
            ),
        )
        if (actions == null) overlay(CommentsOverlay.None)
    }

    private fun dispatch(request: CommentNavigationRequest) {
        val service = actions ?: run { navigation.trySend(request); return }
        if (_state.value.actionPending) return
        val requestedTarget = target
        val closesEditor = request is CommentNavigationRequest.SubmitComment ||
            request is CommentNavigationRequest.SubmitReply || request is CommentNavigationRequest.EditComment ||
            request is CommentNavigationRequest.DeleteComment
        _state.update { it.copy(actionPending = true) }
        actionJob = viewModelScope.launch {
            try {
                service.perform(request)
                if (target != requestedTarget) return@launch
                _state.update { it.copy(actionPending = false) }
                if (closesEditor) closeComposer()
                load()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.hhst.youtubelite.watch.domain.SignInRequiredException) { notices.send(AppNotice.SignIn) }
            catch (_: UnsupportedOperationException) { notices.send(AppNotice.Message("This action is not available.")) }
            catch (_: Exception) { notices.send(AppNotice.Message("Could not confirm the change. Please refresh and try again.")) }
            finally { if (target == requestedTarget) _state.update { it.copy(actionPending = false) } }
        }
    }

    private fun overlay(next: CommentsOverlay) {
        _state.update { it.copy(overlay = next) }
        persistOverlay(next)
    }

    private fun persistOverlay(overlay: CommentsOverlay) {
        when (overlay) {
            CommentsOverlay.SortMenu -> {
                savedState[KEY_OVERLAY] = "sort"
                clearOverlayIds()
            }
            is CommentsOverlay.ThreadMenu -> {
                savedState[KEY_OVERLAY] = "thread"
                savedState[KEY_THREAD] = overlay.threadId
                savedState.remove<String>(KEY_REPLY)
            }
            is CommentsOverlay.ReplyMenu -> {
                savedState[KEY_OVERLAY] = "reply"
                savedState[KEY_THREAD] = overlay.threadId
                savedState[KEY_REPLY] = overlay.replyId
            }
            is CommentsOverlay.Composer -> {
                savedState[KEY_OVERLAY] = "composer"
                if (overlay.threadId == null) savedState.remove<String>(KEY_THREAD)
                else savedState[KEY_THREAD] = overlay.threadId
                if (overlay.replyId == null) savedState.remove<String>(KEY_REPLY)
                else savedState[KEY_REPLY] = overlay.replyId
                savedState[KEY_EDITING] = overlay.editing
            }
            is CommentsOverlay.DeleteConfirm -> {
                savedState[KEY_OVERLAY] = "delete"
                savedState[KEY_THREAD] = overlay.threadId
                if (overlay.replyId == null) savedState.remove<String>(KEY_REPLY)
                else savedState[KEY_REPLY] = overlay.replyId
            }
            CommentsOverlay.None -> {
                savedState[KEY_OVERLAY] = "none"
                clearOverlayIds()
            }
        }
    }

    private fun clearOverlayIds() {
        savedState.remove<String>(KEY_THREAD)
        savedState.remove<String>(KEY_REPLY)
        savedState.remove<Boolean>(KEY_EDITING)
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
        val next = if (commentId in current && findThread(commentId)?.repliesFailed != true) current - commentId else current + commentId
        savedState[KEY_EXPANDED] = ArrayList(next)
        _state.update { it.copy(expanded = next) }
        requestReplies(commentId, next)
    }

    private fun requestReplies(commentId: String, next: Set<String> = _state.value.expanded) {
        val thread = findThread(commentId) ?: return
        if (commentId !in next || thread.replyKey == null || thread.replies.isNotEmpty() || replyJobs[commentId]?.isActive == true) return
        val requestGeneration = generation
        updateThread(commentId) { it.copy(repliesLoading = true, repliesFailed = false) }
        replyJobs[commentId] = viewModelScope.launch {
            try {
                val replies = repository.replies(target, thread)
                if (requestGeneration == generation) updateThread(commentId) { it.copy(replies = replies, repliesLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (requestGeneration == generation) updateThread(commentId) { it.copy(repliesLoading = false, repliesFailed = true) }
            }
        }
    }

    private fun updateThread(id: String, transform: (CommentThread) -> CommentThread) {
        _state.update { state ->
            val content = state.content as? CommentsContent.Ready ?: return@update state
            state.copy(content = content.copy(threads = content.threads.map { if (it.id == id) transform(it) else it }))
        }
    }

    private fun load() {
        replyJobs.values.forEach(Job::cancel)
        replyJobs.clear()
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
                var page = repository.comments(target, requestedSort, cursor = null)
                total = page.total
                threads = page.threads
                cursor = page.nextCursor
                val visited = mutableSetOf<String>()
                while (!page.turnedOff && threads.size < targetCount && cursor != null && visited.add(cursor)) {
                    page = repository.comments(target, requestedSort, cursor)
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
            if (page.sortFallback && requestedSort == CommentSort.Newest) {
                savedState[KEY_SORT] = CommentSort.Top.name
                _state.update { it.copy(sort = CommentSort.Top) }
                notices.send(AppNotice.Message("Newest comments aren't offered here. Showing top comments."))
            }
            val content = when {
                page.turnedOff -> CommentsContent.Disabled
                threads.isEmpty() -> CommentsContent.Empty
                else -> CommentsContent.Ready(threads, cursor)
            }
            savedState[KEY_LOADED] = threads.size
            _state.update { it.copy(content = content, total = if (content == CommentsContent.Empty) "0" else total) }
            _state.value.expanded.forEach { requestReplies(it) }
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
                repository.comments(target, requestedSort, ready.nextCursor)
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
                    total = page.total.ifBlank { state.total },
                    content = current.copy(
                        threads = threads,
                        nextCursor = page.nextCursor.takeUnless { it == ready.nextCursor },
                        loadingMore = false,
                        loadMoreFailed = false,
                    ),
                )
            }
            _state.value.expanded.forEach { requestReplies(it) }
        }
    }

    override fun onCleared() { navigation.close(); notices.close() }

    private companion object {
        const val KEY_VIDEO = "commentsVideoId"
        const val KEY_SORT = "commentsSort"
        const val KEY_EXPANDED = "commentsExpanded"
        const val KEY_LOADED = "commentsLoadedCount"
        const val KEY_OVERLAY = "commentsOverlay"
        const val KEY_THREAD = "commentsMenuThread"
        const val KEY_REPLY = "commentsMenuReply"
        const val KEY_EDITING = "commentsEditing"
        const val KEY_DRAFT = "commentsDraft"
    }
}
