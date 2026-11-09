package com.hhst.youtubelite.search.domain

data class SearchPage(val query: String, val results: List<SearchResult>,
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList(),
    val channels: List<SearchChannel> = emptyList(),
    val shorts: List<SearchResult> = emptyList(),
    val nextCursor: String? = null)

data class SearchChannel(val id: String, val name: String, val handle: String, val subscribers: String, val artwork: String)

/** Completes partial renderers without rolling back resolved fields or menu capabilities. */
fun SearchResult.withMissingDisplayFrom(other: SearchResult): SearchResult = copy(
    title = title.ifBlank { other.title }, channel = channel.ifBlank { other.channel },
    channelId = channelId.ifBlank { other.channelId }, views = views.ifBlank { other.views },
    age = age.ifBlank { other.age }, duration = duration.ifBlank { other.duration },
    artwork = artwork.ifBlank { other.artwork },
    avatar = avatar?.takeIf(String::isNotBlank) ?: other.avatar?.takeIf(String::isNotBlank),
)

data class SearchResult(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val views: String,
    val age: String,
    val duration: String,
    val artwork: String,
    val badge: String? = null,
    val progress: Float? = null,
    /** Fixture-only age used by the upload-date filter; production data would carry this separately. */
    val uploadedDays: Int? = null,
    val avatar: String? = null,
    /** The card's own feedback tokens; blank until a signed-in session maps them for this generation. */
    val notInterestedToken: String? = null,
    val dontRecommendToken: String? = null,
    /** A deferred get_panel endpoint carried by this card only; read on menu open, never preloaded. */
    val menuEndpoint: com.hhst.youtubelite.home.domain.FeedMenuEndpoint? = null,
    /** The identity generation the feedback tokens were captured for; guards writes against switches. */
    val feedbackGeneration: Long? = null,
)

enum class SearchResultType(val label: String) { All("All"), Shorts("Shorts"), Channels("Channels"), Playlists("Playlists") }

enum class SearchUploadDate(val label: String, val maxAgeDays: Int?) {
    Anytime("Anytime", null),
    Today("Today", 1),
    ThisWeek("This week", 7),
    ThisMonth("This month", 31),
    ThisYear("This year", 365),
}

data class SearchFilters(
    val type: SearchResultType = SearchResultType.All,
    val uploaded: SearchUploadDate = SearchUploadDate.Anytime,
)

/** Menu rows measured on the ordinary-video action sheet. Non-share rows stay typed requests. */
enum class SearchVideoMenuAction(val label: String) {
    Queue("Add to queue"), Download("Download"),
    WatchLater("Save to Watch later"),
    SaveToPlaylist("Save to playlist"),
    Share("Share"),
    NotInterested("Not interested"),
    DontRecommend("Don't recommend channel"),
}

sealed interface SearchOverlay {
    data object None : SearchOverlay
    data object Search : SearchOverlay
    data class ItemMenu(val videoId: String) : SearchOverlay
    data class PlaylistMenu(val playlistId: String) : SearchOverlay
}

interface SearchRepository {
    /** Display metadata for cards whose search renderer omitted fields. */
    suspend fun metadata(result: SearchResult): SearchResult = result
    suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage = SearchPage(query, emptyList())
    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): SearchPage

    /**
     * Resolves menu feedback capabilities for one card, reading its own get_panel endpoint when
     * present; results without an endpoint return unchanged (inline tokens already set).
     */
    suspend fun menu(result: SearchResult): SearchResult = result

    suspend fun feedback(result: SearchResult, action: SearchVideoMenuAction) {}
}

sealed interface SearchContent {
    data object Loading : SearchContent
    data class Results(val page: SearchPage, val loadingMore: Boolean = false, val loadMoreFailed: Boolean = false,
        val emptyAutoLoads: Int = 0) : SearchContent {
        val automaticPagingPaused: Boolean get() = emptyAutoLoads >= 3
    }
    data class Empty(val query: String) : SearchContent
    data object Failed : SearchContent
}

data class SearchUiState(
    val query: String = "",
    val content: SearchContent = SearchContent.Loading,
    val signedInPreview: Boolean = true,
    val overlay: SearchOverlay = SearchOverlay.None,
    val draftQuery: String = query,
    val filters: SearchFilters = SearchFilters(),
    val filtersOpen: Boolean = false,
    /** Production mode: account writes run in the ViewModel and the feedback-only menu rows hide. */
    val live: Boolean = false,
    /** Deferred get_panel read in flight for the open result menu. */
    val menuLoading: Boolean = false,
    /** That read failed; the sheet offers one retry row. */
    val menuFailed: Boolean = false,
    /** Confirmed subscription state per channel, applied only after the server confirms. */
    val subscriptions: Map<String, Boolean> = emptyMap(),
    val subscriptionsPending: Set<String> = emptySet(),
    /** Read returned a `/sorry` traffic challenge; loaded rows stay visible. */
    val verification: com.hhst.youtubelite.core.VerificationRequired? = null,
) {
    val searchOpen: Boolean get() = overlay == SearchOverlay.Search
}

enum class SearchDestination(val label: String) {
    Home("Home"), Shorts("Shorts"), Subscriptions("Subscriptions"), You("You"),
}

