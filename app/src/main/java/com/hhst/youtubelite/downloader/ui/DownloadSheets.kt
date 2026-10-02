package com.hhst.youtubelite.downloader.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import com.hhst.youtubelite.player.datasource.SubtitleSelection

/**
 * Batch rows have no per-video catalog, so the subtitle picker offers common
 * YouTube language codes. Language-only keys keep the selector's fallback
 * (human captions first); per-track precision stays on the single-video sheet.
 */
private val BatchSubtitleLanguages =
    listOf("en", "ja", "ko", "zh", "zh-Hant", "de", "fr", "es", "pt", "ru", "tr", "hi", "ar")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleVideoConfirmSheet(
    videoId: String,
    title: String,
    author: String?,
    thumbnailUrl: String?,
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit,
    onSubmitted: (EnqueueResult) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val last = viewModel.prefs.lastConfig()
    var audioOnly by rememberSaveable { mutableStateOf(last.audioOnly) }
    var quality by rememberSaveable { mutableStateOf(last.videoQuality ?: viewModel.prefs.defaultQuality()) }
    var more by rememberSaveable { mutableStateOf(false) }
    var audioTrack by rememberSaveable { mutableStateOf(last.audioTrack.orEmpty()) }
    var includeSubtitle by rememberSaveable { mutableStateOf(last.includeSubtitle) }
    var subtitleLanguage by rememberSaveable { mutableStateOf(last.subtitleLanguage.orEmpty()) }
    var includeCover by rememberSaveable { mutableStateOf(last.includeCover) }
    var fileName by rememberSaveable { mutableStateOf(last.fileNameTemplate.orEmpty()) }
    var attachmentsOnly by rememberSaveable { mutableStateOf(last.attachmentsOnly) }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var reasonKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sizeKind by rememberSaveable { mutableStateOf(SizeKind.UNKNOWN.name) }
    var sizeBytes by rememberSaveable { mutableStateOf(-1L) }
    var catalogTitle by rememberSaveable { mutableStateOf(title) }
    var catalogAuthor by rememberSaveable { mutableStateOf(author.orEmpty()) }
    var qualities by rememberSaveable { mutableStateOf(listOf(quality)) }
    var audioLabels by rememberSaveable { mutableStateOf(listOf<String>()) }
    var audioKeys by rememberSaveable { mutableStateOf(listOf<String>()) }
    var subLabels by rememberSaveable { mutableStateOf(listOf<String>()) }
    var subKeys by rememberSaveable { mutableStateOf(listOf<String>()) }

    val config = DownloadConfig(
        videoQuality = quality,
        audioOnly = audioOnly && !attachmentsOnly,
        audioTrack = audioTrack.takeIf { it.isNotBlank() },
        includeSubtitle = includeSubtitle,
        subtitleLanguage = subtitleLanguage.takeIf { it.isNotBlank() },
        includeCover = includeCover,
        fileNameTemplate = fileName.takeIf { it.isNotBlank() },
        attachmentsOnly = attachmentsOnly,
    )

    // Candidate display must not depend on selection success: a first preview
    // that fails (ambiguous/missing audio track) still has a catalog with the
    // tracks the user can pick to unblock the download.
    fun applyCatalog(catalog: DownloadCatalog) {
        catalogTitle = catalog.title.ifBlank { catalogTitle }
        catalogAuthor = catalog.author ?: catalogAuthor
        val choices = AudioTrackIdentity.choices(catalog.formats)
        audioKeys = choices.map { it.key }
        audioLabels = choices.map { it.label }
        subKeys = catalog.subtitles.map { SubtitleSelection.key(it.languageCode, it.autoGenerated) }
        subLabels = catalog.subtitles.map {
            val tag = it.languageCode
            if (it.autoGenerated) "$tag (auto)" else tag
        }
    }

    LaunchedEffect(videoId, config) {
        when (val state = viewModel.preview(videoId, config)) {
            is DownloadViewModel.PreviewState.Ready -> {
                reason = null
                reasonKey = null
                qualities = state.qualities.ifEmpty { qualities }
                sizeKind = state.size.kind.name
                sizeBytes = state.size.bytes ?: -1L
                applyCatalog(state.catalog)
            }
            is DownloadViewModel.PreviewState.Unavailable -> {
                reason = state.message
                reasonKey = state.reason.name
                state.catalog?.let { catalog ->
                    qualities = DownloadPresentation.qualityOptions(catalog).ifEmpty { qualities }
                    applyCatalog(catalog)
                }
            }
        }
    }
    LaunchedEffect(reasonKey) {
        // Audio failures are only recoverable from the advanced options; open
        // them so the track chips are visible without hunting.
        if (reasonKey == DownloadUnavailableReason.AUDIO_TRACK_AMBIGUOUS.name ||
            reasonKey == DownloadUnavailableReason.AUDIO_LANGUAGE_UNAVAILABLE.name
        ) {
            more = true
        }
    }

    val existing = remember { mutableStateOf<DownloadItemUiState?>(null) }
    LaunchedEffect(videoId) {
        viewModel.observeVideo(videoId).collect { video ->
            existing.value = video.tasks.firstOrNull()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val canDownload = reason == null
    val downloadLabel = stringResource(R.string.download)
    // Embedded hosts (tests) already wrap the body in their own scroll; only
    // the ModalBottomSheet path needs internal scrolling to keep the submit
    // button reachable on small screens.
    val bodyScroll = if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())
    val sheetBody: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .then(bodyScroll)
                .navigationBarsPadding()
                .padding(horizontal = DownloadTokens.PageInset),
        ) {
            if (landscape) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    DownloadThumbnail(thumbnailUrl, Modifier.weight(1f).padding(end = 12.dp))
                    Column(Modifier.weight(1.4f)) {
                        SheetIdentity(catalogTitle, catalogAuthor)
                        Spacer(Modifier.height(12.dp))
                        MediaToggle(audioOnly = audioOnly, onChange = { audioOnly = it; attachmentsOnly = false })
                    }
                }
            } else {
                DownloadThumbnail(thumbnailUrl, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                SheetIdentity(catalogTitle, catalogAuthor)
                Spacer(Modifier.height(14.dp))
                MediaToggle(audioOnly = audioOnly, onChange = { audioOnly = it; attachmentsOnly = false })
            }
            Spacer(Modifier.height(4.dp))
            SectionLabel(stringResource(R.string.download_quality))
            QualityChipRow(quality, qualities) { quality = it }
            SizeRow(SizeKind.valueOf(sizeKind), sizeBytes.takeIf { it >= 0L })
            reason?.let {
                Text(
                    text = unavailableCopy(reasonKey),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            existing.value?.let { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(phaseString(DownloadPresentation.phaseCopy(item))),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.redownload(DownloadTarget.Task(item.taskId)) }) {
                        Text(stringResource(R.string.download_redownload))
                    }
                }
            }
            MoreOptionsHandle(open = more, onToggle = { more = !more })
            if (more) {
                MoreOptions(
                    audioKeys = audioKeys,
                    audioLabels = audioLabels,
                    audioTrack = audioTrack,
                    onAudioTrack = { audioTrack = it },
                    subKeys = subKeys,
                    subLabels = subLabels,
                    includeSubtitle = includeSubtitle,
                    subtitleLanguage = subtitleLanguage,
                    onSubtitle = { include, lang ->
                        includeSubtitle = include
                        subtitleLanguage = lang
                    },
                    includeCover = includeCover,
                    onCover = { includeCover = it },
                    fileName = fileName,
                    onFileName = { fileName = it },
                    attachmentsOnly = attachmentsOnly,
                    onAttachments = {
                        attachmentsOnly = it
                        if (it) audioOnly = false
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            DownloadCapsuleButton(
                text = downloadLabel,
                iconRes = R.drawable.ic_download,
                onClick = {
                    scope.launch {
                        val result = viewModel.confirmSingle(
                            DownloadRequest(
                                videoId = videoId,
                                title = catalogTitle.ifBlank { title }.ifBlank { videoId },
                                author = catalogAuthor.ifBlank { author },
                                thumbnailUrl = thumbnailUrl,
                                config = config,
                            ),
                        )
                        onSubmitted(result)
                    }
                },
                enabled = canDownload,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = downloadLabel,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    if (embedded) {
        sheetBody()
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = DownloadTokens.SheetCorner, topEnd = DownloadTokens.SheetCorner),
        ) {
            sheetBody()
        }
    }
}

/** Small caps-style section header used across the download surfaces. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetIdentity(title: String, author: String) {
    Text(
        text = title,
        maxLines = DownloadTokens.TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (author.isNotBlank()) {
        Text(
            text = author,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Video / Audio picker: one modern M3 segmented control, not two loose chips. */
@Composable
private fun MediaToggle(audioOnly: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !audioOnly,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.download_video)) },
        )
        SegmentedButton(
            selected = audioOnly,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.download_audio)) },
        )
    }
}

