package com.hhst.youtubelite.post.domain

import com.hhst.youtubelite.watch.domain.VideoRating

/** Stable identity for a community post. A post is never addressed by a list index. */
data class PostIdentity(
    val channelId: String,
    val postId: String,
)

data class PostAuthor(
    val channelId: String,
    val name: String,
    val avatar: String,
)

data class PostSummary(
    val identity: PostIdentity,
    val author: PostAuthor,
    val age: String,
    val text: String,
    val images: List<String> = emptyList(),
    val likes: String,
    val comments: String,
    val poll: PostPollData? = null,
)

data class PostDetail(
    val identity: PostIdentity,
    val author: PostAuthor,
    val age: String,
    val text: String,
    val images: List<String> = emptyList(),
    val likes: String,
    val comments: String,
    val rating: VideoRating = VideoRating.None,
    val poll: PostPollData? = null,
)

data class PostPollOption(val text: String, val image: String? = null, val percentage: String? = null, val selected: Boolean = false)
data class PostPollData(val options: List<PostPollOption>, val total: String = "", val voted: Boolean = false)

interface PostActions {
    /** The caller's displayed state determines the intent, independently of capability reads. */
    suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail
    suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail
}

class PostAuthorizationRequiredException : Exception("post_web_authorization_required")

interface PostRepository {
    suspend fun post(identity: PostIdentity): PostDetail?

    suspend fun channelPosts(channelId: String): ChannelPostsPage

    /** Further posts after [cursor]; finite lists return an empty page. */
    suspend fun channelPostsNext(channelId: String, cursor: String): ChannelPostsPage = ChannelPostsPage(emptyList())
}

data class ChannelPostsPage(val posts: List<PostSummary>, val nextKey: String? = null)

enum class PostMenuAction(val label: String) {
    Report("Report"),
}

sealed interface PostDetailContent {
    data object Loading : PostDetailContent
    data class Ready(val post: PostDetail) : PostDetailContent
    data object Failed : PostDetailContent
}

sealed interface PostDetailOverlay {
    data object None : PostDetailOverlay
    data object Menu : PostDetailOverlay
    data class Image(val index: Int) : PostDetailOverlay
}

data class PostDetailUiState(
    val identity: PostIdentity,
    val content: PostDetailContent = PostDetailContent.Loading,
    val selectedImage: Int = 0,
    val overlay: PostDetailOverlay = PostDetailOverlay.None,
    val commentsOpen: Boolean = false,
    /** Production mode: anonymous post pages carry no menu renderer, so the actions entry hides. */
    val live: Boolean = false,
    val signedIn: Boolean = false,
    val actionPending: Boolean = false,
    val pendingAction: PostDetailAction? = null,
    val refreshing: Boolean = false,
) {
    val post: PostDetail? get() = (content as? PostDetailContent.Ready)?.post
}

enum class PostDestination { Home, Shorts, Subscriptions, You }

sealed interface PostDetailNavigationRequest {
    data class Feedback(val identity: PostIdentity, val liked: Boolean) : PostDetailNavigationRequest
    data class Comments(val identity: PostIdentity) : PostDetailNavigationRequest
    data class MenuAction(val identity: PostIdentity, val action: PostMenuAction) : PostDetailNavigationRequest
    data class Channel(val channelId: String) : PostDetailNavigationRequest
    data class Destination(val destination: PostDestination) : PostDetailNavigationRequest
}

sealed interface PostDetailAction {
    data object Retry : PostDetailAction
    data class SelectImage(val index: Int) : PostDetailAction
    data class OpenImage(val index: Int) : PostDetailAction
    data object Like : PostDetailAction
    data object Dislike : PostDetailAction
    data class Vote(val option: Int, val selected: Boolean) : PostDetailAction
    data object Comments : PostDetailAction
    data object CloseComments : PostDetailAction
    data object OpenMenu : PostDetailAction
    data object CloseOverlay : PostDetailAction
    data class MenuSelected(val action: PostMenuAction) : PostDetailAction
    data object OpenChannel : PostDetailAction
    data class SelectDestination(val destination: PostDestination) : PostDetailAction
}

/** Layout anchors measured on the public mobile post page at 390 CSS px. */
object PostLayout {
    const val PageInset = 12f
    const val AvatarSlot = 48f
    const val Avatar = 40f
    const val HeaderTop = 12f
    const val BodyGap = 12f
    const val BodySize = 16f
    const val BodyLine = 22f
    const val BodyMaxLines = 3
    const val ImageInset = 48f
    const val ImageGap = 12f
    const val ImageRadius = 12f
    const val CounterWidth = 36f
    const val CounterHeight = 24f
    const val CounterInset = 12f
    const val FeedbackHeight = 40f
    const val FeedbackGap = 8f
    const val CardBottom = 16f
    const val DetailToolbar = 48f
}
