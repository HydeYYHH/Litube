package com.hhst.youtubelite.downloader.publish

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.hhst.youtubelite.downloader.core.DeleteResult
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.io.DefaultFreeSpace
import com.hhst.youtubelite.downloader.io.DownloadFileNames
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.FreeSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

const val DOWNLOAD_FILE_PROVIDER_SUFFIX = ".download.fileprovider"
const val PUBLISH_RELATIVE_PATH = "Download/Litube"

interface PublishBackend {
    fun existingNames(): Set<String>
    fun createTarget(displayName: String, mimeType: String): PublishTarget
    fun openTarget(uri: String): PublishTarget?
    fun write(target: PublishTarget, source: File)
    fun complete(target: PublishTarget)
    fun exists(uri: String): Boolean
    fun verified(uri: String, expectedBytes: Long, checksum: String?): Boolean
    fun delete(uri: String): DeleteResult
    fun openableUri(uri: String): String
}

data class PublishTarget(
    val uri: String,
    val file: File?,
)

/**
 * Crash-safe publish: register → create target → write+verify → complete →
 * success state (caller) → delete temp. Incomplete ops are reconciled so a
 * retry does not create a duplicate file.
 */
class DownloadPublisherImpl(
    private val backend: PublishBackend,
    private val freeSpace: FreeSpace = DefaultFreeSpace,
    private val workDir: File? = null,
) : DownloadPublisher {

    /** publishId → created target; lets a same-process retry reconcile instead of duplicating. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun delete(uri: String): DeleteResult = backend.delete(uri)

    override fun exists(uri: String): Boolean = backend.exists(uri)

    override suspend fun publish(request: PublishRequest): PublishResult {
        coroutineContext.ensureActive()
        if (!request.source.isFile) return PublishResult.Failed("missing-source")
        val needed = FileIntegrity.estimateNeeded(listOf(request.source), includeMux = false, includePublishCopy = true)
        val dir = workDir ?: request.source.parentFile
        if (dir != null && freeSpace.bytes(dir) in 0 until needed) {
            return PublishResult.Failed("ENOSPC")
        }
        val recovered = reconcile(request) ?: reconcileInFlight(request)
        if (recovered != null) {
            inFlight.remove(request.publishId)
            return recovered
        }
        val ext = DownloadFileNames.extensionOf(request.displayName, fallback = "bin")
        val name = DownloadFileNames.sanitize(request.displayName, ext, backend.existingNames())
        return try {
            val target = backend.createTarget(name, request.mimeType)
            inFlight[request.publishId] = target.uri
            request.onTargetCreated?.invoke(target.uri)
            coroutineContext.ensureActive()
            backend.write(target, request.source)
            val checksum = FileIntegrity.sha256(request.source)
            if (!backend.verified(target.uri, request.source.length(), checksum)) {
                backend.delete(target.uri)
                inFlight.remove(request.publishId)
                return PublishResult.Failed("verify")
            }
            backend.complete(target)
            inFlight.remove(request.publishId)
            PublishResult.Published(backend.openableUri(target.uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // The half-written target stays registered: the retry reconciles
            // (verify → complete, or delete → recreate) instead of duplicating.
            if (FileIntegrity.isNoSpace(t)) PublishResult.Failed("ENOSPC")
            else PublishResult.Failed(t.message ?: "publish")
        }
    }

    private suspend fun reconcileInFlight(request: PublishRequest): PublishResult.Published? {
        val existing = inFlight[request.publishId] ?: return null
        return reconcile(
            request.copy(existingUri = existing, existingPhase = PublishPhase.IN_PROGRESS),
        )
    }

    private fun reconcile(request: PublishRequest): PublishResult.Published? {
        val existing = request.existingUri ?: return null
        if (request.existingPhase == PublishPhase.PUBLISHED && backend.exists(existing)) {
            return PublishResult.Published(backend.openableUri(existing))
        }
        if (request.existingPhase == PublishPhase.IN_PROGRESS &&
            backend.verified(existing, request.source.length(), FileIntegrity.sha256(request.source))
        ) {
            backend.openTarget(existing)?.let { backend.complete(it) }
            return PublishResult.Published(backend.openableUri(existing))
        }
        if (request.existingPhase == PublishPhase.IN_PROGRESS) {
            backend.delete(existing)
        }
        return null
    }
}

/**
 * File-backed publisher used on API 26–28 and in JVM tests. Writes into a
 * dedicated directory, fsyncs, then rename-publishes.
 */
class LocalPublishBackend(
    private val outputDir: File,
    private val uriPrefix: String = "content://com.hhst.youtubelite.download.fileprovider/downloads/",
    private val filesByUri: MutableMap<String, File> = mutableMapOf(),
    private val openable: ((File) -> String)? = null,
) : PublishBackend {

    init {
        outputDir.mkdirs()
    }

    fun fileFor(uri: String): File? = filesByUri[uri] ?: uriToFile(uri)

    override fun existingNames(): Set<String> =
        outputDir.listFiles()?.map { it.name }?.toSet().orEmpty()

    override fun createTarget(displayName: String, mimeType: String): PublishTarget {
        val dest = File(outputDir, displayName)
        val pending = File(outputDir, "$displayName.pending")
        if (pending.exists()) pending.delete()
        pending.createNewFile()
        val uri = uriPrefix + displayName
        filesByUri[uri] = pending
        return PublishTarget(uri, pending)
    }

    override fun openTarget(uri: String): PublishTarget? {
        val file = fileFor(uri) ?: return null
        val pending = File(file.parentFile, file.name + ".pending")
        return PublishTarget(uri, if (pending.exists()) pending else file)
    }

    override fun write(target: PublishTarget, source: File) {
        val dest = target.file ?: error("no local target")
        dest.parentFile?.mkdirs()
        FileInputStream(source).channel.use { input ->
            FileOutputStream(dest).use { out ->
                out.channel.transferFrom(input, 0, input.size())
                out.fd.sync()
            }
        }
    }

    override fun complete(target: PublishTarget) {
        val pending = target.file ?: return
        val finalFile = if (pending.name.endsWith(".pending")) {
            File(pending.parentFile, pending.name.removeSuffix(".pending"))
        } else {
            pending
        }
        if (pending != finalFile) {
            if (finalFile.exists()) finalFile.delete()
            if (!pending.renameTo(finalFile)) {
                pending.copyTo(finalFile, overwrite = true)
                pending.delete()
            }
        }
        FileIntegrity.fsync(finalFile)
        filesByUri[target.uri] = finalFile
    }

    override fun exists(uri: String): Boolean = fileFor(uri)?.isFile == true

    override fun verified(uri: String, expectedBytes: Long, checksum: String?): Boolean {
        val file = fileFor(uri) ?: return false
        if (!file.isFile || file.length() != expectedBytes) return false
        if (checksum.isNullOrBlank()) return true
        return FileIntegrity.sha256(file).equals(checksum, ignoreCase = true)
    }

    override fun delete(uri: String): DeleteResult {
        val file = fileFor(uri)
        val pending = file?.let { File(it.parentFile, it.name + ".pending") }
        var failed: String? = null
        listOfNotNull(file, pending).forEach { f ->
            if (f.exists() && !f.delete()) failed = "delete-failed"
        }
        if (uri.startsWith("file:")) {
            val f = File(Uri.parse(uri).path ?: uri.removePrefix("file://"))
            if (f.exists() && !f.delete()) failed = "delete-failed"
        } else if (!uri.startsWith("content:")) {
            val f = File(uri)
            if (f.exists() && !f.delete()) failed = "delete-failed"
        }
        return failed?.let { DeleteResult.fail(it) } ?: DeleteResult.OK
    }

    override fun openableUri(uri: String): String {
        val file = fileFor(uri)
        if (file != null && openable != null) return openable.invoke(file)
        return uri
    }

    private fun uriToFile(uri: String): File? {
        val name = uri.substringAfterLast('/')
        if (name.isBlank()) return null
        val direct = File(outputDir, name)
        return if (direct.exists()) direct else File(outputDir, "$name.pending").takeIf { it.exists() }
    }
}

class MediaStorePublishBackend(
    private val context: Context,
) : PublishBackend {
    private val resolver get() = context.contentResolver

    override fun existingNames(): Set<String> {
        if (Build.VERSION.SDK_INT < 29) return emptySet()
        val names = mutableSetOf<String>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("%Litube%"),
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                cursor.getString(idx)?.let { names += it }
            }
        }
        return names
    }

    override fun createTarget(displayName: String, mimeType: String): PublishTarget {
        if (Build.VERSION.SDK_INT < 29) error("MediaStore Downloads requires API 29+")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Litube")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        return PublishTarget(uri.toString(), null)
    }

    override fun openTarget(uri: String): PublishTarget = PublishTarget(uri, null)

    override fun write(target: PublishTarget, source: File) {
        val uri = Uri.parse(target.uri)
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        } ?: error("openOutputStream failed")
    }

    override fun complete(target: PublishTarget) {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(Uri.parse(target.uri), values, null, null)
    }

    override fun exists(uri: String): Boolean {
        resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
            return it.moveToFirst()
        }
        return false
    }

    override fun verified(uri: String, expectedBytes: Long, checksum: String?): Boolean {
        if (!exists(uri)) return false
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
            val tmp = File.createTempFile("pub-verify", ".bin")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                if (tmp.length() != expectedBytes) return false
                if (checksum.isNullOrBlank()) return true
                return FileIntegrity.sha256(tmp).equals(checksum, ignoreCase = true)
            } finally {
                tmp.delete()
            }
        }
        return false
    }

    override fun delete(uri: String): DeleteResult {
        return try {
            val rows = resolver.delete(Uri.parse(uri), null, null)
            if (rows >= 0) DeleteResult.OK else DeleteResult.fail("delete-failed")
        } catch (t: Throwable) {
            DeleteResult.fail(t.message ?: "delete-failed")
        }
    }

    override fun openableUri(uri: String): String = uri
}

class AndroidFileProviderUris(
    private val context: Context,
) {
    fun forFile(file: File): Uri = FileProvider.getUriForFile(
        context,
        context.packageName + DOWNLOAD_FILE_PROVIDER_SUFFIX,
        file,
    )
}

fun createPublishBackend(context: Context, sdk: Int = Build.VERSION.SDK_INT): PublishBackend {
    return if (sdk >= 29) {
        MediaStorePublishBackend(context)
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Litube")
        LocalPublishBackend(
            outputDir = dir,
            uriPrefix = "content://${context.packageName}$DOWNLOAD_FILE_PROVIDER_SUFFIX/downloads/",
            openable = { file -> AndroidFileProviderUris(context).forFile(file).toString() },
        )
    }
}
