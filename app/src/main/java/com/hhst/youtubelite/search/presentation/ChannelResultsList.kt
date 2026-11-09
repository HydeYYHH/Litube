package com.hhst.youtubelite.search.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.ui.design.*

@Composable
fun ChannelResultsList(channels: List<SearchChannel>, state: LazyListState, onAction: (SearchAction) -> Unit, footer: (@Composable () -> Unit)? = null,
    subscriptions: Map<String, Boolean> = emptyMap(), subscriptionsPending: Set<String> = emptySet()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("search-channels-list"), state = state,
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            items(channels, key = { it.id }) { channel ->
                Column(Modifier.fillMaxWidth().testTag("search-channel-result-${channel.id}")) {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        val imageSlot = if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) maxWidth * .35f else 160.dp
                        val compact = maxWidth < 300.dp && LocalDensity.current.fontScale > 1.3f
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(imageSlot).heightIn(min = 90.dp)
                                    .clickable(role = Role.Button) { onAction(SearchAction.OpenChannel(channel.id)) }, contentAlignment = Alignment.Center) {
                                    com.hhst.youtubelite.ui.design.Artwork(channel.artwork,
                                        modifier = Modifier.size(67.dp).clip(CircleShape).testTag("search-channel-avatar-${channel.id}"))
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onAction(SearchAction.OpenChannel(channel.id)) }) {
                                        AppText(channel.name, Modifier.testTag("search-channel-name-${channel.id}"), size = 14, line = 18, maxLines = 2, exactLineHeight = 17.5.sp)
                                        // Blank fields (production mapping may omit them) render no line.
                                        if (channel.handle.isNotBlank()) AppText(channel.handle, size = 12, line = 15, color = Color(0xFF888888), maxLines = 1)
                                        if (channel.subscribers.isNotBlank()) AppText(channel.subscribers, size = 12, line = 15, color = Color(0xFF888888), maxLines = 1)
                                    }
                                    if (!compact) {
                                        Spacer(Modifier.height(8.dp))
                                        ChannelSubscribe(channel.id, onAction, subscribed = subscriptions[channel.id] == true,
                                            pending = channel.id in subscriptionsPending, enabled = subscriptionsPending.isEmpty())
                                    }
                                }
                            }
                            if (compact) ChannelSubscribe(channel.id, onAction, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                subscribed = subscriptions[channel.id] == true, pending = channel.id in subscriptionsPending, enabled = subscriptionsPending.isEmpty())
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (footer != null) item(key = "pagination") { footer() }
        }
    }
}

@Composable
private fun ChannelSubscribe(channelId: String, onAction: (SearchAction) -> Unit, modifier: Modifier = Modifier,
    subscribed: Boolean = false, pending: Boolean = false, enabled: Boolean = true) {
    SubscribeButton(subscribed, { onAction(SearchAction.Subscribe(channelId)) }, modifier,
        pending = pending, enabled = enabled, testTag = "search-channel-subscribe-$channelId", channelId = channelId)
}
