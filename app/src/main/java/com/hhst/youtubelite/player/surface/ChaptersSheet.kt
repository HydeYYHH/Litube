package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.ui.theme.SettingsTokens

/**
 * Current-chapter entry in the bottom info band, next to the position text:
 * chapter title (or the localized Chapters label when the video has only
 * skippable segments), a small chevron, and a light translucent background.
 * Hidden when the video has neither chapters nor skippable segments so the
 * entry never dead-ends.
 */
@Composable
fun ChapterEntryChip(
    chapters: List<Chapter>,
    hasSegments: Boolean,
    positionState: State<Long>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    textSizeSp: Int = PlayerUi.CHROME_TEXT_SP,
) {
    if (chapters.isEmpty() && !hasSegments) return
    // 4 Hz ticks recompute the index; the chip recomposes only when the
    // resolved label actually changes.
    val label by remember(chapters, positionState) {
        derivedStateOf {
            val index = PlayerUi.chapterIndexAt(chapters, positionState.value)
            chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() }
        }
    }
    val chaptersLabel = stringResource(R.string.chapters)
    val text = label ?: chaptersLabel
    Row(
        modifier = modifier
            .semantics { contentDescription = "$chaptersLabel: $text" }
            .clip(RoundedCornerShape(10.dp))
            .background(PlayerUi.HintBg)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 6.dp)
            .heightIn(min = 28.dp)
            .testTag("player-chapter-entry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = textSizeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(14.dp),
        )
    }
}

/**
 * Chapters sheet body: real chapters first, then a clearly separated
 * skippable-segments section (SponsorBlock ranges are not chapters).
 * Lives inside [PlayerModalSheet], which supplies the 16 dp top corners.
 * [onSeek] receives the target and the row label (for the jump hint).
 */
@Composable
fun ChaptersSheet(
    chapters: List<Chapter>,
    segments: List<SponsorBlockManager.Segment>,
    positionState: State<Long>,
    fallbackThumb: String?,
    onSeek: (Long, String) -> Unit,
    onPreviewSeek: (Long, String) -> Unit = onSeek,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<Chapter?>(null) }
    val currentIndex by remember(chapters, positionState) {
        derivedStateOf { PlayerUi.chapterIndexAt(chapters, positionState.value) }
    }
    val previewChapter = preview
    if (previewChapter != null) {
        val startMs = previewChapter.startSeconds * 1000L
        val title = previewChapter.title.ifBlank { stringResource(R.string.chapters) }
        SegmentPreviewDialog(
            title = title,
            timeLabel = PlayerUi.formatTime(startMs),
            thumbnailUrl = previewChapter.previewUrl ?: fallbackThumb,
            onJump = {
                onPreviewSeek(startMs, title)
                preview = null
            },
            onDismiss = { preview = null },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chapters),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            fontSize = SettingsTokens.TitleSize.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PlayerIconButton(
            icon = R.drawable.ic_close,
            contentDescription = stringResource(R.string.close),
            onClick = onDismiss,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("chapters-sheet-close"),
        )
    }
    val listState = rememberLazyListState()
    // Locate the current chapter once on open; later scrolls are the user's.
    LaunchedEffect(Unit) {
        val index = PlayerUi.chapterIndexAt(chapters, positionState.value)
        if (index > 0) listState.scrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chapters-sheet-list"),
    ) {
        if (chapters.isEmpty() && segments.isEmpty()) {
            item("empty") {
                Text(
                    text = stringResource(R.string.no_chapters),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = SettingsTokens.BodySize.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
        itemsIndexed(chapters, key = { index, chapter -> "chapter-$index-${chapter.startSeconds}" }) { index, chapter ->
            ChapterRow(
                chapter = chapter,
                current = index == currentIndex,
                onSeek = onSeek,
                onPreview = { preview = chapter },
            )
        }
        if (segments.isNotEmpty()) {
            item("segments-header") {
                Text(
                    text = stringResource(R.string.skippable_segments),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = SettingsTokens.DetailSize.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(segments, key = { index, segment -> "segment-$index-${segment.startMs}" }) { _, segment ->
                SegmentRow(segment = segment, onSeek = onSeek)
            }
        }
    }
}

/**
 * One compact chapter line: timestamp and title only. Per-row preview thumbnails
 * made the list long enough to hide half the chapters; the thumbnail now loads
 * only when the long-press preview is actually opened.
 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    current: Boolean,
    onSeek: (Long, String) -> Unit,
    onPreview: () -> Unit,
) {
    val startMs = chapter.startSeconds * 1000L
    val title = chapter.title.ifBlank { stringResource(R.string.chapters) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (current) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { onSeek(startMs, title) }, onLongClick = onPreview)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chapter-row-${chapter.startSeconds}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = PlayerUi.formatTime(startMs),
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = SettingsTokens.DetailSize.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 44.dp),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = SettingsTokens.BodySize.sp,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SegmentRow(
    segment: SponsorBlockManager.Segment,
    onSeek: (Long, String) -> Unit,
) {
    val label = stringResource(PlayerUi.segmentLabelRes(segment.category))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSeek(segment.startMs, label) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("segment-row-${segment.startMs}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(PlayerUi.segmentColor(segment.category)),
        )
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(iterations = Int.MAX_VALUE),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = SettingsTokens.BodySize.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = PlayerUi.formatTime(segment.startMs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = SettingsTokens.DetailSize.sp,
            maxLines = 1,
        )
    }
}
