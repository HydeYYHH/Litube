package com.hhst.youtubelite.channel.domain

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
)

interface ChannelRepository {
    suspend fun channel(channelId: String): ChannelPage
}

sealed interface ChannelContent {
    data object Loading : ChannelContent
    data class Ready(val page: ChannelPage) : ChannelContent
    data object Failed : ChannelContent
}

enum class ChannelTab(val label: String) {
    Home("Home"), Videos("Videos"), Shorts("Shorts"), Live("Live"), Playlists("Playlists"), Community("Community"),
}

sealed interface ChannelOverlay {
    data object None : ChannelOverlay
}

data class ChannelUiState(
    val channelId: String = "blender",
    val content: ChannelContent = ChannelContent.Loading,
    val selectedTab: ChannelTab = ChannelTab.Home,
) {
    val isLocalTab: Boolean get() = selectedTab == ChannelTab.Home || selectedTab == ChannelTab.Videos
}

enum class ChannelDestination(val label: String) { Home("Home"), Shorts("Shorts"), You("You") }

sealed interface ChannelNavigationRequest {
    data class Destination(val destination: ChannelDestination) : ChannelNavigationRequest
    data class ItemMenu(val videoId: String) : ChannelNavigationRequest
    data class Watch(val videoId: String) : ChannelNavigationRequest
    data class OtherTab(val tab: ChannelTab) : ChannelNavigationRequest
    data class Subscribe(val channelId: String) : ChannelNavigationRequest
    data object Share : ChannelNavigationRequest
    data object ChannelSearch : ChannelNavigationRequest
    data object FullDescription : ChannelNavigationRequest
    data object SectionMenu : ChannelNavigationRequest
}

sealed interface ChannelAction {
    data class SelectDestination(val destination: ChannelDestination) : ChannelAction
    data object Retry : ChannelAction
    data class SelectTab(val tab: ChannelTab) : ChannelAction
    data class OpenItemMenu(val videoId: String) : ChannelAction
    data class OpenVideo(val videoId: String) : ChannelAction
    data object Subscribe : ChannelAction
    data object Share : ChannelAction
    data object ChannelSearch : ChannelAction
    data object OpenFullDescription : ChannelAction
    data object OpenSectionMenu : ChannelAction
}


object ChannelLayout {
    // Anchors from docs/visual/channel-sampling: @blenderguru at 390/600/840/1280 (header,
    // videos grid and identity probes). Values are CSS px, rendered as dp; inference points
    // are marked in the measurement doc.
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
}
