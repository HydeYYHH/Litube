package com.hhst.youtubelite.downloader.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** At most 4 UI updates/s unless [critical] (status/phase) which emit immediately. */
internal fun <T> Flow<T>.uiThrottle(
    windowMs: Long = 250L,
    clock: () -> Long = { System.currentTimeMillis() },
    critical: (previous: T?, next: T) -> Boolean,
): Flow<T> = channelFlow {
    var lastEmitted: T? = null
    var lastTime = 0L
    var flushJob: Job? = null
    collect { value ->
        val now = clock()
        val emitNow = lastEmitted == null ||
            critical(lastEmitted, value) ||
            now - lastTime >= windowMs
        flushJob?.cancel()
        if (emitNow) {
            lastEmitted = value
            lastTime = now
            send(value)
        } else {
            val wait = (windowMs - (now - lastTime)).coerceAtLeast(1L)
            flushJob = launch {
                delay(wait)
                lastEmitted = value
                lastTime = clock()
                send(value)
            }
        }
    }
}

internal fun progressIsCritical(
    previous: List<DownloadItemUiState>?,
    next: List<DownloadItemUiState>,
): Boolean {
    if (previous == null) return true
    if (previous.size != next.size) return true
    if (previous.map { it.taskId } != next.map { it.taskId }) return true
    val prevById = previous.associateBy { it.taskId }
    return next.any { item ->
        val old = prevById[item.taskId] ?: return@any true
        old.phase != item.phase ||
            old.status != item.status ||
            old.completion != item.completion ||
            old.fileAvailability != item.fileAvailability ||
            old.fullyDownloaded != item.fullyDownloaded ||
            old.fileMissing != item.fileMissing ||
            old.watchPageDownloaded != item.watchPageDownloaded
    }
}
