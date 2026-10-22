package com.hhst.youtubelite.library.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import com.hhst.youtubelite.ui.design.*

/** Shared rendering for the library feature (You page, history and playlists). */

@Composable
internal fun LibraryArtwork(asset: String, modifier: Modifier = Modifier) {
    Image(painterResource(when (asset) {
        "dream" -> R.drawable.sample_dream
        "sintel" -> R.drawable.sample_sintel
        else -> R.drawable.sample_bunny
    }), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

/** The 48px message card reused for signed-out, empty and failed states. */
@Composable
internal fun LibraryMessageCard(title: String, message: String, tag: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(tag).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 392.dp).fillMaxWidth().messageCardShadow()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(title, size = 24, line = 32, weight = FontWeight.Black, align = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            AppText(message, size = 14, line = 20, color = DesignTokens.Secondary, align = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
internal fun LibrarySkeleton(tag: String) {
    Column(Modifier.fillMaxSize().testTag(tag).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(DesignTokens.Ghost, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(160.dp, 16.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
                Box(Modifier.size(120.dp, 16.dp).background(DesignTokens.Ghost, RoundedCornerShape(2.dp)))
            }
        }
        repeat(4) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(DesignTokens.Ghost, RoundedCornerShape(8.dp)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { Box(Modifier.weight(1f).aspectRatio(16f / 9).background(DesignTokens.Ghost, RoundedCornerShape(12.dp))) }
        }
    }
}

@Composable
internal fun LibraryError(tag: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(tag).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppText("Something went wrong", size = 20, line = 28, weight = FontWeight.Bold)
        AppText("Please try again.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary)
        PillButton("Retry", onRetry)
    }
}

@Composable
internal fun LibraryRowItem(row: LibraryRow, icon: AppIcon, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onOpen)
        .testTag("library-row-${row.name}").padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon)
        Spacer(Modifier.width(24.dp))
        AppText(row.label, size = 14, line = 22)
    }
}

@Composable
internal fun HistoryCard(entry: HistoryEntry, compact: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 90.dp).testTag("history-item-${entry.id}")) {
        Box(Modifier.padding(start = 12.dp).width(160.dp).height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semanticsLabel("${entry.title}, ${entry.duration}")) {
            LibraryArtwork(entry.artwork, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(5.dp)
                .background(if (entry.duration == "LIVE") DesignTokens.Red else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)) {
                AppText(entry.duration, size = 12, line = 18, weight = FontWeight.Medium, color = Color.White)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(start = 8.dp, end = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)) {
                AppText(entry.title, size = 14, exactLineHeight = 17.5.sp, maxLines = if (compact) 3 else 1)
                AppText(entry.author, size = 12, line = 15, color = DesignTokens.Secondary, maxLines = 1)
                if (entry.views.isNotEmpty()) {
                    AppText(entry.views, size = 12, line = 15, color = DesignTokens.Secondary, maxLines = 1)
                }
            }
            AppIconButton(AppIcon.More, "More actions for ${entry.title}",
                modifier = Modifier.align(Alignment.TopEnd), diameter = 48.dp) { onMenu() }
        }
    }
}

private fun Modifier.semanticsLabel(label: String) = this.then(Modifier.semantics { contentDescription = label })

/** Horizontal shelf of playlist covers; geometry follows the shorts shelf card width. */
@Composable
internal fun PlaylistShelf(playlists: List<PlaylistSummary>, onOpen: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().testTag("library-playlists-shelf"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            Column(Modifier.width(160.dp).testTag("library-playlist-${playlist.id}")
                .clickable(role = Role.Button) { onOpen(playlist.id) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp))) {
                    LibraryArtwork(playlist.artwork, Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(AppIcon.Playlist, Modifier.size(12.dp), color = Color.White)
                        Spacer(Modifier.width(4.dp))
                        AppText("${playlist.videoCount}", size = 12, line = 18, color = Color.White, maxLines = 1)
                    }
                }
                AppText(playlist.title, Modifier.padding(top = 6.dp), size = 14, line = 20, weight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Horizontal shelf of recently watched videos. */
@Composable
internal fun HistoryShelf(entries: List<HistoryEntry>, onOpen: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().testTag("library-history-shelf"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.width(175.dp).testTag("library-history-${entry.id}")
                .clickable(role = Role.Button) { onOpen(entry.videoId) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp))) {
                    LibraryArtwork(entry.artwork, Modifier.fillMaxSize())
                    entry.progress?.let { progress ->
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.5f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(DesignTokens.Red))
                        }
                    }
                }
                AppText(entry.title, Modifier.padding(top = 6.dp), size = 14, line = 20, maxLines = 2)
            }
        }
    }
}
