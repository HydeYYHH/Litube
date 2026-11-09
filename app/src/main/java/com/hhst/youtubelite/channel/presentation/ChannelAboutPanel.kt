package com.hhst.youtubelite.channel.presentation

import com.hhst.youtubelite.ui.design.appPaneTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.ui.design.*
import androidx.compose.ui.res.stringResource

object ChannelAboutLayout {
    fun docked(width: Float, height: Float) = width >= 931f && width > height
    fun panelWidth(width: Float, height: Float) = if (!docked(width, height)) width else if (width >= 1280f) 500f else 256f
    fun top(width: Float, height: Float): Float {
        val sampled = if (docked(width, height)) 48f else if (width > height && width > 320f) width * .5625f - 48f else 48f + width * .5625f
        return sampled.coerceIn(0f, (height - 200f).coerceAtLeast(0f))
    }
}

@Composable
fun ChannelAboutPanel(profile: ChannelProfile, width: Float, height: Float, onAction: (ChannelAction) -> Unit,
    scroll: ScrollState = rememberScrollState()) {
    val docked = ChannelAboutLayout.docked(width, height)
    val top = ChannelAboutLayout.top(width, height)
    val square = width > height && (width >= 931 || width * 9 >= height * 13)
    val headingSize = if (width < 600) 18 else 20
    val headingLine = if (width < 600) 24 else 28
    PanelTransition(fromBottom = !docked, onDismiss = { onAction(ChannelAction.CloseOverlay) },
        scrim = { scrimMotion, close ->
            Box(Modifier.fillMaxSize().then(scrimMotion).background(DesignTokens.Scrim)
                .clickable(role = Role.Button) { close() }.testTag("channel-about-scrim"))
        }) { panelMotion, close ->
    val drag = rememberBottomSheetDrag(enabled = !docked, onDismiss = close)
    Column(Modifier.offset(x = if (docked) (width - ChannelAboutLayout.panelWidth(width, height)).dp else 0.dp, y = top.dp)
        .height((height - top).dp).width(ChannelAboutLayout.panelWidth(width, height).dp)
        .then(panelMotion)
        .then(drag.panelModifier)
        .clip(RoundedCornerShape(topStart = if (square) 0.dp else 12.dp, topEnd = if (square) 0.dp else 12.dp))
        .background(DesignTokens.Background).pointerInput(Unit) { detectTapGestures {} }
        .appPaneTitle(R.string.download_size_estimate, profile.name).testTag("channel-about-panel")) {
        Column(Modifier.fillMaxWidth().then(drag.handleModifier).testTag("channel-about-drag-header")) {
        Box(Modifier.fillMaxWidth().height(12.dp).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            if (!square) Box(Modifier.size(40.dp, 4.dp).background(DesignTokens.Ink.copy(alpha = .15f), RoundedCornerShape(4.dp)))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            AppIconButton(AppIcon.Close, stringResource(R.string.a11y_close_channel_details), diameter = 48.dp) { close() }
        }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp).testTag("channel-about-content")) {
            Spacer(Modifier.height(16.dp))
            AboutHeading(stringResource(R.string.video_description), headingSize, headingLine)
            Spacer(Modifier.height(12.dp))
            AppText(profile.fullDescription, size = 14, line = 20)
            if (profile.links.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                AboutHeading(stringResource(R.string.ui_links), headingSize, headingLine)
                Spacer(Modifier.height(12.dp))
                profile.links.forEach { link ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 40.dp, bottom = 8.dp)
                        .clickable(role = Role.Button) { onAction(ChannelAction.OpenLink("https://$link")) }) {
                        AppText(link, size = 14, line = 20, weight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        AppText(link, size = 14, line = 20, color = Color(0xFF065FD4))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            AboutHeading(stringResource(R.string.ui_more_info), headingSize, headingLine)
            Spacer(Modifier.height(12.dp))
            val details = listOf(AppIcon.Globe to profile.canonicalUrl.removePrefix("https://"), AppIcon.Globe to profile.country,
                AppIcon.Info to profile.joined, AppIcon.Audience to profile.subscribers, AppIcon.VideoOutline to profile.videoCount, AppIcon.Trending to profile.views)
            details.filter { it.second.isNotEmpty() }.forEachIndexed { index, (icon, value) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("channel-about-detail-$index"), verticalAlignment = Alignment.Top) {
                    AppIcon(icon, modifier = Modifier.testTag("channel-about-detail-icon-$index"))
                    Spacer(Modifier.width(15.dp))
                    AppText(value, Modifier.weight(1f).padding(top = 2.dp).testTag("channel-about-detail-value-$index"), size = 14, line = 20,
                        maxLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    }
}

@Composable
private fun AboutHeading(text: String, size: Int, line: Int, modifier: Modifier = Modifier) {
    // Barlow Semi Condensed SemiBold (SIL OFL 1.1): static cut, no variation axes.
    val family = remember(size) { FontFamily(Font(R.font.barlow_semicondensed, FontWeight.SemiBold)) }
    BasicText(text, modifier, style = DesignTokens.text(size, line, FontWeight.SemiBold).copy(fontFamily = family),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}
