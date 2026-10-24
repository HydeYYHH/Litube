package com.hhst.youtubelite.library.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.home.presentation.PillButton
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.ui.components.MainNavigationBar
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.design.*

@Composable
fun YouScreen(state: LibraryUiState, onAction: (LibraryAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("you-screen")) {
        val width = maxWidth.value
        val listState = rememberLibraryListState()
        Column(Modifier.fillMaxSize().then(if (state.overlay != LibraryOverlay.None) Modifier.clearAndSetSemantics {} else Modifier)) {
            YouTopBar(state.signedIn, onAction)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val content = state.content) {
                    LibraryContent.Loading -> LibrarySkeleton("you-skeleton")
                    LibraryContent.Failed -> if (state.verification != null) VerificationCard(
                        state.verification,
                        onVerify = { onAction(LibraryAction.OpenVerification(state.verification!!.challengeUrl)) },
                        onDismiss = { onAction(LibraryAction.DismissVerification) },
                        modifier = Modifier.testTag("you-verification"),
                    ) else LibraryError("you-error") { onAction(LibraryAction.Retry) }
                    is LibraryContent.SignedOut -> LibraryMessageCard(
                        content.prompt.title, content.prompt.message, "you-signed-out",
                    ) { PillButton(content.prompt.action) { onAction(LibraryAction.SignIn) } }
                    is LibraryContent.Ready -> LibraryList(listState, content, width, state.signedIn, onAction)
                }
            }
            MainNavigationBar("main", state.signedIn, accountAvatar(state)) { tab ->
                onAction(LibraryAction.Destination(tab))
            }
        }
    }
}

private fun accountAvatar(state: LibraryUiState): String? =
    (state.content as? LibraryContent.Ready)?.page?.account?.avatar

@Composable
private fun rememberLibraryListState(): LazyListState {
    val state = androidx.compose.runtime.saveable.rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    return state
}

@Composable
private fun YouTopBar(signedIn: Boolean, onAction: (LibraryAction) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("you-topbar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(117.dp).height(DesignTokens.TopBarHeight), contentAlignment = Alignment.CenterStart) {
            com.hhst.youtubelite.ui.design.LitubeBrand(Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.weight(1f))
        if (signedIn) AppIconButton(AppIcon.Bell, "Notifications") { onAction(LibraryAction.OpenNotifications) }
        AppIconButton(AppIcon.Search, "Search YouTube") { onAction(LibraryAction.OpenSearch) }
        if (!signedIn) AppIconButton(AppIcon.More, "More") { onAction(LibraryAction.OpenAccountMenu) }
    }
}

@Composable
private fun LibraryList(listState: LazyListState, content: LibraryContent.Ready, width: Float, signedIn: Boolean, onAction: (LibraryAction) -> Unit) {
    val page = content.page
    LazyColumn(Modifier.fillMaxSize().testTag("you-library"), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "account") {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onAction(LibraryAction.OpenAccountMenu) }
                .testTag("you-account").padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape)) { LibraryArtwork(page.account.avatar, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(12.dp))
                Column {
                    AppText(page.account.name, size = 16, line = 22, weight = FontWeight.Medium)
                    AppText(page.account.handle, size = 14, line = 20, color = DesignTokens.Secondary)
                }
            }
        }
        item(key = "rows") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                LibraryRowItem(LibraryRow.History, AppIcon.Clock) { onAction(LibraryAction.OpenHistory) }
                LibraryRowItem(LibraryRow.Playlists, AppIcon.Playlist) { onAction(LibraryAction.OpenPlaylists) }
                LibraryRowItem(LibraryRow.WatchLater, AppIcon.Bookmark) { onAction(LibraryAction.OpenWatchLater) }
                if (signedIn) LibraryRowItem(LibraryRow.LikedVideos, AppIcon.Like) { onAction(LibraryAction.OpenLiked) }
                LibraryRowItem(LibraryRow.Downloads, AppIcon.Download) { onAction(LibraryAction.OpenDownloads) }
            }
        }
        when {
            page.historyFailed -> item(key = "history-failed") {
                ShelfRetry("Couldn't load history") { onAction(LibraryAction.Retry) }
            }
            page.history.isNotEmpty() -> {
                item(key = "history-header") { ShelfHeader("History") { onAction(LibraryAction.OpenHistory) } }
                item(key = "history-shelf") { HistoryShelf(page.history) { onAction(LibraryAction.OpenVideo(it)) } }
            }
        }
        when {
            page.playlistsFailed -> item(key = "playlists-failed") {
                ShelfRetry("Couldn't load playlists") { onAction(LibraryAction.Retry) }
            }
            page.playlists.isNotEmpty() -> {
                item(key = "playlists-header") { ShelfHeader("Playlists") { onAction(LibraryAction.OpenPlaylists) } }
                item(key = "playlists-shelf") { PlaylistShelf(page.playlists) { onAction(LibraryAction.OpenPlaylist(it)) } }
            }
        }
    }
}

