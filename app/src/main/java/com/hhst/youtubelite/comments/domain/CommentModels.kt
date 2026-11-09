package com.hhst.youtubelite.comments.domain

import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.watch.domain.VideoRating

data class CommentThread(
    val id: String,
    val author: CommentAuthor,
    val age: String,
    val body: String,
    val likes: String,
    val replyCount: Int,
    val replies: List<CommentReply> = emptyList(),
    val authorChannelId: String = "",
    val ownedByViewer: Boolean = false,
    val replyKey: String? = null,
    val repliesNextCursor: String? = null,
    val repliesLoading: Boolean = false,
    val repliesFailed: Boolean = false,
    val runs: List<CommentTextRun> = emptyList(),
    val rating: VideoRating = VideoRating.None,
)

data class CommentAuthor(val displayName: String, val handle: String, val artwork: String)

data class CommentReply(
    val id: String,
    val author: CommentAuthor,
    val age: String,
    val body: String,
    val likes: String,
    val authorChannelId: String = "",
    val ownedByViewer: Boolean = false,
    val runs: List<CommentTextRun> = emptyList(),
    val rating: VideoRating = VideoRating.None,
)

data class CommentsPage(
    val total: String,
    val threads: List<CommentThread>,
    val nextCursor: String?,
    val turnedOff: Boolean = false,
    /** True when Newest was requested but the server offered no such lane, so Top is served instead. */
    val sortFallback: Boolean = false,
)

data class RepliesPage(val replies: List<CommentReply>, val nextCursor: String?)

sealed interface CommentTarget {
    data class Video(val id: String) : CommentTarget
    data class Post(val identity: PostIdentity) : CommentTarget
}

interface CommentsRepository {
    /** One replies page for [thread]; [cursor] is null for the first page, the thread's `repliesNextCursor` afterwards. */
    suspend fun repliesPage(target: CommentTarget, thread: CommentThread, cursor: String?): RepliesPage =
        RepliesPage(if (cursor == null) thread.replies else emptyList(), null)
    suspend fun comments(target: CommentTarget, sort: CommentSort, cursor: String?): CommentsPage
    suspend fun comments(videoId: String, sort: CommentSort, cursor: String?): CommentsPage =
        comments(CommentTarget.Video(videoId), sort, cursor)
}

enum class CommentSort { Top, Newest }

sealed interface CommentsContent {
    data object Loading : CommentsContent
    data class Ready(
        val threads: List<CommentThread>,
        val nextCursor: String? = null,
        val loadingMore: Boolean = false,
        val loadMoreFailed: Boolean = false,
    ) : CommentsContent
    data object Empty : CommentsContent
    data object Disabled : CommentsContent
    data object Failed : CommentsContent
}

data class CommentsUiState(
    val videoId: String = "",
    val sort: CommentSort = CommentSort.Top,
    val content: CommentsContent = CommentsContent.Loading,
    val expanded: Set<String> = emptySet(),
    val total: String = "",
    val overlay: CommentsOverlay = CommentsOverlay.None,
    val draft: String = "",
    val actionPending: Boolean = false,
    val refreshing: Boolean = false,
    val pendingAction: CommentNavigationRequest? = null,
    val target: CommentTarget = CommentTarget.Video(videoId),
    val supportedMenuActions: Set<CommentMenuAction> = CommentMenuAction.entries.toSet(),
)

/** One overlay at a time; menus keep the panel, its expansion set and its scroll position alive underneath. */
sealed interface CommentsOverlay {
    data object None : CommentsOverlay
    data object SortMenu : CommentsOverlay
    data class ThreadMenu(val threadId: String) : CommentsOverlay
    data class ReplyMenu(val threadId: String, val replyId: String) : CommentsOverlay

    /** `replyId` is null for a new thread and for the thread's own reply action target. */
    data class Composer(
        val threadId: String? = null,
        val replyId: String? = null,
        val editing: Boolean = false,
    ) : CommentsOverlay

    data class DeleteConfirm(val threadId: String, val replyId: String? = null) : CommentsOverlay
}

enum class CommentMenuAction(val label: String, val ownerOnly: Boolean = false) {
    Reply("Reply"),
    Report("Report"),
    Edit("Edit", ownerOnly = true),
    Delete("Delete", ownerOnly = true);

    companion object {
        fun forComment(ownedByViewer: Boolean): List<CommentMenuAction> =
            entries.filter { !it.ownerOnly || ownedByViewer }
    }
}

/**
 * Typed boundaries for interactions this screen does not implement. The host records
 * them; nothing is simulated as a successful write.
 */
