package com.hhst.youtubelite.downloader.io

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStorePublishAndroidTest {
    @Test fun aPublishedTargetCanBeVerifiedAndRecoveredWithNoSpaceForAnotherCopy() = runBlocking {
        withPublishedFile { context, request, uri, work ->
            val reopened = DownloadPublisherImpl(createPublishBackend(context), FreeSpace { 0L }, work)
            val recovered = reopened.publish(request.copy(existingUri = uri, existingPhase = PublishPhase.IN_PROGRESS))
            assertEquals(PublishResult.Published(uri), recovered)
            val valid = context.contentResolver.openInputStream(Uri.parse(uri))!!.use {
                FileIntegrity.verify(it, request.source.length(), FileIntegrity.sha256(request.source))
            }
            assertTrue(valid)
        }
    }

    @Test fun anEqualLengthCorruptTargetIsRejectedDuringRecovery() = runBlocking {
        withPublishedFile { context, request, uri, work ->
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use {
                it.write(ByteArray(request.source.length().toInt()) { 9 })
            }
            val reopened = DownloadPublisherImpl(createPublishBackend(context), FreeSpace { 0L }, work)
            val recovered = reopened.publish(request.copy(existingUri = uri, existingPhase = PublishPhase.IN_PROGRESS))
            assertEquals(PublishResult.Failed("ENOSPC"), recovered)
            assertTrue("recovery must preserve completed MediaStore rows", reopened.exists(uri))
        }
    }

    private suspend fun withPublishedFile(
        check: suspend (Context, PublishRequest, String, File) -> Unit,
    ) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val work = File(context.cacheDir, "publish-regression-$id").also { it.mkdirs() }
        val source = File(work, "source.bin").also { it.writeBytes(ByteArray(64 * 1024) { 7 }) }
        val publisher = DownloadPublisherImpl(createPublishBackend(context), workDir = work)
        val request = PublishRequest(id, "asset-$id", "publish-regression-$id.bin", "application/octet-stream", source)
        var uri: String? = null
        try {
            val result = publisher.publish(request)
            assertTrue("MediaStore publication failed: $result", result is PublishResult.Published)
            val published = (result as PublishResult.Published).uri
            uri = published
            check(context, request, published, work)
        } finally {
            uri?.let(publisher::delete)
            work.deleteRecursively()
        }
    }
}