/** A failed optional shelf keeps the page usable and offers the page retry. */
@Composable
private fun ShelfRetry(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText(message, Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
        PillButton("Retry", onRetry)
    }
}

@Composable
private fun ShelfHeader(label: String, onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText(label, Modifier.weight(1f), size = 16, line = 22, weight = FontWeight.Medium)
        Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSeeAll).padding(horizontal = 12.dp, vertical = 6.dp)) {
            AppText("View all", size = 14, line = 20, weight = FontWeight.Medium)
        }
    }
}

/** The watch history page. */
@Composable
fun HistoryScreen(state: HistoryUiState, onAction: (LibraryAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    BoxWithConstraints(modifier.fillMaxSize().background(DesignTokens.Background).testTag("history-screen")) {
        val width = maxWidth.value
        Column(Modifier.fillMaxSize()) {
        LibrarySubPageBar("History", onBack, onAction, trailing = if (!state.live) null else ({
            AppIconButton(AppIcon.More, "History settings", diameter = 48.dp,
                modifier = Modifier.testTag("history-manage")) { onAction(LibraryAction.OpenManageHistory) }
        }))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                HistoryContent.Loading -> LibrarySkeleton("history-skeleton")
                HistoryContent.Failed -> if (state.verification != null) VerificationCard(
                    state.verification,
                    onVerify = { onAction(LibraryAction.OpenVerification(state.verification!!.challengeUrl)) },
                    onDismiss = { onAction(LibraryAction.DismissVerification) },
                    modifier = Modifier.testTag("history-verification"),
                ) else LibraryError("history-error") { onAction(LibraryAction.Retry) }
                is HistoryContent.SignedOut -> LibraryMessageCard(
                    content.prompt.title, content.prompt.message, "history-signed-out",
                ) { PillButton(content.prompt.action) { onAction(LibraryAction.SignIn) } }
                is HistoryContent.Empty -> LibraryMessageCard(content.title, content.message, "history-empty")
                is HistoryContent.Ready -> {
                    val grouped = remember(content) { content.page.entries.groupBy { it.section } }
                    val wide = width >= 600f
                    val entryCount = content.page.entries.size
                    val nearEnd by remember(content) { derivedStateOf {
                        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= entryCount - 2
                    } }
                    androidx.compose.runtime.LaunchedEffect(nearEnd, content.page.nextKey, state.append) {
                        if (nearEnd && content.page.nextKey != null &&
                            state.append == com.hhst.youtubelite.home.domain.AppendState.Idle) {
                            onAction(LibraryAction.LoadHistoryNext)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("history-list"), state = listState,
                        contentPadding = PaddingValues(top = if (wide) 16.dp else 8.dp, bottom = 24.dp)) {
                        item(key = "history-heading") {
                            AppText("History", Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (wide) 12.dp else 8.dp),
                                size = if (wide) 36 else 32, line = if (wide) 50 else 44, weight = FontWeight.Bold)
                        }
                        grouped.forEach { (section, entries) ->
                            item(key = "section-$section") {
                                AppText(section, Modifier.padding(start = 12.dp, end = 12.dp,
                                        top = if (wide) 4.dp else 0.dp, bottom = if (wide) 8.dp else 4.dp),
                                    size = if (wide) 20 else 18, line = if (wide) 28 else 26, weight = FontWeight.Medium)
                            }
                            items(entries, key = { it.id }) { entry ->
                                Box(Modifier.padding(bottom = 12.dp)) {
                                    HistoryCard(entry, compact = !wide,
                                        onOpen = { onAction(LibraryAction.OpenVideo(entry.videoId)) },
                                        onMenu = { onAction(LibraryAction.OpenMenu(entry.videoId)) })
                                }
                            }
                        }
                        item(key = "history-append") { LibraryAppendRow(state.append) { onAction(LibraryAction.LoadHistoryNext) } }
                    }
                }
            }
        }
        }
    }
}

