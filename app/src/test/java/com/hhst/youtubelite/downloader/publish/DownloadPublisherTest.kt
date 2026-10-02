package com.hhst.youtubelite.downloader.publish

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DeleteResult
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.io.FreeSpace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadPublisherTest {

    @Test
    fun publishWriteVerifyComplete_thenOpenableUri() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val publisher = DownloadPublisherImpl(backend)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(16) { 3 }) }
        val result = publisher.publish(
            PublishRequest(
                publishId = "p1",
                assetId = "a1",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                source = source,
            ),
        ) as PublishResult.Published
        assertTrue(result.uri.startsWith("content://"))
        val published = backend.fileFor(result.uri)!!
        assertTrue(published.isFile)
        assertEquals(16, published.length().toInt())
        assertFalse(File(dir, "clip.mp4.pending").exists())
    }

    @Test
    fun incompletePublish_isReconciledWithoutDuplicate() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val publisher = DownloadPublisherImpl(backend)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(8) { 1 }) }
        val first = publisher.publish(
            PublishRequest("p1", "a1", "clip.mp4", "video/mp4", source),
        ) as PublishResult.Published
        val second = publisher.publish(
            PublishRequest(
                publishId = "p1",
                assetId = "a1",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                source = source,
                existingUri = first.uri,
                existingPhase = PublishPhase.IN_PROGRESS,
            ),
        ) as PublishResult.Published
        assertEquals(first.uri, second.uri)
        assertEquals(1, dir.listFiles { f -> f.extension == "mp4" }!!.size)
    }

    @Test
    fun interruptBeforeComplete_retryUsesSameName() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(4) { 9 }) }
        val pending = File(dir, "clip.mp4.pending").also { it.writeBytes(ByteArray(2)) }
        val uri = "content://com.hhst.youtubelite.download.fileprovider/downloads/clip.mp4"
        val publisher = DownloadPublisherImpl(backend)
        val result = publisher.publish(
            PublishRequest(
                publishId = "p1",
                assetId = "a1",
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                source = source,
                existingUri = uri,
                existingPhase = PublishPhase.IN_PROGRESS,
            ),
        ) as PublishResult.Published
        assertTrue(backend.fileFor(result.uri)!!.length() == 4L)
        assertFalse(pending.exists())
    }

    @Test
    fun deleteFailure_isRetryableWithReason() = runBlocking {
        val h = DownloadHarness()
        h.publisher.failDelete = true
        h.publisher.failReason = "busy"
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.remove(DownloadTarget.Task(id), RemoveMode.RECORD_AND_FILES)
        val snap = h.repo.transact { snapshot(id) }!!
        val publish = snap.assets.single().publish!!
        assertEquals(PublishPhase.DELETE_INTENT, publish.phase)
        assertEquals("busy", publish.errorMessage)
        assertEquals(listOf("file://a.mp4"), h.publisher.deleted)
        h.publisher.failDelete = false
        h.coordinator.remove(DownloadTarget.Task(id), RemoveMode.RECORD_AND_FILES)
        val after = h.repo.transact { snapshot(id) }!!
        assertEquals(PublishPhase.DELETED, after.assets.single().publish!!.phase)
    }

    @Test
    fun recordAndFiles_deletesPublishedFile() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val publisher = DownloadPublisherImpl(backend)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(4)) }
        val published = publisher.publish(
            PublishRequest("p1", "a1", "gone.mp4", "video/mp4", source),
        ) as PublishResult.Published
        val file = backend.fileFor(published.uri)!!
        assertTrue(file.isFile)
        assertEquals(DeleteResult.OK, publisher.delete(published.uri))
        assertFalse(file.exists())
    }

    @Test
    fun enospc_isReported() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val publisher = DownloadPublisherImpl(backend, freeSpace = FreeSpace { 1L })
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(64)) }
        val result = publisher.publish(
            PublishRequest("p1", "a1", "big.mp4", "video/mp4", source),
        )
        assertEquals(PublishResult.Failed("ENOSPC"), result)
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dl-pub-${System.nanoTime()}").also { it.mkdirs() }
}
