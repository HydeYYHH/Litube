package com.hhst.youtubelite.downloader.io

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
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private const val DOWNLOAD_FILE_PROVIDER_SUFFIX = ".download.fileprovider"

interface PublishBackend {
    /** Reserve a distinct target, including names held by unfinished publications. */
    fun reserveTarget(displayName: String, mimeType: String): PublishTarget
    fun openTarget(uri: String): PublishTarget?
    fun write(target: PublishTarget, source: File)
    fun complete(target: PublishTarget)
    fun exists(uri: String): Boolean
    fun verified(uri: String, expectedBytes: Long, checksum: String?): Boolean
    /** Recovery must never delete a completed file that happens to occupy this name. */
    fun discardIncomplete(uri: String): DeleteResult
    fun delete(uri: String): DeleteResult
    fun openableUri(uri: String): String
}

data class PublishTarget(
    val uri: String,
    val file: File?,
)

private class PublishNameCollision : IOException("publish-name-collision")

/**
 * Crash-safe publish: register → create → write+verify → complete → success → delete temp.
 * Incomplete ops are reconciled so a retry does not create a duplicate file.
 */
class DownloadPublisherImpl(
    private val backend: PublishBackend,
    private val freeSpace: FreeSpace = DefaultFreeSpace,
    private val workDir: File? = null,
) : DownloadPublisher {

    /** publishId → created target; same-process retry reconciles instead of duplicating. */
    private val inFlight = ConcurrentHashMap<String, String>()

    override fun delete(uri: String): DeleteResult = backend.delete(uri)

    override fun exists(uri: String): Boolean = backend.exists(uri)

    override suspend fun publish(request: PublishRequest): PublishResult {
        coroutineContext.ensureActive()
        if (!request.source.isFile) return PublishResult.Failed("missing-source")
        return try {
            val recovered = reconcile(request) ?: reconcileInFlight(request)
            if (recovered != null) {
                inFlight.remove(request.publishId)
                return recovered
            }
            val needed = FileIntegrity.estimateNeeded(listOf(request.source), includeMux = false, includePublishCopy = true)
            val dir = workDir ?: request.source.parentFile
            if (dir != null && freeSpace.bytes(dir) in 0 until needed) {
                return PublishResult.Failed("ENOSPC")
            }
            val target = backend.reserveTarget(request.displayName, request.mimeType)
            inFlight[request.publishId] = target.uri
            request.onTargetCreated?.invoke(target.uri)
            coroutineContext.ensureActive()
            backend.write(target, request.source)
            val checksum = FileIntegrity.sha256(request.source)
            if (!backend.verified(target.uri, request.source.length(), checksum)) {
                discardIncomplete(target.uri)
                inFlight.remove(request.publishId)
                return PublishResult.Failed("verify")
            }
            backend.complete(target)
            inFlight.remove(request.publishId)
            PublishResult.Published(backend.openableUri(target.uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.event(AppLog.Category.DOWNLOADER, "publish_io_failed", mapOf("phase" to "publish", "source_bytes" to request.source.length()),
                t, context = coroutineContext[DiagnosticCoroutineContext]?.diagnostic)
            // Half-written target stays registered: the retry reconciles (verify→complete or delete→recreate).
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
            val target = backend.openTarget(existing) ?: return null
            try {
                backend.complete(target)
            } catch (_: PublishNameCollision) {
                // Recorded URI cannot change during completion; keep the other final file and re-reserve.
                discardIncomplete(existing)
                inFlight.remove(request.publishId, existing)
                return null
            }
            return PublishResult.Published(backend.openableUri(existing))
        }
        if (request.existingPhase == PublishPhase.IN_PROGRESS) {
            discardIncomplete(existing)
            inFlight.remove(request.publishId, existing)
        }
        return null
    }

    private fun discardIncomplete(uri: String) {
        val result = backend.discardIncomplete(uri)
        if (!result.ok) throw IOException(result.reason ?: "discard-incomplete-failed")
    }
}

/**
 * File-backed publisher for API 24–28 and JVM tests: dedicated directory, fsync, rename-publish.
 */
class LocalPublishBackend(
    private val outputDir: File,
    private val uriPrefix: String = "content://com.hhst.youtubelite.download.fileprovider/downloads/",
    private val openable: ((File) -> String)? = null,
) : PublishBackend {

    private val metadataLock = Any()
    private val directory = outputDir.canonicalFile
    private val authority = URI(uriPrefix).rawAuthority

    init {
        outputDir.mkdirs()
    }

    fun fileFor(uri: String): File? = synchronized(metadataLock) {
        val finalFile = finalFile(uri) ?: return@synchronized null
        finalFile.takeIf { it.isFile } ?: pendingFile(finalFile)?.takeIf { it.isFile }
    }

    override fun reserveTarget(displayName: String, mimeType: String): PublishTarget = synchronized(metadataLock) {
        val occupied = directory.listFiles().orEmpty()
            .flatMap { listOf(it.name, it.name.removeSuffix(".pending")) }.toMutableSet()
        val extension = DownloadFileNames.extensionOf(displayName, fallback = "bin")
        var name: String
        var finalFile: File
        var pending: File
        do {
            name = DownloadFileNames.sanitize(displayName, extension, occupied)
            finalFile = ownedFile(name) ?: error("invalid-publish-name")
            pending = pendingFile(finalFile) ?: error("invalid-publish-name")
            occupied += name
        } while (finalFile.exists() || !pending.createNewFile())
        PublishTarget(uriPrefix + name, pending)
    }

    override fun openTarget(uri: String): PublishTarget? = synchronized(metadataLock) {
        val finalFile = finalFile(uri) ?: return@synchronized null
        val file = pendingFile(finalFile)?.takeIf { it.isFile } ?: finalFile.takeIf { it.isFile }
        file?.let { PublishTarget(uri, it) }
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
        val completed = synchronized(metadataLock) {
            val finalFile = finalFile(target.uri) ?: error("invalid-publish-uri")
            val file = target.file ?: error("no local target")
            if (file != finalFile) {
                check(file == pendingFile(finalFile)) { "invalid-publish-target" }
                if (finalFile.exists()) throw PublishNameCollision()
                if (!file.renameTo(finalFile)) throw IOException("publish-rename-failed")
            }
            check(finalFile.isFile) { "missing-publish-target" }
            finalFile
        }
        FileIntegrity.fsync(completed)
    }

    override fun exists(uri: String): Boolean = fileFor(uri)?.isFile == true

    override fun verified(uri: String, expectedBytes: Long, checksum: String?): Boolean {
        val file = fileFor(uri) ?: return false
        if (!file.isFile || file.length() != expectedBytes) return false
        if (checksum.isNullOrBlank()) return true
        return FileIntegrity.sha256(file).equals(checksum, ignoreCase = true)
    }

    override fun discardIncomplete(uri: String): DeleteResult = synchronized(metadataLock) {
        val finalFile = finalFile(uri) ?: return@synchronized DeleteResult.fail("invalid-publish-uri")
        deleteFiles(listOfNotNull(pendingFile(finalFile)))
    }

    override fun delete(uri: String): DeleteResult = synchronized(metadataLock) {
        val finalFile = finalFile(uri) ?: return@synchronized DeleteResult.fail("invalid-publish-uri")
        deleteFiles(listOfNotNull(finalFile, pendingFile(finalFile)))
    }

    override fun openableUri(uri: String): String {
        val file = fileFor(uri)
        if (file != null && openable != null) return openable.invoke(file)
        return uri
    }

    private fun finalFile(uri: String): File? {
        val name = if (uri.startsWith(uriPrefix)) {
            // Old in-progress records used an unescaped filename; literal "%20" and "#" must survive.
            uri.removePrefix(uriPrefix)
        } else {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (parsed.scheme != "content" || parsed.rawAuthority != authority ||
                parsed.rawQuery != null || parsed.rawFragment != null) return null
            val path = parsed.path ?: return null // URI.path decodes exactly once.
            if (!path.startsWith("/download_ext/")) return null
            path.removePrefix("/download_ext/")
        }
        return ownedFile(name)
    }

    private fun ownedFile(name: String): File? {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) return null
        return runCatching { File(directory, name).canonicalFile }
            .getOrNull()?.takeIf { it.parentFile == directory }
    }

    private fun pendingFile(finalFile: File): File? = ownedFile(finalFile.name + ".pending")

    private fun deleteFiles(files: List<File>): DeleteResult {
        var failed = false
        files.forEach { if (it.exists() && !it.delete()) failed = true }
        return if (failed) DeleteResult.fail("delete-failed") else DeleteResult.OK
    }
}

class MediaStorePublishBackend(
    private val context: Context,
) : PublishBackend {
    private val resolver get() = context.contentResolver

    private fun existingNames(): Set<String> {
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

    override fun reserveTarget(displayName: String, mimeType: String): PublishTarget {
        if (Build.VERSION.SDK_INT < 29) error("MediaStore Downloads requires API 29+")
        val extension = DownloadFileNames.extensionOf(displayName, fallback = "bin")
        val name = DownloadFileNames.sanitize(displayName, extension, existingNames())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
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
            return FileIntegrity.verify(input, expectedBytes, checksum)
        }
        return false
    }

    override fun delete(uri: String): DeleteResult {
        return try {
            val rows = resolver.delete(Uri.parse(uri), null, null)
            if (rows >= 0) DeleteResult.OK else DeleteResult.fail("delete-failed")
        } catch (t: Throwable) {
            AppLog.event(AppLog.Category.STORAGE, "published_file_delete_failed", failure = t)
            DeleteResult.fail(t.message ?: "delete-failed")
        }
    }

    override fun discardIncomplete(uri: String): DeleteResult = try {
        val pending = resolver.query(
            Uri.parse(uri), arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null,
        )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 } == true
        if (pending) delete(uri) else DeleteResult.OK
    } catch (t: Throwable) {
        AppLog.event(AppLog.Category.STORAGE, "incomplete_publish_discard_failed", failure = t)
        DeleteResult.fail(t.message ?: "discard-incomplete-failed")
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