/** The personal playlists collection page. */
@Composable
fun CollectionScreen(state: CollectionUiState,
                     onAction: (LibraryAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val listState = androidx.compose.runtime.saveable.rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    Column(modifier.fillMaxSize().background(DesignTokens.Background).testTag("playlists-screen")) {
        LibrarySubPageBar("Playlists", onBack, onAction)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                CollectionContent.Loading -> LibrarySkeleton("playlists-skeleton")
                CollectionContent.Failed -> if (state.verification != null) VerificationCard(
                    state.verification,
                    onVerify = { onAction(LibraryAction.OpenVerification(state.verification!!.challengeUrl)) },
                    onDismiss = { onAction(LibraryAction.DismissVerification) },
                    modifier = Modifier.testTag("playlists-verification"),
                ) else LibraryError("playlists-error") { onAction(LibraryAction.Retry) }
                is CollectionContent.SignedOut -> LibraryMessageCard(
                    content.prompt.title, content.prompt.message, "playlists-signed-out",
                ) { PillButton(content.prompt.action) { onAction(LibraryAction.SignIn) } }
                is CollectionContent.Empty -> LibraryMessageCard(content.title, content.message, "playlists-empty")
                is CollectionContent.Ready -> {
                    val nearEnd by remember(content) { derivedStateOf {
                        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= content.playlists.size - 2
                    } }
                    androidx.compose.runtime.LaunchedEffect(nearEnd, content.nextKey, state.append) {
                        if (nearEnd && content.nextKey != null &&
                            state.append == com.hhst.youtubelite.home.domain.AppendState.Idle) {
                            onAction(LibraryAction.LoadCollectionNext)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("playlists-list"), state = listState,
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                        items(content.playlists, key = { it.id }) { playlist ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onAction(LibraryAction.OpenPlaylist(playlist.id)) }
                            .testTag("playlists-item-${playlist.id}").padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(12.dp))) {
                                    LibraryArtwork(playlist.artwork, Modifier.fillMaxSize())
                                    Row(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AppIcon(AppIcon.Playlist, Modifier.size(12.dp), color = Color.White)
                                        Spacer(Modifier.width(4.dp))
                                        AppText(playlist.videoCount?.toString() ?: playlist.countLabel.orEmpty(), size = 12, line = 18, color = Color.White, maxLines = 1)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    AppText(playlist.title, size = 16, line = 22, weight = FontWeight.Medium, maxLines = 2)
                                    AppText(listOfNotNull(playlist.owner.takeIf(String::isNotBlank), playlist.countLabel).joinToString(" · "), Modifier.padding(top = 2.dp),
                                        size = 12, line = 18, color = DesignTokens.Secondary, maxLines = 1)
                                }
                            }
                        }
                        }
                        item(key = "playlists-append") { LibraryAppendRow(state.append) { onAction(LibraryAction.LoadCollectionNext) } }
                    }
                }
            }
        }
    }
}

/** Shared tail row for library lists that load further pages. */
@Composable
internal fun LibraryAppendRow(append: com.hhst.youtubelite.home.domain.AppendState, onRetry: () -> Unit) {
    when (append) {
        com.hhst.youtubelite.home.domain.AppendState.Loading -> Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("library-append-loading"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            com.hhst.youtubelite.home.presentation.LoadingSpinner()
        }
        com.hhst.youtubelite.home.domain.AppendState.Failed -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("library-append-failed"),
            verticalAlignment = Alignment.CenterVertically) {
            AppText("Couldn't load more entries.", Modifier.weight(1f), size = 14, line = 20, color = DesignTokens.Secondary)
            AppText("Retry", Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 6.dp), size = 14, line = 20, weight = FontWeight.Medium)
        }
        com.hhst.youtubelite.home.domain.AppendState.Idle -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
internal fun LibrarySubPageBar(title: String, onBack: () -> Unit, onAction: (LibraryAction) -> Unit,
    trailing: (@Composable () -> Unit)? = null) {    Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.TopBarHeight).testTag("library-subpage-bar"), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.Back, "Back", diameter = 48.dp) { onBack() }
        AppText(title, Modifier.weight(1f).padding(end = 16.dp), size = 20, line = 28, weight = FontWeight.Bold, maxLines = 1)

        AppIconButton(AppIcon.Search, "Search YouTube") { onAction(LibraryAction.OpenSearch) }
        trailing?.invoke()
    }
}
