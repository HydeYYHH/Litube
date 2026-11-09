package com.hhst.youtubelite.home.domain

/** App-owned models. MediaServiceCore types and Android resources stay at the edges. */
data class HomePage(
    val items: List<FeedItem>,
    val messages: List<String> = emptyList(),
    val nextKey: String? = null,
    val catalog: HomeCatalog? = null,
    val partialFailure: Boolean = false,
)

enum class HomeCatalog(val filter: String) {
    Popular("All"), MusicCharts("Music charts"), Gaming("Gaming"), Sports("Sports"), News("News");

    companion object {
        val filters = entries.map { it.filter }
        fun forFilter(filter: String) = entries.firstOrNull { it.filter == filter }
    }
}

sealed interface FeedItem {
    val id: String
    @kotlinx.serialization.Serializable
    data class Video(
        override val id: String,
        val title: String,
        val author: String,
        val metadata: String,
        val artwork: String,
        val duration: String,
        val channelId: String,
        val live: Boolean = false,
        val progress: Float? = null,
        val age: String? = null,
        val isNew: Boolean = false,
        val avatar: String? = null,
        @kotlinx.serialization.Transient val notInterestedToken: String? = null,
        @kotlinx.serialization.Transient val dontRecommendToken: String? = null,
        @kotlinx.serialization.Transient val feedbackGeneration: Long? = null,
        @kotlinx.serialization.Transient val menuEndpoint: FeedMenuEndpoint? = null,
        val short: Boolean = false,
    ) : FeedItem
    data class Mix(override val id: String, val title: String, val authors: String, val artwork: String, val palette: ThumbnailPalette) : FeedItem
    data class Playlist(val summary: com.hhst.youtubelite.playlist.domain.PlaylistSummary) : FeedItem {
        override val id: String get() = "playlist-${summary.id}"
    }
    data class Shorts(override val id: String, val videos: List<Video>) : FeedItem
    data class Post(
        override val id: String, val author: String, val age: String, val text: String,
        val artwork: String, val images: List<String>, val likes: String, val comments: String,
        val channelId: String = "studio", val postId: String = id,
    ) : FeedItem
}

data class ThumbnailPalette(val stackColor: Int, val badgeColor: Int)

/** Later renderers may omit display fields already recovered for the same video. */
fun FeedItem.Video.withMissingDisplayFrom(other: FeedItem.Video): FeedItem.Video = copy(
    title = title.ifBlank { other.title }, author = author.ifBlank { other.author },
    metadata = metadata.ifBlank { other.metadata }, artwork = artwork.ifBlank { other.artwork },
    duration = duration.ifBlank { other.duration }, channelId = channelId.ifBlank { other.channelId },
    age = age?.takeIf(String::isNotBlank) ?: other.age?.takeIf(String::isNotBlank),
    avatar = avatar?.takeIf(String::isNotBlank) ?: other.avatar?.takeIf(String::isNotBlank),
)

data class FeedMenuEndpoint(val panelId: String, val params: String) {
    override fun toString() = "FeedMenuEndpoint"
}

interface HomeRepository {
    val filters: List<String> get() = HomeLayout.chips
    suspend fun loadHome(filter: String, continuation: String? = null): HomePage

    suspend fun menu(video: FeedItem.Video): FeedItem.Video = video

    suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) = Unit
}

enum class AppendState { Idle, Loading, Failed }
sealed interface HomeContent {
    data object Loading : HomeContent
    data class Feed(val page: HomePage, val refreshing: Boolean = false, val append: AppendState = AppendState.Idle,
        val refreshFailed: Boolean = false,
        /** A user-visible refresh (pull, chip or identity change) may show the anchored indicator. */
        val userRefresh: Boolean = false,
        val emptyAutoLoads: Int = 0) : HomeContent {
        val automaticPagingPaused: Boolean get() = emptyAutoLoads >= 3
    }
    /** The empty card stays painted during the quiet re-check after a real watch (no Loading flash); blank [title]/[message] fall back to localized strings in the UI layer. */
    data class Empty(val title: String = "", val message: String = "", val refreshing: Boolean = false,
        val userRefresh: Boolean = false) : HomeContent
    data object Failed : HomeContent
}

