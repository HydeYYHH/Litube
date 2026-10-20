package com.hhst.youtubelite.comments.domain

/** A fixed local comment thread. All content is packaged with the app. */
data class CommentThread(
    val id: String,
    val author: CommentAuthor,
    val age: String,
    val body: String,
    val likes: String,
    val replyCount: Int,
    val replies: List<CommentReply> = emptyList(),
    val authorChannelId: String = "",
)

data class CommentAuthor(val displayName: String, val handle: String, val artwork: String)

data class CommentReply(
    val id: String,
    val author: CommentAuthor,
    val age: String,
    val body: String,
    val likes: String,
    val authorChannelId: String = "",
)

data class CommentsPage(
    val total: String,
    val threads: List<CommentThread>,
    val nextCursor: String?,
    val turnedOff: Boolean = false,
)

interface CommentsRepository {
    suspend fun comments(videoId: String, sort: CommentSort, cursor: String?): CommentsPage
}

enum class CommentSort { Top, Newest }

/** The panel's visible content, including its pagination sub-state. */
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
)

/**
 * One panel overlay at a time. Menus keep the panel, its expansion set and its scroll
 * position alive underneath.
 */
sealed interface CommentsOverlay {
    data object None : CommentsOverlay
    data object SortMenu : CommentsOverlay
    data class ThreadMenu(val threadId: String) : CommentsOverlay
    data class ReplyMenu(val threadId: String, val replyId: String) : CommentsOverlay
}

/** Public comment actions only; owner-only edit/delete are never offered for anonymous samples. */
enum class CommentMenuAction(val label: String) { Reply("Reply"), Report("Report") }

/**
 * Typed boundaries for interactions this screen does not implement. The host records
 * them; nothing is simulated as a successful write.
 */
sealed interface CommentNavigationRequest {
    data class Compose(val videoId: String) : CommentNavigationRequest
    data class LikeComment(val videoId: String, val commentId: String) : CommentNavigationRequest
    data class DislikeComment(val videoId: String, val commentId: String) : CommentNavigationRequest
    data class ReportComment(val videoId: String, val threadId: String, val commentId: String) : CommentNavigationRequest
    data class OpenCommentMenu(val videoId: String, val threadId: String) : CommentNavigationRequest
    data class OpenReplyMenu(val videoId: String, val threadId: String, val replyId: String) : CommentNavigationRequest
    data class Reply(val videoId: String, val threadId: String, val commentId: String) : CommentNavigationRequest
    data class OpenAuthor(val channelId: String) : CommentNavigationRequest
}

sealed interface CommentAction {
    data object Retry : CommentAction
    data object LoadMore : CommentAction
    data class SelectSort(val sort: CommentSort) : CommentAction
    data object OpenSortMenu : CommentAction
    data object CloseMenu : CommentAction
    data class MenuAction(val action: CommentMenuAction) : CommentAction
    data class ToggleReplies(val commentId: String) : CommentAction
    data object Compose : CommentAction
    data class LikeThread(val commentId: String) : CommentAction
    data class DislikeThread(val commentId: String) : CommentAction
    data class ReportThread(val commentId: String) : CommentAction
    data class OpenThreadMenu(val commentId: String) : CommentAction
    data class Reply(val threadId: String, val commentId: String = threadId) : CommentAction
    data class OpenAuthor(val channelId: String) : CommentAction
    data class LikeReply(val commentId: String, val replyId: String) : CommentAction
    data class DislikeReply(val commentId: String, val replyId: String) : CommentAction
    data class ReportReply(val commentId: String, val replyId: String) : CommentAction
    data class OpenReplyMenu(val commentId: String, val replyId: String) : CommentAction
}

/** Layout metrics for the comments panel. */
object CommentsLayout {
    const val SheetRadius = 12f
    const val HandleStripHeight = 12f
    const val HandleWidth = 40f
    const val HandleHeight = 4f

    /** The panel header block (title row) sits 12px below the panel top. */
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

    /** Header and simplebox separators are 1px at 20% black. */
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

    /** Bottom-sheet top: below the pinned player, clamped so the sheet stays usable. */
    fun sheetTop(topBar: Float, playerHeight: Float, windowHeight: Float): Float =
        minOf(topBar + playerHeight, (windowHeight - MinPanelHeight).coerceAtLeast(0f))
}