sealed interface CommentNavigationRequest {
    val target: CommentTarget? get() = null
    val videoId: String get() = (target as? CommentTarget.Video)?.id.orEmpty()
    data class SubmitComment(override val target: CommentTarget, val body: String) : CommentNavigationRequest {
        constructor(videoId: String, body: String) : this(CommentTarget.Video(videoId), body)
    }
    data class SubmitReply(override val target: CommentTarget, val threadId: String, val commentId: String, val body: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String, commentId: String, body: String) : this(CommentTarget.Video(videoId), threadId, commentId, body)
    }
    data class EditComment(override val target: CommentTarget, val threadId: String, val commentId: String, val body: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String, commentId: String, body: String) : this(CommentTarget.Video(videoId), threadId, commentId, body)
    }
    data class DeleteComment(override val target: CommentTarget, val threadId: String, val commentId: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String, commentId: String) : this(CommentTarget.Video(videoId), threadId, commentId)
    }
    data class LikeComment(override val target: CommentTarget, val commentId: String,
        val rating: VideoRating, val previous: VideoRating) : CommentNavigationRequest {
        constructor(videoId: String, commentId: String, rating: VideoRating, previous: VideoRating) :
            this(CommentTarget.Video(videoId), commentId, rating, previous)
    }
    data class DislikeComment(override val target: CommentTarget, val commentId: String,
        val rating: VideoRating, val previous: VideoRating) : CommentNavigationRequest {
        constructor(videoId: String, commentId: String, rating: VideoRating, previous: VideoRating) :
            this(CommentTarget.Video(videoId), commentId, rating, previous)
    }
    data class ReportComment(override val target: CommentTarget, val threadId: String, val commentId: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String, commentId: String) : this(CommentTarget.Video(videoId), threadId, commentId)
    }
    data class OpenCommentMenu(override val target: CommentTarget, val threadId: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String) : this(CommentTarget.Video(videoId), threadId)
    }
    data class OpenReplyMenu(override val target: CommentTarget, val threadId: String, val replyId: String) : CommentNavigationRequest {
        constructor(videoId: String, threadId: String, replyId: String) : this(CommentTarget.Video(videoId), threadId, replyId)
    }
    data class OpenAuthor(val channelId: String) : CommentNavigationRequest
    data class OpenLink(val link: CommentLink) : CommentNavigationRequest
}

sealed interface CommentAction {
    data object Retry : CommentAction
    data object LoadMore : CommentAction
    data class SelectSort(val sort: CommentSort) : CommentAction
    data object OpenSortMenu : CommentAction
    data object CloseMenu : CommentAction
    data class MenuAction(val action: CommentMenuAction) : CommentAction
    data class ToggleReplies(val commentId: String) : CommentAction
    data class LoadMoreReplies(val commentId: String) : CommentAction
    data object Compose : CommentAction
    data class DraftChanged(val text: String) : CommentAction
    data object SubmitDraft : CommentAction
    data object DismissComposer : CommentAction
    data class RequestDelete(val threadId: String, val commentId: String = threadId) : CommentAction
    data object ConfirmDelete : CommentAction
    data object CancelDelete : CommentAction
    data class LikeThread(val commentId: String) : CommentAction
    data class DislikeThread(val commentId: String) : CommentAction
    data class ReportThread(val commentId: String) : CommentAction
    data class OpenThreadMenu(val commentId: String) : CommentAction
    data class Reply(val threadId: String, val commentId: String = threadId) : CommentAction
    data class OpenAuthor(val channelId: String) : CommentAction
    data class OpenLink(val link: CommentLink) : CommentAction
    data class LikeReply(val commentId: String, val replyId: String) : CommentAction
    data class DislikeReply(val commentId: String, val replyId: String) : CommentAction
    data class ReportReply(val commentId: String, val replyId: String) : CommentAction
    data class OpenReplyMenu(val commentId: String, val replyId: String) : CommentAction
}

object CommentsLayout {
    const val SheetRadius = 12f
    const val HandleStripHeight = 12f
    const val HandleWidth = 40f
    const val HandleHeight = 4f

    const val HeaderTopGap = 12f
    const val HeaderHeight = 65f
    const val HeaderPaddingStart = 12f
    const val TitleCountGap = 8f

    const val TitleSizeNarrow = 18f
    const val TitleLineNarrow = 26f
    const val TitleSizeWide = 20f
    const val TitleLineWide = 28f
    const val CountSize = 14f
    const val CountLine = 18f

    const val CloseTarget = 48f
    const val CloseIcon = 24f
    const val CloseEndInset = 8f

    const val DividerAlpha = 0.2f

    const val SimpleboxHeight = 88f
    const val AvatarLarge = 48f
    const val AvatarGap = 8f
    const val InputHeight = 56f
    const val InputRadius = 12f
    const val InputEndInset = 12f
    const val InputTextInset = 10f

    const val CommentStartInset = 12f
    const val CommentEndInset = 12f
    const val ThreadTopPad = 16f
    const val AuthorTopPad = 4f
    const val AuthorSize = 12f
    const val AuthorLine = 14f
    const val AgeSize = 13f
    const val AgeLine = 15f
    const val AuthorBodyGap = 5f
    const val BodySize = 14f
    const val BodyLine = 17.5f
    const val BodyActionsGap = 10f

    const val ActionRowHeight = 40f
    const val ActionIcon = 16f
    const val ActionCountSize = 11f
    const val ActionCountGap = 4f
    const val ActionGroupGap = 11f
    const val ActionReplyGap = 16f
    const val ActionRowOffset = 1f
    const val KebabTarget = 40f
    const val KebabIcon = 24f
    const val KebabEndInset = 20f

    const val ReplyToggleHeight = 40f
    const val ReplyToggleStart = 62f
    const val ReplyToggleTopGap = 16f
    const val ThreadBottomGap = 16f
    const val ReplyAvatar = 26f
    const val ReplyContentStart = 96f
    const val ReplyBodyWidth = 274f
    const val ReplyRowGap = 8f

    /** Below this panel width the header uses the 18px title; the 500px rail uses 20px. */
    const val WideTitleWidth = 480f

    const val MinPanelHeight = 240f

    fun titleSize(panelWidth: Float, docked: Boolean = false): Float =
        if (docked || panelWidth >= WideTitleWidth) TitleSizeWide else TitleSizeNarrow

    fun titleLine(panelWidth: Float, docked: Boolean = false): Float =
        if (docked || panelWidth >= WideTitleWidth) TitleLineWide else TitleLineNarrow

    fun sheetTop(topBar: Float, playerHeight: Float, windowHeight: Float): Float =
        minOf(topBar + playerHeight, (windowHeight - MinPanelHeight).coerceAtLeast(0f))
}
