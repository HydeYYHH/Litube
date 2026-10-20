package com.hhst.youtubelite.channel.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelLayout
import com.hhst.youtubelite.channel.domain.ChannelPage
import com.hhst.youtubelite.channel.domain.ChannelProfile
import com.hhst.youtubelite.channel.domain.ChannelTab
import com.hhst.youtubelite.channel.domain.ChannelUiState
import com.hhst.youtubelite.channel.domain.ChannelVideo
import com.hhst.youtubelite.ui.design.*

private val SubscribeForeground = Color(0xFFF1F1F1)

/** m.youtube renders its website links in this blue; the app tokens don't need it elsewhere. */
private val ChannelLink = Color(0xFF065FD4)

/**
 * Local channel page: top bar, banner, identity, description, subscribe, tab bar and the
 * Home/Videos tabs with fixed fixture content. Every other reference surface (other tabs,
 * account-bound subscribe, description expansion, per-video actions) stays a typed
 * ChannelNavigationRequest. Geometry anchors live in ChannelLayout.
 */
@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onAction: (ChannelAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.White).testTag("channel-page")) {
            val width = maxWidth.value
            // Each tab owns a list state that survives tab switches and activity recreation.
            val homeState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            val videosState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            Column(Modifier.fillMaxSize()) {
                ChannelTopBar(state, onBack, onAction)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val content = state.content) {
                        ChannelContent.Loading -> ChannelSkeleton(width)
                        ChannelContent.Failed -> ChannelFailed(onAction)
                        is ChannelContent.Ready -> when (state.selectedTab) {
                            ChannelTab.Home -> ChannelHomeTab(content.page, width, homeState, onAction)
                            else -> ChannelVideosTab(content.page, width, videosState, onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelTopBar(state: ChannelUiState, onBack: () -> Unit, onAction: (ChannelAction) -> Unit) {
    val title = (state.content as? ChannelContent.Ready)?.page?.profile?.name ?: ""
    Row(Modifier.fillMaxWidth().height(ChannelLayout.ToolbarHeight.dp).background(Color.White)
        .testTag("channel-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(53.dp), contentAlignment = Alignment.CenterStart) {
            AppIconButton(AppIcon.Back, "Close the channel page", diameter = 48.dp) { onBack() }
        }
        AppText(title, Modifier.weight(1f).testTag("channel-title"), size = 20, line = 28,
            weight = FontWeight.Bold, maxLines = 1)
        AppIconButton(AppIcon.Search, "Search within this channel", diameter = 48.dp) {
            onAction(ChannelAction.ChannelSearch)
        }
        AppIconButton(AppIcon.More, "More channel options", diameter = 48.dp) {
            onAction(ChannelAction.OpenSectionMenu)
        }
    }
}

@Composable
private fun ChannelTabBar(selected: ChannelTab, onAction: (ChannelAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("channel-tabbar")) {
        Row(Modifier.fillMaxWidth().height(ChannelLayout.TabBarHeight.dp)
            .horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ChannelLayout.TabsStartInset.dp))
            ChannelTab.entries.forEach { tab ->
                val isSelected = tab == selected
                Box(Modifier.height(ChannelLayout.TabBarHeight.dp)
                    .widthIn(min = ChannelLayout.TabMinWidth.dp)
                    .testTag("channel-tab-${tab.name}")
                    .clickable(role = Role.Tab) { onAction(ChannelAction.SelectTab(tab)) },
                    contentAlignment = Alignment.Center) {
                    AppText(tab.label, size = 14, line = 20, maxLines = 1,
                        color = if (isSelected) DesignTokens.Ink else DesignTokens.Secondary)
                    if (isSelected) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .height(ChannelLayout.TabUnderlineHeight.dp).background(DesignTokens.Ink))
                    }
                }
                Spacer(Modifier.width(ChannelLayout.TabsGap.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(ChannelLayout.TabBarBorder.dp).background(DesignTokens.Ghost))
    }
}

@Composable
private fun Artwork(asset: String, modifier: Modifier = Modifier) {
    Image(painterResource(when (asset) {
        "dream" -> R.drawable.sample_dream
        "sintel" -> R.drawable.sample_sintel
        else -> R.drawable.sample_bunny
    }), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun ChannelHomeTab(
    page: ChannelPage,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
) {
    val shelf = page.videos.filter { it.homeShelf }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-home-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, width, onAction) }
        // The sampled page scrolls the tab row with the channel header and pins it once
        // the header is gone, so tabs stay reachable from every scroll depth.
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Home, onAction) }
        if (shelf.isEmpty()) {
            item { ChannelNoVideos() }
        } else {
            // The reference adds shelf chrome around the home list; this scope keeps a small heading.
            item {
                Column(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
                    Spacer(Modifier.height(12.dp))
                    AppText("Open movies", size = 16, line = 22, weight = FontWeight.Bold, maxLines = 1)
                    AppText("The Blender Foundation's open movie projects", Modifier.padding(top = 2.dp),
                        size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(shelf, key = { it.id }) { video ->
                Box(Modifier.padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                    ChannelVideoRow(video, ChannelLayout.menuSize(width).dp, onAction)
                }
            }
        }
    }
}

@Composable
private fun ChannelVideosTab(
    page: ChannelPage,
    width: Float,
    state: LazyListState,
    onAction: (ChannelAction) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("channel-videos-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ChannelHeader(page, width, onAction) }
        stickyHeader("channel-tabbar") { ChannelTabBar(ChannelTab.Videos, onAction) }
        if (page.videos.isEmpty()) {
            item { ChannelNoVideos() }
        } else {
            val columns = ChannelLayout.gridColumns(width)
            if (columns == 1) {
                items(page.videos, key = { it.id }) { video ->
                    Box(Modifier.padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                        ChannelVideoRow(video, ChannelLayout.menuSize(width).dp, onAction)
                    }
                }
            } else {
                val cardWidth = ChannelLayout.cardWidth(width)
                items(
                    page.videos.chunked(columns),
                    key = { row -> row.joinToString("-") { it.id } },
                ) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = ChannelLayout.PageInset.dp),
                        horizontalArrangement = Arrangement.spacedBy(ChannelLayout.PageInset.dp)) {
                        row.forEach { video ->
                            ChannelVideoRow(video, ChannelLayout.menuSize(width).dp, onAction, cardWidth)
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Compact lockup sampled on the channel list: 160×90 thumb raised 12, dark meta, right-flush menu. */
@Composable
private fun ChannelVideoRow(
    video: ChannelVideo,
    menuDiameter: Dp,
    onAction: (ChannelAction) -> Unit,
    cellWidth: Float? = null,
) {
    Row(Modifier
        .then(if (cellWidth != null) Modifier.width(cellWidth.dp) else Modifier.fillMaxWidth())
        .height(ChannelLayout.VideoRowHeight.dp)
        .testTag("channel-video-${video.id}")) {
        Box(Modifier.padding(top = 12.dp).size(ChannelLayout.ThumbnailWidth.dp, ChannelLayout.ThumbnailHeight.dp)
            .clip(RoundedCornerShape(ChannelLayout.ThumbnailRadius.dp))
            .clickable(role = Role.Button) { onAction(ChannelAction.OpenVideo(video.id)) }
            .semantics { contentDescription = "${video.title}, ${video.duration}" }
            .testTag("channel-thumb-${video.id}")) {
            Artwork(video.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(ChannelLayout.DurationBadgeInset.dp)
                .background(if (video.live) DesignTokens.Red else Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(video.duration, size = 12, line = 18, weight = FontWeight.Medium, color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 14.dp)
            .clickable(role = Role.Button) { onAction(ChannelAction.OpenVideo(video.id)) }) {
            AppText(video.title, Modifier.fillMaxWidth(), size = 14, line = 18, maxLines = 2, exactLineHeight = 17.sp)
            Spacer(Modifier.height(2.dp))
            if (video.age.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(video.views, size = 12, line = 16, maxLines = 1, color = VideoMeta)
                    Spacer(Modifier.width(4.dp))
                    AppText("•", size = 12, line = 16, maxLines = 1, color = VideoMeta)
                    Spacer(Modifier.width(4.dp))
                    AppText(video.age, size = 12, line = 16, maxLines = 1, color = VideoMeta)
                }
            } else {
                AppText(video.views, size = 12, line = 16, maxLines = 1, color = VideoMeta)
            }
        }
        AppIconButton(AppIcon.More, "More actions for ${video.title}", diameter = menuDiameter) {
            onAction(ChannelAction.OpenSectionMenu)
        }
    }
}

private val VideoMeta = DesignTokens.Ink

/** Banner with the sampled 6.2:1 box; a missing banner leaves only the layout gap. */
@Composable
private fun ChannelBanner(asset: String?, width: Float) {
    if (asset != null) {
        Box(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)
            .fillMaxWidth()
            .height(((width - ChannelLayout.PageInset * 2f) / ChannelLayout.BannerAspect).dp)
            .testTag("channel-banner")) { Artwork(asset, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun ChannelIdentityAvatar(asset: String, size: Float) {
    Box(Modifier.size(size.dp).clip(CircleShape).testTag("channel-avatar")) {
        Artwork(asset, Modifier.fillMaxSize())
    }
}

@Composable
private fun BylineDot(fontSize: Int) {
    AppText("•", size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
        color = DesignTokens.Secondary, maxLines = 1)
}

@Composable
private fun BylineIdentity(text: String, fontSize: Int) {
    AppText(text, size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
        color = DesignTokens.Secondary, maxLines = 1)
}

/** Header stack: banner → identity → description → links → subscribe, per the sampled rhythm. */
@Composable
private fun ChannelHeader(page: ChannelPage, width: Float, onAction: (ChannelAction) -> Unit) {
    val profile = page.profile
    val wide = ChannelLayout.wide(width)
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    Column(Modifier.fillMaxWidth().testTag("channel-header")) {
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        ChannelBanner(profile.banner, width)
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        Row(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
            if (wide) {
                Column(Modifier.width((ChannelLayout.identityStart(width) - ChannelLayout.PageInset).dp)) {
                    Spacer(Modifier.height(18.dp))
                    ChannelIdentityAvatar(profile.avatar, ChannelLayout.avatarSize(width))
                }
            } else {
                ChannelIdentityAvatar(profile.avatar, ChannelLayout.avatarSize(width))
                Spacer(Modifier.width(ChannelLayout.PageInset.dp))
            }
            Column(Modifier.weight(1f).widthIn(max = 600.dp).testTag("channel-identity")) {
                AppText(profile.name, Modifier.fillMaxWidth().testTag("channel-name"),
                    size = ChannelLayout.nameFontSize(width).toInt(), line = ChannelLayout.nameLineHeight(width).toInt(),
                    weight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(ChannelLayout.identityNameGap(width).dp))
                if (ChannelLayout.mergesIdentityRows(width)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(profile.handle, Modifier.testTag("channel-handle"),
                            size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
                            weight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        BylineDot(fontSize)
                        Spacer(Modifier.width(4.dp))
                        BylineIdentity(profile.subscribers, fontSize)
                        Spacer(Modifier.width(4.dp))
                        BylineDot(fontSize)
                        Spacer(Modifier.width(4.dp))
                        BylineIdentity("${page.videos.size} videos", fontSize)
                    }
                } else {
                    AppText(profile.handle, Modifier.fillMaxWidth().testTag("channel-handle"),
                        size = fontSize, line = ChannelLayout.identityLine(fontSize.toFloat()),
                        weight = FontWeight.Medium, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BylineIdentity(profile.subscribers, fontSize)
                        Spacer(Modifier.width(4.dp))
                        BylineDot(fontSize)
                        Spacer(Modifier.width(4.dp))
                        BylineIdentity("${page.videos.size} videos", fontSize)
                    }
                }
            }
        }
        Spacer(Modifier.height(ChannelLayout.IdentityDescriptionGap.dp))
        val paragraphStart = if (wide) ChannelLayout.identityStart(width) else ChannelLayout.PageInset
        Column(Modifier
            .padding(start = paragraphStart.dp, end = ChannelLayout.PageInset.dp)
            .widthIn(max = 600.dp)) {
            ChannelDescription(profile.description, width, onAction)
            Spacer(Modifier.height(ChannelLayout.descriptionLinksGap(width).dp))
            ChannelLinks(profile, width, onAction)
        }
        // The narrow subscribe pill ignores the 600 text cap: below 700 it is the full row width.
        Spacer(Modifier.height(ChannelLayout.linksSubscribeGap(width).dp))
        Box(Modifier.padding(start = paragraphStart.dp, end = ChannelLayout.PageInset.dp)) {
            ChannelSubscribe(wide, onAction)
        }
        Spacer(Modifier.height(ChannelLayout.subscribeTabBarGap(width).dp))
    }
}

/**
 * Description summary with the sampled inline `...more`; taps stay a typed request. The
 * fixture text and tail are sized so the tail renders inline at the sampled widths.
 */
@Composable
private fun ChannelDescription(description: String, width: Float, onAction: (ChannelAction) -> Unit) {
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    val lineHeight = ChannelLayout.identityLine(fontSize.toFloat())
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = DesignTokens.Secondary)) { append(description) }
        append(" ")
        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = DesignTokens.Ink)) { append("...more") }
    }
    Box(Modifier.fillMaxWidth().testTag("channel-description")
        .clickable(role = Role.Button) { onAction(ChannelAction.OpenFullDescription) }) {
        BasicText(annotated,
            style = TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp,
                fontWeight = FontWeight.Normal, color = DesignTokens.Secondary,
                fontFamily = DesignTokens.Font, platformStyle = PlatformTextStyle(includeFontPadding = false)),
            maxLines = ChannelLayout.descriptionLines(width), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelLinks(profile: ChannelProfile, width: Float, onAction: (ChannelAction) -> Unit) {
    val first = profile.links.firstOrNull() ?: return
    val fontSize = ChannelLayout.identityFontSize(width).toInt()
    val lineHeight = ChannelLayout.identityLine(fontSize.toFloat())
    Row(Modifier.fillMaxWidth().testTag("channel-links")
        .clickable(role = Role.Button) { onAction(ChannelAction.OpenFullDescription) },
        verticalAlignment = Alignment.CenterVertically) {
        AppText(first, size = fontSize, line = lineHeight, color = ChannelLink, maxLines = 1)
        val extra = profile.links.size - 1
        if (extra > 0) {
            Spacer(Modifier.width(4.dp))
            AppText("and $extra more link" + if (extra > 1) "s" else "",
                size = fontSize, line = lineHeight, weight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ChannelSubscribe(wide: Boolean, onAction: (ChannelAction) -> Unit) {
    Box(Modifier
        .then(if (wide) Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = false) else Modifier.fillMaxWidth(1f))
        .height(ChannelLayout.SubscribeHeight.dp)
        .clip(RoundedCornerShape(ChannelLayout.SubscribeRadius.dp))
        .background(DesignTokens.Ink)
        .clickable(role = Role.Button) { onAction(ChannelAction.Subscribe) }
        .testTag("channel-subscribe"),
        contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Subscribe", size = 14, line = 18, weight = FontWeight.Medium, color = SubscribeForeground)
        }
    }
}

@Composable
private fun ChannelNoVideos() {
    Column(Modifier.fillMaxWidth().testTag("channel-empty").padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AppText("This channel has no videos.", color = DesignTokens.Secondary, align = TextAlign.Center)
    }
}

/** Loading skeleton with the banner, identity and first rows of the sampled geometry. */
@Composable
private fun ChannelSkeleton(width: Float) {
    val bannerHeight = ((width - ChannelLayout.PageInset * 2f) / ChannelLayout.BannerAspect).dp
    Column(Modifier.fillMaxSize().testTag("channel-skeleton")) {
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp))
        Box(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)
            .fillMaxWidth().height(bannerHeight).background(DesignTokens.Ghost))
        Spacer(Modifier.height(ChannelLayout.HeaderTopGap.dp + 8.dp))
        Row(Modifier.padding(horizontal = ChannelLayout.PageInset.dp)) {
            Box(Modifier.size(ChannelLayout.avatarSize(width).dp).background(DesignTokens.Ghost, CircleShape))
            Spacer(Modifier.width(ChannelLayout.PageInset.dp))
            Column(Modifier.padding(top = 8.dp)) {
                Box(Modifier.width(180.dp).height(24.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(140.dp).height(18.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(120.dp).height(18.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
            }
        }
        repeat(3) { index ->
            Row(Modifier.height(ChannelLayout.VideoRowHeight.dp).padding(horizontal = ChannelLayout.ListRowInset.dp)) {
                Box(Modifier.padding(top = 12.dp)
                    .size(ChannelLayout.ThumbnailWidth.dp, ChannelLayout.ThumbnailHeight.dp)
                    .background(DesignTokens.Ghost, RoundedCornerShape(ChannelLayout.ThumbnailRadius.dp))
                    .testTag("skeleton-thumb-$index"))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 14.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(16.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(120.dp).height(16.dp)
                        .background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun ChannelFailed(onAction: (ChannelAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(24.dp).testTag("channel-error"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
            AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
            Box(Modifier.clip(CircleShape).background(DesignTokens.Surface)
                .clickable(role = Role.Button) { onAction(ChannelAction.Retry) }
                .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                AppText("Retry", weight = FontWeight.Medium)
            }
        }
    }
}
