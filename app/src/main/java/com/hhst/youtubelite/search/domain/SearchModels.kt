package com.hhst.youtubelite.search.domain

data class SearchPage(val query: String, val results: List<SearchResult>)

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
)

interface SearchRepository {
    suspend fun search(query: String): SearchPage
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
    val searchOpen: Boolean = false,
    val draftQuery: String = query,
)

enum class SearchDestination(val label: String) {
    Home("Home"), Shorts("Shorts"), Subscriptions("Subscriptions"), You("You"),
}

sealed interface SearchNavigationRequest {
    data class Watch(val videoId: String) : SearchNavigationRequest
    data class Channel(val channelId: String) : SearchNavigationRequest
    data class Destination(val destination: SearchDestination) : SearchNavigationRequest
    data class ItemMenu(val videoId: String) : SearchNavigationRequest
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
    data class OpenChannel(val channelId: String) : SearchAction
    data class SelectDestination(val destination: SearchDestination) : SearchAction
    data class OpenItemMenu(val videoId: String) : SearchAction
    data object OpenFilters : SearchAction
    data object VoiceSearch : SearchAction
    data object OpenPageMenu : SearchAction
    data object Retry : SearchAction
}

object SearchResultsLayout {
    const val TopBarHeight = 48f
    const val NavigationHeight = 56f

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
