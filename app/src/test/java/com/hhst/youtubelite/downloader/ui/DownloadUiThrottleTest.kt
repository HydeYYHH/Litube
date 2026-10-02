package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.FileAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadUiThrottleTest {

    @Test
    fun progressUpdates_atMostFourPerSecond_criticalImmediate() = runTest {
        val src = MutableStateFlow(listOf(item("t", progress = 0)))
        val out = mutableListOf<List<DownloadItemUiState>>()
        val job = launch {
            src.uiThrottle(windowMs = 250L, clock = { testClock() }, critical = ::progressIsCritical)
                .collect { out += it }
        }
        src.value = listOf(item("t", progress = 10))
        src.value = listOf(item("t", progress = 20))
        advanceTimeBy(50)
        src.value = listOf(item("t", progress = 30, status = DownloadStatus.PAUSED))
        advanceTimeBy(300)
        job.cancel()
        assertTrue(out.size >= 2)
        assertEquals(
            DownloadStatus.PAUSED,
            out.last().single().status,
        )
    }

    private var now = 0L
    private fun testClock(): Long = now.also { now += 10 }

    private fun item(
        id: String,
        progress: Long,
        status: DownloadStatus =
            DownloadStatus.RUNNING,
    ) = DownloadItemUiState(
        taskId = id,
        videoId = "abcdefghijk",
        title = "T",
        author = null,
        thumbnailUrl = null,
        phase = DownloadPhase.TRANSFER,
        status = status,
        fileAvailability = FileAvailability.MISSING,
        completion = CompletionKind.NONE,
        fullyDownloaded = false,
        progressBytes = progress,
    )
}
