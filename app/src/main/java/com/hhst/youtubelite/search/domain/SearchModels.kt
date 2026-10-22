package com.hhst.youtubelite.search.domain

data class SearchPage(val query: String, val results: List<SearchResult>,
    val playlists: List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> = emptyList(),
    val channels: List<SearchChannel> = emptyList(),
    val shorts: List<SearchResult> = emptyList())

data class SearchChannel(val id: String, val name: String, val handle: String, val subscribers: String, val artwork: String)

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
)

/** The two groups the mobile results page exposes behind its filter button. */
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
    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): SearchPage
}

sealed interface SearchContent {
    data object Loading : SearchContent
    data class Results(val page: SearchPage) : SearchContent
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
    data object Filters : SearchNavigationRequest
    data object VoiceSearch : SearchNavigationRequest
    data object PageMenu : SearchNavigationRequest
}

sealed interface SearchAction {
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
