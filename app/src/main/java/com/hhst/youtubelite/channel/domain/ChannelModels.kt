package com.hhst.youtubelite.channel.domain

/** App-owned models for the local channel page; every asset is a bundled fixture. */
data class ChannelProfile(
    val channelId: String = "blender",
    val name: String,
    val handle: String,
    val avatar: String,
    val banner: String?,
    /** The one-line stats rendered next to the name, e.g. subscribers. */
    val subscribers: String,
    /** First description sentence shown before the inline expansion affordance. */
    val description: String,
        /** Account context shown beside the name; fixture-only, no real account data. */
        val memberBadge: String? = null,
        /** Described website links of the channel; the row shows the first plus a count of the rest. */
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
    /** Which channel tab a video belongs to when both tabs can show it. */
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
    /** Only these tabs switch locally; the rest stay typed requests. */
    val isLocalTab: Boolean get() = selectedTab == ChannelTab.Home || selectedTab == ChannelTab.Videos
}

/** Typed boundaries only; the host records them and no page action is simulated. */
sealed interface ChannelNavigationRequest {
    data class Watch(val videoId: String) : ChannelNavigationRequest
    data class OtherTab(val tab: ChannelTab) : ChannelNavigationRequest
    data class Subscribe(val channelId: String) : ChannelNavigationRequest
    data object Share : ChannelNavigationRequest
    data object ChannelSearch : ChannelNavigationRequest
    data object FullDescription : ChannelNavigationRequest
    data object SectionMenu : ChannelNavigationRequest
}

sealed interface ChannelAction {
    data object Retry : ChannelAction
    data class SelectTab(val tab: ChannelTab) : ChannelAction
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

    /** Banner leaves `PageInset` on both sides and keeps the sampled 6.2:1 box. */
    const val BannerAspect = 6.2f

    const val WideViewport = 700f
    const val IdentityLargeViewport = 1148f

    /** Avatar ladder sampled at 390 (72), 600 (120) and 840/1280 (160). */
    fun avatarSize(width: Float): Float = when {
        width < 528f -> 72f
        width < WideViewport -> 120f
        else -> 160f
    }

    /** ≥700px the identity block sits beside the widened avatar, as sampled at 840/1280. */
    fun wide(width: Float): Boolean = width >= WideViewport

    fun fullWidthSubscribe(width: Float): Boolean = !wide(width)

    fun identityStart(width: Float): Float = PageInset + avatarSize(width) + PageInset

    /** Videos grid: one column sampled at 390, two on mid widths, three sampled at 1280. */
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
    const val ThumbnailRadius = 12f

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
    const val LinksSubscribeGapWide = 10f
    const val SubscribeTabBarGapNarrow = 16f
    const val SubscribeTabBarGapWide = 24f

    fun nameFontSize(width: Float): Float = when {
        width < 528f -> 24f   // sampled lh 32 at 390
        width < IdentityLargeViewport -> 28f // 600/840 views; no direct sample (recorded gap)
        else -> 36f           // sampled lh 50 at 1280
    }

    fun nameLineHeight(width: Float): Float = when {
        width < 528f -> 32f
        width < IdentityLargeViewport -> 38f
        else -> 50f
    }

    /** The handle and stats merge into one inline row at ≥528, as sampled at 600/1280. */
    fun mergesIdentityRows(width: Float): Boolean = width >= 528f

    fun identityFontSize(width: Float): Float = if (width >= IdentityLargeViewport) 14f else 12f

    fun identityLine(fontSize: Float): Int = if (fontSize >= 14f) 20 else 18

    fun identityNameGap(width: Float): Float = if (wide(width)) NameGapWide else NameGapSmall

    fun descriptionLinksGap(width: Float): Float =
        if (wide(width)) DescriptionLinksGapWide else DescriptionLinksGapNarrow

    fun linksSubscribeGap(width: Float): Float =
        if (wide(width)) LinksSubscribeGapWide else LinksSubscribeGapNarrow

    fun subscribeTabBarGap(width: Float): Float =
        if (wide(width)) SubscribeTabBarGapWide else SubscribeTabBarGapNarrow

    fun descriptionLines(width: Float): Int = if (width < 528f) 2 else 1
}
