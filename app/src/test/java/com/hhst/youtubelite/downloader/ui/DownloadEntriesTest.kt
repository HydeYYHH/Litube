package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DownloadLimits
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadEntriesTest {

    @Test
    fun queueToolbar_capsSnapshotAt50() {
        val items = (0 until 60).map { i ->
            item("v$i", "Title $i")
        }
        val snapshot = DownloadEntries.queueSnapshot(items)
        assertEquals(BatchSource.QUEUE, snapshot.source)
        assertEquals(DownloadLimits.NATIVE_QUEUE_CAP, snapshot.items.size)
        assertEquals("v0xxxxxxxxx", snapshot.items.first().videoId)
    }

    @Test
    fun queueItemMenu_singleConfirmUsesKnownMetadata() {
        val queued = item("abc", "Hello", "Chan")
        val spec = DownloadEntries.single(queued)
        assertNotNull(spec)
        assertEquals(queued.videoId, spec!!.videoId)
        assertEquals("Hello", spec.title)
        assertEquals("Chan", spec.author)
        assertEquals(VideoId.thumbnailUrl(spec.videoId), spec.thumbnailUrl)
    }

    @Test
    fun playerMenu_singleConfirmFromPlaybackFields() {
        val spec = DownloadEntries.single(
            videoId = "dQw4w9wgGcQ",
            title = "Never",
            author = "Rick",
            thumbnailUrl = null,
        )
        assertNotNull(spec)
        assertEquals("dQw4w9wgGcQ", spec!!.videoId)
        assertEquals("Never", spec.title)
        assertEquals("Rick", spec.author)
        assertEquals(VideoId.thumbnailUrl("dQw4w9wgGcQ"), spec.thumbnailUrl)
    }

    @Test
    fun single_rejectsUnparseableId() {
        assertNull(DownloadEntries.single(videoId = "nope"))
        assertNull(DownloadEntries.single(QueueItem(videoId = "", url = "https://example.com")))
    }

    private fun item(raw: String, title: String, author: String? = "A"): QueueItem {
        val id = raw.padEnd(11, 'x').take(11)
        return QueueItem(
            videoId = id,
            url = VideoId.watchUrl(id),
            title = title,
            author = author,
            thumbnailUrl = VideoId.thumbnailUrl(id),
        )
    }
}
