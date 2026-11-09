package com.hhst.youtubelite.channel.domain

import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostMenuAction
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostSummary

data class ChannelProfile(
    val channelId: String = "blender",
    val name: String,
    val handle: String,
    val avatar: String,
    val banner: String?,
    val subscribers: String,
    val description: String,
    val memberBadge: String? = null,
    val links: List<String> = emptyList(),
    val fullDescription: String = description,
    val country: String = "",
    val joined: String = "",
    val videoCount: String = "",
    val views: String = "",
    val canonicalUrl: String = "",
    /** Server-reported membership; null means the page did not expose it (guests, modern header). */
    val subscribed: Boolean? = null,
)

data class ChannelVideo(
    val id: String,
    val title: String,
    val views: String,
    val age: String,
    val duration: String,
    val artwork: String,
    val live: Boolean = false,
    val homeShelf: Boolean = false,
)

data class ChannelPage(
    val profile: ChannelProfile,
    val videos: List<ChannelVideo>,
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList(),
    val homeShelves: List<ChannelHomeShelf> = emptyList(),
    val videosNextKey: String? = null,
    /** The videos tab was not part of the landing read; it loads on demand. */
    val videosDeferred: Boolean = false,
    /** The playlists tab was not part of the landing read; it loads on demand. */
    val playlistsDeferred: Boolean = false,
    /** Landing-page endpoints, scoped by the repository to this page's session. */
    val readReference: ChannelReadReference? = null,
)

/** Opaque, page-owned reference; it is never persisted or kept in a repository cache. */
interface ChannelReadReference

data class ChannelVideosPage(val videos: List<ChannelVideo>, val nextKey: String?)

data class ChannelHomeShelf(val title: String, val description: String = "", val videos: List<ChannelVideo> = emptyList(),
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList())

data class ChannelShort(
    val id: String,
    val title: String,
    val views: String,
    val artwork: String,
    val order: Int = 0,
    val popularity: Long = 0,
)

/** A live row can be a current stream or a past broadcast; the two are never conflated. */
data class ChannelLiveVideo(
    val id: String,
    val title: String,
    val views: String,
    val time: String,
    val duration: String,
    val artwork: String,
    val liveNow: Boolean = false,
    val order: Int = 0,
    val popularity: Long = 0,
)

data class ChannelLivePage(
    val current: List<ChannelLiveVideo> = emptyList(),
    val past: List<ChannelLiveVideo> = emptyList(),
    val nextKey: String? = null,
) {
    val all: List<ChannelLiveVideo> get() = current + past
}

data class ChannelShortsPage(val shorts: List<ChannelShort>, val nextKey: String? = null)

data class ChannelSearchResults(val videos: List<ChannelVideo>, val nextKey: String? = null)

interface ChannelRepository {
    suspend fun channel(channelId: String): ChannelPage

    suspend fun shorts(channelId: String): ChannelShortsPage

    suspend fun shorts(channelId: String, opened: ChannelPage): ChannelShortsPage = shorts(channelId)

    suspend fun shortsNext(channelId: String, cursor: String): ChannelShortsPage = ChannelShortsPage(emptyList())

    suspend fun live(channelId: String): ChannelLivePage

    suspend fun live(channelId: String, opened: ChannelPage): ChannelLivePage = live(channelId)

    suspend fun liveNext(channelId: String, cursor: String): ChannelLivePage = ChannelLivePage()

    suspend fun posts(channelId: String): ChannelPostsPage