@Composable
private fun QualityChipRow(quality: String, options: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            DownloadFilterChip(option == quality, option) { onPick(option) }
        }
    }
}

@Composable
private fun SizeRow(kind: SizeKind, bytes: Long?) {
    val formatted = bytes?.let { DownloadPresentation.formatBytes(it) }
    val text = when (kind) {
        SizeKind.EXACT -> formatted ?: stringResource(R.string.download_size_unknown)
        SizeKind.ESTIMATE -> formatted?.let { stringResource(R.string.download_size_estimate, it) }
            ?: stringResource(R.string.download_size_unknown)
        SizeKind.UNKNOWN -> stringResource(R.string.download_size_unknown)
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** Full-width expander for the advanced block, with a rotating chevron. */
@Composable
private fun MoreOptionsHandle(open: Boolean, onToggle: () -> Unit) {
    val moreLabel = stringResource(R.string.download_more_options)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadTokens.MinTouch)
            .semantics { contentDescription = moreLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = moreLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggle) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (open) 90f else 0f),
            )
        }
    }
}

@Composable
private fun MoreOptions(
    audioKeys: List<String>,
    audioLabels: List<String>,
    audioTrack: String,
    onAudioTrack: (String) -> Unit,
    subKeys: List<String>,
    subLabels: List<String>,
    includeSubtitle: Boolean,
    subtitleLanguage: String,
    onSubtitle: (Boolean, String) -> Unit,
    includeCover: Boolean,
    onCover: (Boolean) -> Unit,
    fileName: String,
    onFileName: (String) -> Unit,
    attachmentsOnly: Boolean,
    onAttachments: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (audioKeys.isNotEmpty()) {
            SectionLabel(stringResource(R.string.audio_track))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                audioKeys.zip(audioLabels).forEach { (key, label) ->
                    DownloadFilterChip(audioTrack == key, label) { onAudioTrack(key) }
                }
            }
        }
        OptionSwitch(stringResource(R.string.subtitles), includeSubtitle) {
            onSubtitle(it, subtitleLanguage.ifBlank { subKeys.firstOrNull().orEmpty() })
        }
        if (includeSubtitle && subKeys.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                subKeys.zip(subLabels).forEach { (key, label) ->
                    DownloadFilterChip(subtitleLanguage == key, label) { onSubtitle(true, key) }
                }
            }
        }
        OptionSwitch(stringResource(R.string.download_cover), includeCover, onCover)
        OutlinedTextField(
            value = fileName,
            onValueChange = onFileName,
            label = { Text(stringResource(R.string.download_filename)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        OptionSwitch(stringResource(R.string.download_attachments_only), attachmentsOnly, onAttachments)
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadTokens.MinTouch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun unavailableCopy(reasonKey: String?): String {
    val reason = reasonKey?.let { runCatching { DownloadUnavailableReason.valueOf(it) }.getOrNull() }
    return when (reason) {
        DownloadUnavailableReason.LIVE -> stringResource(R.string.download_reason_live)
        DownloadUnavailableReason.PREMIERE_UNSTARTED -> stringResource(R.string.download_reason_premiere)
        DownloadUnavailableReason.NO_FILE_STREAMS -> stringResource(R.string.download_reason_no_streams)
        DownloadUnavailableReason.QUALITY_UNAVAILABLE -> stringResource(R.string.download_reason_quality)
        DownloadUnavailableReason.AUDIO_TRACK_AMBIGUOUS -> stringResource(R.string.download_reason_audio_ambiguous)
        DownloadUnavailableReason.AUDIO_LANGUAGE_UNAVAILABLE -> stringResource(R.string.download_reason_audio_missing)
        DownloadUnavailableReason.SUBTITLE_LANGUAGE_UNAVAILABLE -> stringResource(R.string.download_reason_subtitle_missing)
        DownloadUnavailableReason.CODEC_NOT_ENABLED -> stringResource(R.string.download_reason_codec)
        null -> stringResource(R.string.download_reason_no_streams)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConfirmSheet(
    snapshot: BatchSnapshot,
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit,
    onSubmitted: (EnqueueResult) -> Unit,
    onRejected: (String) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val reject = viewModel.validateSnapshot(snapshot)
    LaunchedEffect(reject) {
        if (reject != null) onRejected(reject.message)
    }
    if (reject != null) return

    var selected by rememberSaveable { mutableStateOf(snapshot.items.indices.toSet()) }
    val last = viewModel.prefs.lastConfig()
    var audioOnly by rememberSaveable { mutableStateOf(last.audioOnly) }
    var quality by rememberSaveable { mutableStateOf(last.videoQuality ?: viewModel.prefs.defaultQuality()) }
    var includeSubtitle by rememberSaveable { mutableStateOf(last.includeSubtitle) }
    var subtitleLanguage by rememberSaveable { mutableStateOf(last.subtitleLanguage.orEmpty()) }
    var includeCover by rememberSaveable { mutableStateOf(last.includeCover) }
    var attachmentsOnly by rememberSaveable { mutableStateOf(last.attachmentsOnly) }

    val config = DownloadConfig(
        videoQuality = quality,
        audioOnly = audioOnly && !attachmentsOnly,
        includeSubtitle = includeSubtitle,
        subtitleLanguage = subtitleLanguage.takeIf { it.isNotBlank() },
        includeCover = includeCover,
        fileNameTemplate = last.fileNameTemplate,
        attachmentsOnly = attachmentsOnly,
    )
    val downloadLabel = stringResource(R.string.download)
    // Same embedded/host distinction as SingleVideoConfirmSheet above.
    val bodyScroll = if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())
    val sheetBody: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .then(bodyScroll)
                .navigationBarsPadding()
                .padding(horizontal = DownloadTokens.PageInset),
        ) {
            Text(
                snapshot.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.download_batch_meta, snapshot.items.size, snapshot.source.name),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = DownloadTokens.MinTouch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected.size == snapshot.items.size,
                    onCheckedChange = {
                        selected = if (it) snapshot.items.indices.toSet() else emptySet()
                    },
                )
                Text(stringResource(R.string.download_select_all))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.download_selected_count, selected.size, snapshot.items.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                snapshot.items.forEachIndexed { index, request ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = DownloadTokens.MinTouch),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = index in selected,
                            onCheckedChange = {
                                selected = if (it) selected + index else selected - index
                            },
                        )
                        DownloadThumbnail(request.thumbnailUrl, Modifier.width(64.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                request.title.ifBlank { request.videoId },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            request.author?.let {
                                Text(
                                    it,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MediaToggle(audioOnly) { audioOnly = it; attachmentsOnly = false }
            SectionLabel(stringResource(R.string.download_quality))
            QualityChipRow(quality, listOf("144p", "360p", "480p", "720p", "1080p", "1440p", "2160p")) {
                quality = it
            }
            OptionSwitch(stringResource(R.string.subtitles), includeSubtitle) {
                includeSubtitle = it
                if (it && subtitleLanguage.isBlank()) subtitleLanguage = BatchSubtitleLanguages.first()
            }
            if (includeSubtitle) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BatchSubtitleLanguages.forEach { language ->
                        DownloadFilterChip(subtitleLanguage == language, language) { subtitleLanguage = language }
                    }
                }
            }
            OptionSwitch(stringResource(R.string.download_cover), includeCover) { includeCover = it }
            OptionSwitch(stringResource(R.string.download_attachments_only), attachmentsOnly) {
                attachmentsOnly = it
                if (it) audioOnly = false
            }
            Spacer(Modifier.height(12.dp))
            DownloadCapsuleButton(
                text = downloadLabel,
                iconRes = R.drawable.ic_download,
                onClick = {
                    scope.launch {
                        val frozen = snapshot.copy(config = config, items = snapshot.items.map { it.copy(config = config) })
                        val result = viewModel.confirmBatch(frozen, BatchSelection(selected))
                        onSubmitted(result)
                    }
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                contentDescription = downloadLabel,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    if (embedded) {
        sheetBody()
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = DownloadTokens.SheetCorner, topEnd = DownloadTokens.SheetCorner),
        ) {
            sheetBody()
        }
    }
}