sealed interface HomeOverlay {
    data object None : HomeOverlay
    data object Search : HomeOverlay
    data class Menu(val itemId: String, val kind: FeedMenuKind = FeedMenuKind.Video,
        val loading: Boolean = false, val failed: Boolean = false) : HomeOverlay
    data class Save(
        val videoId: String,
        val newPlaylist: Boolean = false,
        val title: String = "",
        val description: String = "",
        /** Live mode: the ViewModel owns server choices and writes; the fixture sheet stays request-only. */
        val live: Boolean = false,
        val choices: List<com.hhst.youtubelite.saves.domain.SaveChoice>? = null,
        val choicesFailed: Boolean = false,
        val pending: Boolean = false,
    ) : HomeOverlay
    data class Feedback(val itemId: String, val kind: HomeFeedbackKind) : HomeOverlay
}

enum class HomeFeedbackKind(val title: String, val reasons: List<String>) {
    NotInterested("Tell us why", listOf(
        "I'm not interested in this video", "It's repetitive", "I've already seen it",
    )),
    DontRecommend("Tell us why", listOf(
        "I don't like this channel", "This channel is misleading", "I've already seen enough",
    )),
    SendFeedback("Send feedback", listOf(
        "Not interested", "Inappropriate", "Misleading", "Something else",
    )),
    Report("Report post", listOf(
        "Spam or misleading", "Harmful content", "Something else",
    )),
}

