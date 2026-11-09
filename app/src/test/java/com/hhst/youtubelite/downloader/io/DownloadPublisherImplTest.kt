package com.hhst.youtubelite.downloader.io

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DeleteResult
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.downloader.core.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadPublisherImplTest {

    @Test
    fun sameNamePublishesKeepBothFilesWhileFirstCompletionIsDelayed() = runBlocking {
        val dir = tempDir()
        val local = LocalPublishBackend(File(dir, "published"))
        val firstAtComplete = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delayFirst = AtomicBoolean(true)
        val backend = object : PublishBackend by local {
            override fun complete(target: PublishTarget) {
                if (delayFirst.compareAndSet(true, false)) {
                    firstAtComplete.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first completion was not released" }
                }
                local.complete(target)
            }
        }
        val publisher = DownloadPublisherImpl(backend)
        val firstSource = File(dir, "first.bin").also { it.writeBytes(ByteArray(32) { 1 }) }
        val secondSource = File(dir, "second.bin").also { it.writeBytes(ByteArray(32) { 2 }) }
        val first = async(Dispatchers.IO) {
            publisher.publish(PublishRequest("p1", "a1", "clip.mp4", "video/mp4", firstSource))
        }
        val second = try {
            assertTrue("first publish did not reach completion", firstAtComplete.await(10, TimeUnit.SECONDS))
            publisher.publish(PublishRequest("p2", "a2", "clip.mp4", "video/mp4", secondSource))
        } finally {
            releaseFirst.countDown()
        }
        val firstResult = first.await()
        assertTrue("first publication failed: $firstResult", firstResult is PublishResult.Published)
        assertTrue("second publication failed: $second", second is PublishResult.Published)
        val firstUri = (firstResult as PublishResult.Published).uri
        val secondUri = (second as PublishResult.Published).uri
        assertFalse(firstUri == secondUri)
        assertArrayEquals(firstSource.readBytes(), local.fileFor(firstUri)!!.readBytes())
        assertArrayEquals(secondSource.readBytes(), local.fileFor(secondUri)!!.readBytes())
    }

    @Test
    fun completionCollisionPreservesExistingFinalAndPendingSource() {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val target = backend.reserveTarget("clip.mp4", "video/mp4")
        val pending = target.file!!
        pending.writeBytes(byteArrayOf(1, 2, 3))
        val finalFile = File(dir, "clip.mp4").also { it.writeBytes(byteArrayOf(4, 5, 6)) }

        assertTrue(runCatching { backend.complete(target) }.isFailure)
        assertArrayEquals(byteArrayOf(4, 5, 6), finalFile.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), pending.readBytes())
    }

    @Test
    fun incompleteRecoveryDoesNotDeleteAnUnrelatedCompletedFile() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val finalFile = File(dir, "clip.mp4").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
        val source = File(dir, "source.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = "content://com.hhst.youtubelite.download.fileprovider/downloads/clip.mp4"

        val result = DownloadPublisherImpl(backend).publish(PublishRequest(
            "p1", "a1", "clip.mp4", "video/mp4", source,
            existingUri = uri, existingPhase = PublishPhase.IN_PROGRESS,
        ))

        assertTrue(result is PublishResult.Published)
        assertArrayEquals(byteArrayOf(4, 5, 6), finalFile.readBytes())
        assertArrayEquals(source.readBytes(), backend.fileFor((result as PublishResult.Published).uri)!!.readBytes())
    }

    @Test
    fun completedRenameIsRecoveredAfterBackendRestartWithoutAnotherCopy() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val source = File(dir, "source.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val target = backend.reserveTarget("clip.mp4", "video/mp4")
        backend.write(target, source)
        backend.complete(target)

        val reopened = DownloadPublisherImpl(LocalPublishBackend(dir), FreeSpace { 0L })
        val result = reopened.publish(PublishRequest(
            "p1", "a1", "clip.mp4", "video/mp4", source,
            existingUri = target.uri, existingPhase = PublishPhase.IN_PROGRESS,
        ))

        assertEquals(PublishResult.Published(target.uri), result)
        assertEquals(listOf("clip.mp4"), dir.listFiles()!!.filter { it.extension == "mp4" }.map { it.name })
    }

    @Test
    fun collisionRetryRegistersAnotherNameAndPreservesTheExistingFile() = runBlocking {
        val dir = tempDir()
        val output = File(dir, "published")
        val local = LocalPublishBackend(output)
        val source = File(dir, "source.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val introduceCollision = AtomicBoolean(true)
        val backend = object : PublishBackend by local {
            override fun complete(target: PublishTarget) {
                if (introduceCollision.compareAndSet(true, false)) {
                    File(output, "clip.mp4").writeBytes(source.readBytes())
                }
                local.complete(target)
            }
        }
        var recordedUri: String? = null
        val request = PublishRequest("p1", "a1", "clip.mp4", "video/mp4", source,
            onTargetCreated = { recordedUri = it })
        val first = DownloadPublisherImpl(backend).publish(request)
        assertEquals(PublishResult.Failed("publish-name-collision"), first)
        assertTrue(source.isFile)
        assertTrue(File(output, "clip.mp4.pending").isFile)
        val firstUri = recordedUri!!

        val reopenedBackend = LocalPublishBackend(output)
        val recovered = DownloadPublisherImpl(reopenedBackend).publish(request.copy(
            existingUri = firstUri, existingPhase = PublishPhase.IN_PROGRESS,
        ))
        assertTrue("collision retry failed: $recovered", recovered is PublishResult.Published)
        assertFalse(firstUri == (recovered as PublishResult.Published).uri)
        assertEquals(recordedUri, recovered.uri)
        assertArrayEquals(source.readBytes(), File(output, "clip.mp4").readBytes())
        assertArrayEquals(source.readBytes(), reopenedBackend.fileFor(recovered.uri)!!.readBytes())
        assertFalse(File(output, "clip.mp4.pending").exists())
    }

    @Test
    fun renameFailureKeepsPendingInsteadOfCopyingOverTheDestination() {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val target = backend.reserveTarget("clip.mp4", "video/mp4")
        val pending = target.file!!.also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val refusedRename = object : File(pending.path) {
            override fun renameTo(dest: File): Boolean = false
        }

        assertTrue(runCatching { backend.complete(target.copy(file = refusedRename)) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), pending.readBytes())
        assertFalse(File(dir, "clip.mp4").exists())
    }

    @Test
    fun encodedFileProviderUriAndLegacyRawUriResolveTheSameFileAfterRestart() {
        val dir = tempDir()
        val name = "中文 空格 %20 %2F #.mp4"
        val file = File(dir, name).also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val authority = "com.hhst.youtubelite.download.fileprovider"
        val encodedUri = "content://$authority" + URI(null, null, "/download_ext/$name", null).toASCIIString()
        val rawUri = "content://$authority/downloads/$name"
        val reopened = LocalPublishBackend(dir)

        assertEquals(file.canonicalFile, reopened.fileFor(encodedUri))
        assertEquals(file.canonicalFile, reopened.fileFor(rawUri))
        assertTrue(reopened.verified(encodedUri, file.length(), FileIntegrity.sha256(file)))
        assertEquals(DeleteResult.OK, reopened.delete(encodedUri))
        assertFalse(file.exists())
    }

    @Test
    fun unsupportedAuthoritiesRootsAndTraversalCannotAccessLocalFiles() {
        val dir = tempDir()
        val file = File(dir, "clip.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val backend = LocalPublishBackend(dir)
        val authority = "com.hhst.youtubelite.download.fileprovider"
        val invalidUris = listOf(
            "content://other.provider/download_ext/clip.mp4",
            "content://$authority/download_tmp/clip.mp4",
            "content://$authority/download_ext/%2e%2e%2fclip.mp4",
            "content://$authority/download_ext/clip.mp4?ignored=true",
            "content://$authority/downloads/../clip.mp4",
        )
        invalidUris.forEach { uri ->
            assertFalse(uri, backend.exists(uri))
            assertFalse(uri, backend.delete(uri).ok)
            assertTrue(file.isFile)
        }
    }

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

    @Test
    fun aVerifiedPendingTargetCanBeCompletedWhenStorageIsFull() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(64) { 7 }) }
        val target = backend.reserveTarget("clip.mp4", "video/mp4")
        backend.write(target, source)
        val publisher = DownloadPublisherImpl(backend, freeSpace = FreeSpace { 0L })
        val result = publisher.publish(PublishRequest(
            "p1", "a1", "clip.mp4", "video/mp4", source,
            existingUri = target.uri, existingPhase = PublishPhase.IN_PROGRESS,
        ))
        assertEquals(PublishResult.Published(target.uri), result)
        assertTrue(File(dir, "clip.mp4").isFile)
        assertFalse(File(dir, "clip.mp4.pending").exists())
    }

    @Test
    fun anAlreadyPublishedTargetDoesNotRequireSpaceForAnotherCopy() = runBlocking {
        val dir = tempDir()
        val backend = LocalPublishBackend(dir)
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(64) { 7 }) }
        val target = backend.reserveTarget("clip.mp4", "video/mp4")
        backend.write(target, source)
        backend.complete(target)
        val publisher = DownloadPublisherImpl(backend, freeSpace = FreeSpace { 0L })
        val result = publisher.publish(PublishRequest(
            "p1", "a1", "clip.mp4", "video/mp4", source,
            existingUri = target.uri, existingPhase = PublishPhase.PUBLISHED,
        ))
        assertEquals(PublishResult.Published(target.uri), result)
        assertEquals(1, dir.listFiles { f -> f.extension == "mp4" }!!.size)
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dl-pub-${System.nanoTime()}").also { it.mkdirs() }
}