sealed interface SearchNavigationRequest {
    data class Watch(val videoId: String) : SearchNavigationRequest
    data class Shorts(val itemId: String) : SearchNavigationRequest
    data class Subscribe(val channelId: String) : SearchNavigationRequest
    data class Channel(val channelId: String) : SearchNavigationRequest
    data class Destination(val destination: SearchDestination) : SearchNavigationRequest
    data class ItemMenu(val videoId: String) : SearchNavigationRequest
    data class MenuAction(val videoId: String, val action: SearchVideoMenuAction) : SearchNavigationRequest
    data class Share(val videoId: String) : SearchNavigationRequest
    data class Playlist(val playlistId: String) : SearchNavigationRequest
    data class SharePlaylist(val playlistId: String) : SearchNavigationRequest
    data class OpenVerification(val challengeUrl: String) : SearchNavigationRequest
    data object Filters : SearchNavigationRequest
    data object VoiceSearch : SearchNavigationRequest
    data object PageMenu : SearchNavigationRequest
}

sealed interface SearchAction {
    /** The visible rows and a small look-ahead window, scoped to their search. */
    data class VisibleResults(val query: String, val filters: SearchFilters, val videoIds: List<String>) : SearchAction
    data object OpenSearch : SearchAction
    data object CloseSearch : SearchAction
    data class QueryChanged(val query: String) : SearchAction
    data class SubmitSearch(val query: String) : SearchAction
    data class OpenResult(val videoId: String) : SearchAction
    data class OpenPlaylist(val playlistId: String) : SearchAction
    data class OpenPlaylistMenu(val playlistId: String) : SearchAction
    data object SharePlaylist : SearchAction
    data class Subscribe(val channelId: String) : SearchAction
    data class OpenChannel(val channelId: String) : SearchAction
    data class SelectDestination(val destination: SearchDestination) : SearchAction
    data class OpenItemMenu(val videoId: String) : SearchAction
    data class MenuAction(val action: SearchVideoMenuAction) : SearchAction
    data object CloseItemMenu : SearchAction
    data object OpenFilters : SearchAction
    data object CloseFilters : SearchAction
    data class SelectResultType(val type: SearchResultType) : SearchAction
    data class SelectUploadDate(val date: SearchUploadDate) : SearchAction
    data object VoiceSearch : SearchAction
    data object OpenPageMenu : SearchAction
    data object DismissVerification : SearchAction
    data class OpenVerification(val challengeUrl: String) : SearchAction
    data object LoadMore : SearchAction
    data object AutomaticLoadMore : SearchAction
    data object Retry : SearchAction
}

object SearchResultsLayout {
    const val TopBarHeight = 48f
    const val NavigationHeight = 56f

    /** Inline filter strip sampled at 390 and 1280: padding 12, 50-high select boxes, 1px divider. */
    const val FilterBarPadding = 12f
    const val FilterBarHeight = 75f
    const val FilterGroupHeight = 50f
    const val FilterTypeMinWidth = 104f
    const val FilterDateMinWidth = 117f
    const val FilterGroupGap = 4f
    const val FilterGroupRadius = 8f
    const val FilterGroupPadding = 10f
    const val FilterIconSize = 24f
    const val FilterTextSize = 14f
    const val FilterLineHeight = 48f
    const val FilterDividerAlpha = 0.2f

    const val ColumnMaxWidth = 840f

    const val FullBleedMaxWidth = 550f

    const val ListThumbnailWidth = 200f
    const val ListThumbnailHeight = 112f
    const val ListItemSpacing = 12f
    const val StackedItemSpacing = 24f

    const val DetailsMinHeight = 51f

    const val BadgeInset = 8f
    const val ListBadgeInset = 5f

    fun listLockup(width: Float, height: Float): Boolean =
        width >= 416f && width > height && (width * 9f >= height * 13f || width >= 931f)

    fun columnWidth(width: Float): Float = minOf(width, ColumnMaxWidth)

    fun columnInset(width: Float): Float = ((width - columnWidth(width)) / 2f).coerceAtLeast(0f)

    fun fullBleed(width: Float): Boolean = width < FullBleedMaxWidth

    fun stackedThumbnailInset(width: Float, height: Float = Float.MAX_VALUE): Float =
        if (width < 300f) 8f else if (width <= 330f || !fullBleed(width) || width > height) 12f else 0f

    fun stackedThumbnailWidth(width: Float, height: Float = Float.MAX_VALUE): Float =
        columnWidth(width) - stackedThumbnailInset(width, height) * 2f

    fun detailsStart(width: Float): Float = if (width in 300f..330f) 24f else 12f

    fun detailsEnd(width: Float, height: Float): Float =
        if (width in 331f..<FullBleedMaxWidth && width <= height) 0f else 12f

    fun stackedThumbnailHeight(width: Float, height: Float = Float.MAX_VALUE): Float =
        if (width < 300f) (width - 16f) * 9f / 16f
        else (columnWidth(width) - if (width > height && width > 330f) 48f else 24f) * 9f / 16f +
            if (fullBleed(width) && width <= height) 13f else 0f

    fun stackedThumbnailRadius(width: Float, height: Float = Float.MAX_VALUE): Float =
        if (fullBleed(width) || width > height) 0f else 12f

    fun stackedMenuSize(width: Float, height: Float = Float.MAX_VALUE): Float =
        if (fullBleed(width) || width > height && width < 931f) 48f else 40f

    fun menuSize(width: Float, height: Float): Float =
        stackedMenuSize(width, height)

    fun listTopInset(width: Float): Float = if (fullBleed(width)) 0f else 12f

    fun itemSpacing(width: Float, height: Float): Float =
        if (listLockup(width, height)) ListItemSpacing else StackedItemSpacing
}