data class HomeUiState(
    val content: HomeContent = HomeContent.Empty(),
    val query: String = "",
    val selectedChip: String = "All",
    val overlay: HomeOverlay = HomeOverlay.None,
    val signedInPreview: Boolean = false,
    val hideShorts: Boolean = false,
    val chips: List<String> = HomeLayout.chips,
    val avatar: String? = null,
    /** Production mode: feedback menu rows without server tokens hide. Fixtures keep the recorded menus. */
    val live: Boolean = false,
    val sessionGeneration: Long? = null,
    /** Set when the feed read came back as a `/sorry` traffic challenge; existing content stays visible. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
) {
    val suggestions: List<String> get() = if (query.isBlank()) emptyList() else listOf(
        query.trim(), "${query.trim()} documentary", "${query.trim()} music", "${query.trim()} live",
    ).distinct()
}

enum class HomeDestination(val label: String) { Home("Home"), Shorts("Shorts"), Subscriptions("Subscriptions"), You("You") }
enum class FeedMenuAction(val label: String) {
    Queue("Add to queue"), Download("Download"),
    WatchLater("Save to Watch later"), SaveToPlaylist("Save to playlist"), Share("Share"),
    NotInterested("Not interested"), DontRecommend("Don't recommend channel"),
    SendFeedback("Send feedback"), ShowFewerShorts("Show fewer Shorts"), Report("Report"), SaveToLibrary("Save to Library"),
}

enum class FeedMenuKind(val actions: List<FeedMenuAction>) {
    Video(listOf(FeedMenuAction.Queue, FeedMenuAction.Download, FeedMenuAction.WatchLater, FeedMenuAction.SaveToPlaylist, FeedMenuAction.Share, FeedMenuAction.NotInterested, FeedMenuAction.DontRecommend)),
    Short(listOf(FeedMenuAction.NotInterested, FeedMenuAction.SendFeedback)),
    Shorts(listOf(FeedMenuAction.ShowFewerShorts)),
    Post(listOf(FeedMenuAction.Report)),
    Mix(listOf(FeedMenuAction.SaveToLibrary, FeedMenuAction.Share, FeedMenuAction.NotInterested)),
}

fun FeedMenuKind.productionActions(signedIn: Boolean = false, video: FeedItem.Video? = null): List<FeedMenuAction> = when (this) {
    FeedMenuKind.Video -> buildList {
        addAll(listOf(FeedMenuAction.Queue, FeedMenuAction.Download, FeedMenuAction.WatchLater, FeedMenuAction.SaveToPlaylist, FeedMenuAction.Share))
        if (signedIn && !video?.notInterestedToken.isNullOrBlank()) add(FeedMenuAction.NotInterested)
        if (signedIn && !video?.dontRecommendToken.isNullOrBlank()) add(FeedMenuAction.DontRecommend)
    }
    FeedMenuKind.Mix -> listOf(FeedMenuAction.Share)
    FeedMenuKind.Short, FeedMenuKind.Shorts, FeedMenuKind.Post -> emptyList()
}

fun FeedMenuKind.visibleActions(live: Boolean, signedIn: Boolean = false, video: FeedItem.Video? = null): List<FeedMenuAction> =
    if (live) productionActions(signedIn, video) else actions

sealed interface HomeNavigationRequest {
    data class Search(val query: String) : HomeNavigationRequest
    data class Watch(val videoId: String) : HomeNavigationRequest
    data class Shorts(val itemId: String) : HomeNavigationRequest
    data class Channel(val channelId: String) : HomeNavigationRequest
    data class Playlist(val playlistId: String) : HomeNavigationRequest
    data class Destination(val destination: HomeDestination) : HomeNavigationRequest
    data class MenuAction(val itemId: String, val action: FeedMenuAction) : HomeNavigationRequest
    data class SaveToWatchLater(val videoId: String) : HomeNavigationRequest
    data class SaveToPlaylist(val videoId: String, val playlistId: String, val title: String) : HomeNavigationRequest
    data class CreatePlaylist(val videoId: String, val title: String, val description: String) : HomeNavigationRequest
    data class Feedback(val itemId: String, val kind: HomeFeedbackKind, val reason: String) : HomeNavigationRequest
    data class PostFeedback(val postId: String, val like: Boolean, val channelId: String = "studio") : HomeNavigationRequest
    data class PostComments(val postId: String, val channelId: String = "studio") : HomeNavigationRequest
    data class OpenPost(val channelId: String, val postId: String) : HomeNavigationRequest
    data class OpenLink(val url: String) : HomeNavigationRequest
    data class OpenVerification(val challengeUrl: String) : HomeNavigationRequest
    data object VoiceSearch : HomeNavigationRequest
    data object Explore : HomeNavigationRequest
}

sealed interface HomeAction {
    data object OpenSearch : HomeAction
    data object CloseOverlay : HomeAction
    data class QueryChanged(val query: String) : HomeAction
    data class SubmitSearch(val query: String) : HomeAction
    data class SelectChip(val chip: String) : HomeAction
    data class OpenMenu(val itemId: String, val kind: FeedMenuKind = FeedMenuKind.Video) : HomeAction
    data class MenuSelected(val action: FeedMenuAction) : HomeAction
    data class SaveDestinationSelected(val destination: com.hhst.youtubelite.saves.domain.SaveDestination) : HomeAction
    data class SaveChoiceToggled(val choice: com.hhst.youtubelite.saves.domain.SaveChoice) : HomeAction
    data object SaveChoicesRetry : HomeAction
    data object OpenNewPlaylist : HomeAction
    data class SaveDraftChanged(val title: String, val description: String) : HomeAction
    data object SubmitNewPlaylist : HomeAction
    data class FeedbackReason(val reason: String) : HomeAction
    data class Navigate(val request: HomeNavigationRequest) : HomeAction
    data object Refresh : HomeAction
    data object LoadNext : HomeAction
    data object AutomaticLoadNext : HomeAction
    data object DismissVerification : HomeAction
}

/** Layout dimensions use the available window bounds in dp. */
object HomeLayout {
    fun columns(width: Float): Int = when {
        width < 528f -> 1
        width < 994f -> 2
        width < 1320f -> 3
        width < 1646f -> 4
        width < 1972f -> 5
        else -> 6
    }
    fun outerPadding(width: Float): Float = if (width < 528f) 0f else 16f
    fun shortsColumns(width: Float): Int = if (width < 600f) 2 else columns(width) * 2
    fun menuWidth(width: Float, height: Float): Float =
        if (width >= 416f && width > height && (width * 9 >= height * 13 || width >= 931f)) 400f
        else (width - 16f).coerceAtLeast(0f)
    val chips = listOf("All", "Music", "Mixes", "Gaming", "Live", "Nature", "Podcasts", "Recently uploaded", "New to you")
}