    /** The playlists tab, read on demand so it never blocks the first paint. */
    suspend fun playlists(channelId: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage =
        com.hhst.youtubelite.library.domain.PlaylistCollectionPage(emptyList())

    suspend fun playlists(channelId: String, opened: ChannelPage): com.hhst.youtubelite.library.domain.PlaylistCollectionPage =
        playlists(channelId)

    suspend fun playlistsNext(channelId: String, cursor: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage =
        com.hhst.youtubelite.library.domain.PlaylistCollectionPage(emptyList())

    suspend fun postsNext(channelId: String, cursor: String): ChannelPostsPage = ChannelPostsPage(emptyList())

    /** True when the repository searches the channel on the server instead of filtering the first page. */
    val serverSearch: Boolean get() = false

    suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults = ChannelSearchResults(emptyList())

    suspend fun searchNext(channelId: String, cursor: String): ChannelSearchResults = ChannelSearchResults(emptyList())

    suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage = ChannelVideosPage(emptyList(), null)

    suspend fun videos(channelId: String, opened: ChannelPage): ChannelVideosPage = videos(channelId, null as String?)
}

sealed interface ChannelSection<out T> {
    data object Loading : ChannelSection<Nothing>
    data object Empty : ChannelSection<Nothing>
    data object Failed : ChannelSection<Nothing>
    data class Ready<T>(val value: T) : ChannelSection<T>
}

enum class ChannelSortOption(val label: String) {
    Latest("Latest"),
    Popular("Popular"),
    Oldest("Oldest"),
}

enum class ChannelShortMenuAction(val label: String) {
    NotInterested("Not interested"),
    SendFeedback("Send feedback"),
}

sealed interface ChannelContent {
    data object Loading : ChannelContent
    data class Ready(val page: ChannelPage) : ChannelContent
    data object Failed : ChannelContent
}

enum class ChannelTab(val label: String) {
    Home("Home"), Videos("Videos"), Shorts("Shorts"), Live("Live"), Playlists("Playlists"), Posts("Posts"), Search("Search"),
}

sealed interface ChannelOverlay {
    data object About : ChannelOverlay
    data object None : ChannelOverlay
    data class PlaylistMenu(val playlistId: String) : ChannelOverlay
    data class ShortMenu(val shortId: String) : ChannelOverlay
    data class PostMenu(val identity: PostIdentity) : ChannelOverlay
}

enum class ChannelPlaylistSort(val label: String) { DateAdded("Date added (newest)"), LastVideo("Last video added") }

data class ChannelUiState(
    val channelId: String = "blender",
    val content: ChannelContent = ChannelContent.Loading,
    val selectedTab: ChannelTab = ChannelTab.Home,
    val overlay: ChannelOverlay = ChannelOverlay.None,
    val searchQuery: String = "",
    val submittedQuery: String = "",
    val playlistSort: ChannelPlaylistSort = ChannelPlaylistSort.DateAdded,
    val shorts: ChannelSection<List<ChannelShort>> = ChannelSection.Loading,
    val live: ChannelSection<ChannelLivePage> = ChannelSection.Loading,
    val posts: ChannelSection<List<PostSummary>> = ChannelSection.Loading,
    /** First-read state of a deferred videos tab; Ready means page.videos is populated. */
    val videosTab: ChannelSection<Unit> = ChannelSection.Ready(Unit),
    /** The playlists tab; Ready carries the rows, Loading/Failed are tab-local. */
    val playlists: ChannelSection<List<com.hhst.youtubelite.playlist.domain.PlaylistSummary>> = ChannelSection.Loading,
    val shortsNextKey: String? = null,
    val liveNextKey: String? = null,
    val postsNextKey: String? = null,
    val searchNextKey: String? = null,
    val playlistsNextKey: String? = null,
    val shortsAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val liveAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val postsAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val searchAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val playlistsAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    val shortsSort: ChannelSortOption = ChannelSortOption.Latest,
    val liveSort: ChannelSortOption = ChannelSortOption.Latest,
    val expandedPosts: Set<String> = emptySet(),
    val subscribePending: Boolean = false,
    /** Server search results for the Search tab; null while the tab filters the first page locally. */
    val searchResults: ChannelSection<List<ChannelVideo>>? = null,
    val videosAppend: com.hhst.youtubelite.home.domain.AppendState = com.hhst.youtubelite.home.domain.AppendState.Idle,
    /** Production mode: feedback-only menus (section/short/post) hide — their rows need tokens the server never sends. */
    val production: Boolean = false,
    /** Set when a read came back as a `/sorry` traffic challenge; loaded rows stay visible. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
    val signedIn: Boolean = false,
) {
    val isLocalTab: Boolean
        get() = selectedTab in listOf(
            ChannelTab.Home,
            ChannelTab.Videos,
            ChannelTab.Shorts,
            ChannelTab.Live,
            ChannelTab.Playlists,
            ChannelTab.Posts,
            ChannelTab.Search,
        )
}

enum class ChannelDestination(val label: String) { Home("Home"), Shorts("Shorts"), Subscriptions("Subscriptions"), You("You") }

sealed interface ChannelNavigationRequest {
    data class Destination(val destination: ChannelDestination) : ChannelNavigationRequest
    data class OpenVerification(val challengeUrl: String) : ChannelNavigationRequest
    data class ItemMenu(val videoId: String) : ChannelNavigationRequest
    data class Watch(val videoId: String) : ChannelNavigationRequest
    data class Playlist(val playlistId: String) : ChannelNavigationRequest
    data class SharePlaylist(val playlistId: String) : ChannelNavigationRequest
    data class Subscribe(val channelId: String) : ChannelNavigationRequest
    data class OpenLink(val url: String) : ChannelNavigationRequest
    data object Share : ChannelNavigationRequest
    data object ChannelSearch : ChannelNavigationRequest
    data object FullDescription : ChannelNavigationRequest
    data object SectionMenu : ChannelNavigationRequest

    /** A Short has its own typed exit; it is never passed to the ordinary video player. */
    data class Short(val channelId: String, val shortId: String) : ChannelNavigationRequest
    data class ShortMenu(
        val channelId: String,
        val shortId: String,
        val action: ChannelShortMenuAction,
    ) : ChannelNavigationRequest

    /** A currently live stream is not claimed to be playable by the on-demand player. */
    data class LiveStream(val channelId: String, val videoId: String) : ChannelNavigationRequest

    data class Post(val identity: PostIdentity) : ChannelNavigationRequest
    data class PostFeedback(val identity: PostIdentity, val liked: Boolean) : ChannelNavigationRequest
    data class PostComments(val identity: PostIdentity) : ChannelNavigationRequest
    data class PostMenu(
        val identity: PostIdentity,
        val action: PostMenuAction,
    ) : ChannelNavigationRequest
}

sealed interface ChannelAction {
    data class SelectDestination(val destination: ChannelDestination) : ChannelAction
    data object Retry : ChannelAction
    data class SelectTab(val tab: ChannelTab) : ChannelAction
    data class OpenItemMenu(val videoId: String) : ChannelAction
    data class OpenVideo(val videoId: String) : ChannelAction
    data class OpenPlaylist(val playlistId: String) : ChannelAction
    data class OpenPlaylistMenu(val playlistId: String) : ChannelAction
    data object SharePlaylist : ChannelAction
    data object CloseOverlay : ChannelAction
    data object DismissVerification : ChannelAction
    data class OpenVerification(val challengeUrl: String) : ChannelAction
    data class SortPlaylists(val sort: ChannelPlaylistSort) : ChannelAction
    data object Subscribe : ChannelAction
    data object Share : ChannelAction
    data class QueryChanged(val query: String) : ChannelAction
    data object SubmitSearch : ChannelAction
    data class OpenLink(val url: String) : ChannelAction
    data object ChannelSearch : ChannelAction
    data object OpenFullDescription : ChannelAction
    data object OpenSectionMenu : ChannelAction

    data class SortShorts(val option: ChannelSortOption) : ChannelAction
    data class SortLive(val option: ChannelSortOption) : ChannelAction
    data class RetrySection(val tab: ChannelTab) : ChannelAction
    data class OpenShort(val shortId: String) : ChannelAction
    data class OpenShortMenu(val shortId: String) : ChannelAction
    data class SelectShortMenu(val action: ChannelShortMenuAction) : ChannelAction
    data class OpenLive(val videoId: String) : ChannelAction
    data class OpenPost(val identity: PostIdentity) : ChannelAction
    data class TogglePostExpanded(val identity: PostIdentity) : ChannelAction
    data class OpenPostMenu(val identity: PostIdentity) : ChannelAction
    data class SelectPostMenu(val action: PostMenuAction) : ChannelAction
    data class PostFeedback(val identity: PostIdentity, val liked: Boolean) : ChannelAction
    data class PostComments(val identity: PostIdentity) : ChannelAction
    data object LoadVideosNext : ChannelAction
    data object LoadShortsNext : ChannelAction
    data object LoadLiveNext : ChannelAction
    data object LoadPostsNext : ChannelAction
    data object LoadPlaylistsNext : ChannelAction
    data object LoadSearchNext : ChannelAction
    data object RetrySearch : ChannelAction
}


object ChannelLayout {
    // Anchors from docs/visual/channel-sampling: measured at 390/600/840/1280 CSS px
    // (rendered as dp); inference points are marked in the measurement doc.
    const val ToolbarHeight = 48f
    const val PageInset = 16f
    const val ListRowInset = 12f
    const val DurationBadgeInset = 5f

    const val BannerAspect = 6.2f

    const val WideViewport = 700f
    const val IdentityLargeViewport = 1148f

    fun avatarSize(width: Float): Float = when {
        width < 600f -> 72f
        width < WideViewport -> 120f
        else -> 160f
    }

    fun wide(width: Float): Boolean = width >= WideViewport

    fun fullWidthSubscribe(width: Float): Boolean = !wide(width)

    fun identityStart(width: Float): Float = PageInset + avatarSize(width) + PageInset

    fun gridColumns(width: Float): Int = when {
        width < 600f -> 1
        width < IdentityLargeViewport -> 2
        else -> 3
    }

    fun cardWidth(width: Float): Float {
        val columns = gridColumns(width)
        return (width - PageInset * 2f - PageInset * (columns - 1f)) / columns
    }

    fun menuSize(width: Float): Float = if (wide(width)) 40f else 48f

    const val VideoRowHeight = 102f
    const val ThumbnailWidth = 160f
    const val ThumbnailHeight = 90f
    const val ThumbnailRadius = 8f

    const val SubscribeHeight = 40f
    const val SubscribeRadius = 20f

    const val TabBarHeight = 48f
    const val TabBarBorder = 1f
    const val TabsStartInset = 14f
    const val TabsGap = 24f
    const val TabMinWidth = 48f
    const val TabUnderlineHeight = 2f
    const val ListTopGap = 16f

    const val HeaderTopGap = 16f
    const val NameGapSmall = 4f
    const val NameGapWide = 2f
    const val IdentityDescriptionGap = 14f
    const val DescriptionLinksGapNarrow = 10f
    const val DescriptionLinksGapWide = 12f
    const val LinksSubscribeGapNarrow = 12f
    const val LinksSubscribeGapWide = 12f
    const val SubscribeTabBarGapNarrow = 16f
    const val SubscribeTabBarGapWide = 24f

    fun nameFontSize(width: Float): Float = if (width < 600f) 24f else 36f

    fun nameLineHeight(width: Float): Float = if (width < 600f) 32f else 50f

    fun mergesIdentityRows(width: Float): Boolean = width >= 600f

    fun identityFontSize(width: Float): Float = if (width >= IdentityLargeViewport) 14f else 12f

    fun identityLine(fontSize: Float): Int = if (fontSize >= 14f) 20 else 18

    fun identityNameGap(width: Float): Float = if (width >= 600f) NameGapWide else NameGapSmall

    fun descriptionLinksGap(width: Float): Float =
        if (width >= 600f) DescriptionLinksGapWide else DescriptionLinksGapNarrow

    fun linksSubscribeGap(width: Float): Float =
        if (wide(width)) LinksSubscribeGapWide else LinksSubscribeGapNarrow

    fun subscribeTabBarGap(width: Float): Float =
        if (wide(width)) SubscribeTabBarGapWide else SubscribeTabBarGapNarrow

    fun descriptionLines(width: Float): Int = if (width < 600f) 2 else 1

    // Channel community tabs, measured at 390/600/840/1280 CSS px.
    const val ShortsOuterInset = 1f
    const val ShortsGap = 2f
    const val ShortsRowGap = 3f
    const val ShortsMinWidth = 166f
    const val ShortsMaxColumns = 6
    const val ShortsCardRadius = 8f
    const val FilterChipHeight = 32f
    const val FilterChipRadius = 8f

    fun shortsColumns(width: Float): Int = (width / ShortsMinWidth).toInt().coerceIn(2, ShortsMaxColumns)

    fun shortsCardWidth(width: Float): Float {
        val columns = shortsColumns(width)
        return (width - ShortsOuterInset * 2f - ShortsGap * (columns - 1f)) / columns
    }

    const val LiveInset = 16f
    const val LiveGap = 16f
    const val LiveRowHeight = 102f
    const val LiveThumbnailWidth = 160f
    const val LiveThumbnailHeight = 90f

    fun liveColumns(width: Float): Int = gridColumns(width)

    fun liveCellWidth(width: Float): Float {
        val columns = liveColumns(width)
        return (width - LiveInset * 2f - LiveGap * (columns - 1f)) / columns
    }

    /** Wide three-column rows use the compact 160x90 row; narrow two-column grids use a tile. */
    fun liveTile(width: Float): Boolean = width >= 600f && gridColumns(width) < 3
}
