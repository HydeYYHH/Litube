package com.hhst.youtubelite.downloader.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import com.hhst.youtubelite.player.datasource.SubtitleSelection

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
    var existingTaskId by rememberSaveable { mutableStateOf<String?>(null) }

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

    LaunchedEffect(videoId, config) {
        when (val state = viewModel.preview(videoId, config)) {
            is DownloadViewModel.PreviewState.Ready -> {
                reason = null
                reasonKey = null
                catalogTitle = state.catalog.title.ifBlank { catalogTitle }
                catalogAuthor = state.catalog.author ?: catalogAuthor
                qualities = state.qualities.ifEmpty { qualities }
                sizeKind = state.size.kind.name
                sizeBytes = state.size.bytes ?: -1L
                audioKeys = AudioTrackIdentity.choices(state.catalog.formats).map { it.key }
                audioLabels = AudioTrackIdentity.choices(state.catalog.formats).map { it.label }
                subKeys = state.catalog.subtitles.map { SubtitleSelection.key(it.languageCode, it.autoGenerated) }
                subLabels = state.catalog.subtitles.map {
                    val tag = it.languageCode
                    if (it.autoGenerated) "$tag (auto)" else tag
                }
            }
            is DownloadViewModel.PreviewState.Unavailable -> {
                reason = state.message
                reasonKey = state.reason.name
                state.catalog?.let { catalog ->
                    catalogTitle = catalog.title.ifBlank { catalogTitle }
                    qualities = DownloadPresentation.qualityOptions(catalog).ifEmpty { qualities }
                }
            }
        }
    }

    val existing = androidx.compose.runtime.remember { mutableStateOf<DownloadItemUiState?>(null) }
    LaunchedEffect(videoId) {
        viewModel.observeVideo(videoId).collect { video ->
            existing.value = video.tasks.firstOrNull()
            existingTaskId = existing.value?.taskId
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val canDownload = reason == null
    val downloadLabel = stringResource(R.string.download)
    val sheetBody: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = DownloadTokens.PageInset),
        ) {
            if (landscape) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    DownloadThumbnail(Modifier.weight(1f).padding(end = 12.dp))
                    Column(Modifier.weight(1.4f)) {
                        SheetIdentity(catalogTitle, catalogAuthor)
                        MediaToggle(audioOnly = audioOnly, onChange = { audioOnly = it; attachmentsOnly = false })
                    }
                }
            } else {
                DownloadThumbnail(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                SheetIdentity(catalogTitle, catalogAuthor)
                MediaToggle(audioOnly = audioOnly, onChange = { audioOnly = it; attachmentsOnly = false })
            }
            QualityRow(quality, qualities) { quality = it }
            SizeRow(SizeKind.valueOf(sizeKind), sizeBytes.takeIf { it >= 0L })
            reason?.let {
                Text(
                    text = unavailableCopy(reasonKey),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            existing.value?.let { item ->
                Text(
                    text = stringResource(phaseString(DownloadPresentation.phaseCopy(item))),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                TextButton(onClick = { viewModel.redownload(DownloadTarget.Task(item.taskId)) }) {
                    Text(stringResource(R.string.download_redownload))
                }
            }
            TextButton(onClick = { more = !more }) {
                Text(stringResource(R.string.download_more_options))
            }
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
            Spacer(Modifier.height(8.dp))
            DownloadCapsuleButton(
                text = downloadLabel,
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

@Composable
private fun SheetIdentity(title: String, author: String) {
    Text(
        text = title,
        maxLines = DownloadTokens.TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (author.isNotBlank()) {
        Text(
            text = author,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaToggle(audioOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadFilterChip(!audioOnly, stringResource(R.string.download_video)) { onChange(false) }
        DownloadFilterChip(audioOnly, stringResource(R.string.download_audio)) { onChange(true) }
    }
}

@Composable
private fun QualityRow(quality: String, options: List<String>, onPick: (String) -> Unit) {
    Text(
        text = stringResource(R.string.download_quality),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        modifier = Modifier.padding(vertical = 8.dp),
    )
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
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(stringResource(R.string.audio_track), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            audioKeys.zip(audioLabels).forEach { (key, label) ->
                DownloadFilterChip(audioTrack == key, label) { onAudioTrack(key) }
            }
        }
        OptionSwitch(stringResource(R.string.subtitles), includeSubtitle) {
            onSubtitle(it, subtitleLanguage.ifBlank { subKeys.firstOrNull().orEmpty() })
        }
        if (includeSubtitle) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var includeCover by rememberSaveable { mutableStateOf(last.includeCover) }

    val config = DownloadConfig(
        videoQuality = quality,
        audioOnly = audioOnly,
        includeSubtitle = includeSubtitle,
        subtitleLanguage = last.subtitleLanguage,
        includeCover = includeCover,
        fileNameTemplate = last.fileNameTemplate,
    )
    val downloadLabel = stringResource(R.string.download)
    val sheetBody: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = DownloadTokens.PageInset),
        ) {
            Text(
                snapshot.name,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.download_batch_meta, snapshot.items.size, snapshot.source.name),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(stringResource(R.string.download_selected_count, selected.size, snapshot.items.size))
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
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
                        Column(Modifier.weight(1f)) {
                            Text(request.title.ifBlank { request.videoId }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            request.author?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            MediaToggle(audioOnly) { audioOnly = it }
            QualityRow(quality, listOf("144p", "360p", "480p", "720p", "1080p", "1440p", "2160p")) { quality = it }
            OptionSwitch(stringResource(R.string.subtitles), includeSubtitle) { includeSubtitle = it }
            OptionSwitch(stringResource(R.string.download_cover), includeCover) { includeCover = it }
            DownloadCapsuleButton(
                text = downloadLabel,
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
