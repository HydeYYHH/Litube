package com.hhst.youtubelite.downloader.webview

import com.google.gson.Gson
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadLimits
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.ui.DownloadItemUiState
import com.hhst.youtubelite.downloader.ui.VideoDownloadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWebGuardTest {

    @Test
    fun httpsYoutubeOrigin_allowed_httpAndGoogleRejected() {
        assertTrue(DownloadWebGuard.isAllowedOrigin("https://m.youtube.com"))
        assertTrue(DownloadWebGuard.isAllowedOrigin("https://www.youtube.com"))
        assertTrue(DownloadWebGuard.isAllowedOrigin("https://youtu.be"))
        assertFalse(DownloadWebGuard.isAllowedOrigin("http://m.youtube.com"))
        assertFalse(DownloadWebGuard.isAllowedOrigin("https://accounts.google.com"))
        assertFalse(DownloadWebGuard.isAllowedOrigin("https://evil.com"))
    }

    @Test
    fun iframeRejected() {
        val decision = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = false,
            currentTabId = 1,
            currentPageGeneration = 1,
            rawJson = openConfirm(1, 1),
        )
        assertTrue(decision is DownloadWebDecision.Error)
        assertEquals("iframe", (decision as DownloadWebDecision.Error).code)
    }

    @Test
    fun stalePageAndTabGeneration_ignored() {
        val stalePage = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = true,
            currentTabId = 1,
            currentPageGeneration = 4,
            rawJson = openConfirm(pageGen = 3, tabId = 1),
        )
        val staleTab = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = true,
            currentTabId = 2,
            currentPageGeneration = 4,
            rawJson = openConfirm(pageGen = 4, tabId = 1),
        )
        assertEquals(DownloadWebDecision.Ignore, stalePage)
        assertEquals(DownloadWebDecision.Ignore, staleTab)
    }

    @Test
    fun over500Items_rejectedWithError_notTruncated() {
        val items = (0 until 501).joinToString(",") { i ->
            val id = "v$i".padEnd(11, 'x').take(11)
            """{"videoId":"$id","title":"t"}"""
        }
        val json = """{"type":"openBatch","pageGeneration":1,"tabId":1,"name":"p","items":[$items]}"""
        val decision = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = true,
            currentTabId = 1,
            currentPageGeneration = 1,
            rawJson = json,
        )
        assertTrue(decision is DownloadWebDecision.Error)
        assertEquals("too_many_items", (decision as DownloadWebDecision.Error).code)
    }

    @Test
    fun over1MiB_rejectedWithError_notTruncated() {
        val huge = "x".repeat(DownloadLimits.MAX_SNAPSHOT_BYTES + 8)
        val json = """{"type":"openConfirm","pageGeneration":1,"tabId":1,"videoId":"abcdefghijk","title":"$huge"}"""
        val decision = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = true,
            currentTabId = 1,
            currentPageGeneration = 1,
            rawJson = json,
        )
        assertTrue(decision is DownloadWebDecision.Error)
        assertEquals("too_large", (decision as DownloadWebDecision.Error).code)
    }

    @Test
    fun missingListenerFallback_opensNativeSheetOnly() {
        val items = """{"videoId":"abcdefghijk","title":"a"},{"videoId":"bcdefghijkl","title":"b"}"""
        val json = """{"type":"openBatch","pageGeneration":1,"tabId":1,"items":[$items]}"""
        val decision = DownloadWebGuard.decide(
            origin = "https://m.youtube.com",
            isMainFrame = true,
            currentTabId = 1,
            currentPageGeneration = 1,
            rawJson = json,
            allowBatch = false,
        )
        assertTrue(decision is DownloadWebDecision.OpenSingle)
        assertEquals("abcdefghijk", (decision as DownloadWebDecision.OpenSingle).videoId)
    }

    @Test
    fun mutationCommands_ignored() {
        for (type in listOf("enqueue", "cancel", "delete", "pause")) {
            val json = """{"type":"$type","pageGeneration":1,"tabId":1,"videoId":"abcdefghijk"}"""
            val decision = DownloadWebGuard.decide(
                origin = "https://m.youtube.com",
                isMainFrame = true,
                currentTabId = 1,
                currentPageGeneration = 1,
                rawJson = json,
            )
            assertEquals(type, DownloadWebDecision.Ignore, decision)
        }
    }

    @Test
    fun statusPayload_hasNoPaths() {
        val state = VideoDownloadUiState(
            videoId = "abcdefghijk",
            tasks = listOf(
                DownloadItemUiState(
                    taskId = "t",
                    videoId = "abcdefghijk",
                    title = "T",
                    author = "A",
                    thumbnailUrl = null,
                    phase = DownloadPhase.COMPLETE,
                    status = DownloadStatus.QUEUED,
                    fileAvailability = FileAvailability.EXISTS,
                    completion = CompletionKind.FULL,
                    fullyDownloaded = true,
                    watchPageDownloaded = true,
                    publishedUris = listOf("content://secret/file.mp4"),
                ),
            ),
            watchPageDownloaded = true,
        )
        val json = DownloadWebStatus.json(state)
        assertFalse(DownloadWebStatus.containsPath(json))
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("publishedUri"))
        @Suppress("UNCHECKED_CAST")
        val map = Gson().fromJson(json, Map::class.java) as Map<String, Any?>
        assertEquals("status", map["type"])
        assertEquals("complete", map["state"])
        assertEquals(true, map["watchPageDownloaded"])
        assertFalse(map.containsKey("publishedUris"))
    }

    @Test
    fun nativeCollect_ignoresGeneration_andDoesNotTruncate() {
        val items = (0 until 501).joinToString(",") { i ->
            val id = "v$i".padEnd(11, 'x').take(11)
            """{"videoId":"$id","title":"t"}"""
        }
        val json = """{"type":"openBatch","name":"p","items":[$items]}"""
        val snapshot = DownloadWebGuard.snapshotFromCollect(json)
        assertEquals(501, snapshot!!.items.size)
        assertEquals("p", snapshot.name)
    }

    private fun openConfirm(pageGen: Long, tabId: Long): String =
        """{"type":"openConfirm","pageGeneration":$pageGen,"tabId":$tabId,"videoId":"abcdefghijk","title":"T"}"""
}
