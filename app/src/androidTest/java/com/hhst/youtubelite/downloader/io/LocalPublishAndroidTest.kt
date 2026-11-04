package com.hhst.youtubelite.downloader.io

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.downloader.core.DeleteResult
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPublishAndroidTest {
    @Test fun fileProviderNamesSurviveRestartRecoveryAndDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Exercise the API 24–28 backend even when instrumentation runs on a newer device.
        for (sdk in listOf(24, 28)) {
            val id = UUID.randomUUID().toString()
            val work = File(context.cacheDir, "local-publish-$id").also { it.mkdirs() }
            val source = File(work, "source.bin").also { it.writeBytes(ByteArray(64) { 7 }) }
            val backend = createPublishBackend(context, sdk)
            val publisher = DownloadPublisherImpl(backend, workDir = work)
            var rawUri: String? = null
            var publishedUri: String? = null
            val request = PublishRequest(
                id, "asset-$id", "中文 空格 %20 # $id.mp4", "video/mp4", source,
                onTargetCreated = { rawUri = it },
            )
            try {
                val result = publisher.publish(request)
                assertTrue("local publication failed: $result", result is PublishResult.Published)
                val uri = (result as PublishResult.Published).uri
                publishedUri = uri
                assertTrue(uri.contains("/download_ext/"))
                assertTrue(uri.contains("%2520"))
                assertTrue(backend.exists(uri))
                assertTrue(backend.verified(uri, source.length(), FileIntegrity.sha256(source)))

                val reopenedBackend = createPublishBackend(context, sdk)
                val reopened = DownloadPublisherImpl(reopenedBackend, FreeSpace { 0L }, work)
                assertTrue(reopened.exists(uri))
                assertTrue("legacy raw URI must preserve literal %20 and #", reopened.exists(rawUri!!))
                assertEquals(PublishResult.Published(uri), reopened.publish(request.copy(
                    existingUri = rawUri, existingPhase = PublishPhase.IN_PROGRESS,
                )))
                assertEquals(PublishResult.Published(uri), reopened.publish(request.copy(
                    existingUri = uri, existingPhase = PublishPhase.PUBLISHED,
                )))
                val correct = context.contentResolver.openInputStream(Uri.parse(uri))!!.use {
                    FileIntegrity.verify(it, source.length(), FileIntegrity.sha256(source))
                }
                assertTrue(correct)
                assertEquals(DeleteResult.OK, reopened.delete(uri))
                assertFalse(reopened.exists(uri))
            } finally {
                publishedUri?.let(publisher::delete)
                rawUri?.let(publisher::delete)
                work.deleteRecursively()
            }
        }
    }
}
