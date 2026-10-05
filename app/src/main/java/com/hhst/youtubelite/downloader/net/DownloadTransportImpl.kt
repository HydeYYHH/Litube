package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.core.DownloadChunk
import kotlinx.coroutines.InternalCoroutinesApi
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.DownloadTransport
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.TransferResult
import com.hhst.youtubelite.downloader.io.DownloadNetworkPolicy
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.NetworkKind
import com.hhst.youtubelite.downloader.io.NetworkMonitor
import com.hhst.youtubelite.downloader.io.AssumeAvailableNetwork
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import okhttp3.Request
import kotlinx.coroutines.Job
import androidx.media3.datasource.HttpUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

fun interface DownloadSleeper {
    suspend fun sleep(millis: Long)
}

fun interface ForbiddenRecovery {
    suspend fun recover(taskId: String, identity: String?): RecoveredSource?
}

data class RecoveredSource(
    val source: DownloadComponentSource,
    val needsRedownload: Boolean,
    val exhausted: Boolean,
)

/**
 * Independent OkHttp download client: own Call, dispatcher budget, working files.
 */
class DownloadTransportImpl(
    private val client: OkHttpClient,
    private val cookies: DownloadCookieSource = NoCookies,
    private val network: NetworkMonitor = AssumeAvailableNetwork,
    private val wifiOnly: Boolean = DownloadSettings.WIFI_ONLY_DEFAULT,
    /** Live policy lookup so a settings change applies without re-creating the transport. */
    private val wifiOnlyProvider: () -> Boolean = { wifiOnly },
    private val chunkBytes: Long = DownloadSettings.CHUNK_BYTES,
    private val sleeper: DownloadSleeper = DownloadSleeper { kotlinx.coroutines.delay(it) },
    private val random: Random = Random.Default,
    private val forbidden: ForbiddenRecovery? = null,
    private val mediaRequests: YoutubeMediaRequests? = null,
    private val fallback: ForbiddenRecovery? = null,
) : DownloadTransport {

    private val calls = ConcurrentHashMap<String, Call>()

    override fun cancelInFlight(taskId: String) {
        calls.remove(taskId)?.cancel()
    }

    override suspend fun downloadComponent(
        taskId: String,
        component: InputComponent,
        source: DownloadComponentSource,
        dest: File,
        verified: List<DownloadChunk>,
        onProgress: suspend (DownloadChunk, Long?) -> Boolean,
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        dest.parentFile?.mkdirs()
        var current = source
        var plan = DownloadRequestFactory.fromSource(current)
        var identityRounds = 0
        var backupAttempted = false
        if (component.needsRedownload) {
            dest.delete()
            FileIntegrity.sidecar(dest, 0).parentFile?.listFiles { f ->
                f.name.startsWith("${dest.name}.") && f.name.endsWith(".sha256")
            }?.forEach { it.delete() }
        }
        val kept: MutableList<DownloadChunk> = if (component.needsRedownload) {
            mutableListOf()
        } else {
            verified.filter {
                it.verified && FileIntegrity.verify(dest, it.startByte, it.receivedBytes, it.checksum)
            }.toMutableList()
        }
        var retries = 0
        var fullFallback = false
        while (coroutineContext.isActive) {
            if (DownloadNetworkPolicy.waitingNetwork(wifiOnlyProvider(), network.current())) {
                return TransferResult.WaitingNetwork
            }
            val result = try {
                if (fullFallback) {
                    fetchFull(taskId, component, plan, dest, current, onProgress, onChunk)
                } else {
                    fetchChunked(taskId, component, plan, dest, current, kept, onProgress, onChunk)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                if (isCancelled(taskId, io)) {
                    return if (network.current() == NetworkKind.NONE) {
                        TransferResult.WaitingNetwork
                    } else {
                        TransferResult.Paused
                    }
                }
                if (FileIntegrity.isNoSpace(io)) return TransferResult.Failed("ENOSPC")
                if (isNetworkLoss(io)) return TransferResult.WaitingNetwork
                TransferResult.Failed(io.message ?: "io")
            }
            when (result) {
                is TransferResult.Completed, is TransferResult.Paused,
                is TransferResult.Cancelled, is TransferResult.WaitingNetwork,
                -> return result
                is TransferResult.Failed -> {
                    when {
                        result.reason.startsWith("403") || result.reason in setOf("MEDIA_SESSION_CHANGED", "MEDIA_OBJECT_CHANGED", "MEDIA_URL_EXPIRED") -> {
                            val recovered = if (identityRounds == 0) {
                                identityRounds++
                                forbidden?.recover(taskId, current.resourceIdentity)
                            } else {
                                if (backupAttempted || fallback == null) return result
                                backupAttempted = true
                                fallback.recover(taskId, current.resourceIdentity)
                            }
                            if (recovered == null || recovered.exhausted) return result
                            val identityChanged =
                                recovered.source.resourceIdentity != current.resourceIdentity
                            current = recovered.source
                            plan = DownloadRequestFactory.fromSource(current)
                            if (recovered.needsRedownload || identityChanged) {
                                // The refreshed object is not proven to be the same
                                // media: old chunks must never be spliced into it.
                                // Refetch the new object in full, without reusing
                                // any verified range from the old one.
                                dest.delete()
                                kept.clear()
                                fullFallback = true
                            }
                        }
                        result.reason.startsWith("429:") -> {
                            val wait = result.reason.removePrefix("429:").toLongOrNull()
                                ?: backoff(retries)
                            retries++
                            if (retries > DownloadSettings.MAX_RETRIES) return result
                            sleeper.sleep(wait)
                        }
                        result.reason == "fallback-full" -> {
                            if (plan.requestPlan != null && fullFallback) return TransferResult.Failed("MEDIA_RANGE_IGNORED")
                            fullFallback = true
                        }
                        result.reason == "total-mismatch" || result.reason == "length-mismatch" -> return result
                        else -> {
                            retries++
                            if (retries > DownloadSettings.MAX_RETRIES) return result
                            sleeper.sleep(backoff(retries - 1))
                        }
                    }
                }
            }
        }
        return TransferResult.Paused
    }

    private suspend fun fetchChunked(
        taskId: String,
        component: InputComponent,
        plan: YoutubeDownloadRequestPlan,
        dest: File,
        source: DownloadComponentSource,
        kept: MutableList<DownloadChunk>,
        onProgress: suspend (DownloadChunk, Long?) -> Boolean,
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        val knownTotal = plan.requestPlan?.resourceLength?.takeIf { it > 0 } ?: source.expectedBytes
            ?: DownloadResourceIdentity.contentLength(source.resourceIdentity.orEmpty())
        if (knownTotal == null || knownTotal <= 0L) {
            if (plan.requestPlan != null && plan.rangeMode != DownloadRangeMode.NONE) {
                return fetchUnknownLength(taskId, component, plan, dest, kept, onProgress, onChunk)
            }
            return fetchFull(taskId, component, plan, dest, source, onProgress, onChunk)
        }
        val size = chunkBytes.coerceIn(1L, 10L * 1024 * 1024)
        var offset = 0L
        var planned: Long = knownTotal
        while (offset < planned) {
            coroutineContext.ensureActive()
            if (DownloadNetworkPolicy.waitingNetwork(wifiOnlyProvider(), network.current())) {
                return TransferResult.WaitingNetwork
            }
            val end = minOf(offset + size, planned) - 1L
            val existing = kept.firstOrNull { it.startByte == offset && it.verified }
            if (existing != null) {
                offset = existing.endByte + 1L
                continue
            }
            val request = DownloadRequestFactory.build(plan, offset, end, cookies, mediaRequests)
            when (val outcome = executeAndWrite(taskId, request, dest, offset, end, plan.rangeMode, planned,
                component.id, onProgress)) {
                is WriteOutcome.Ok -> {
                    if (outcome.received <= 0L) {
                        offset = maxOf(offset, outcome.end + 1L)
                        if (offset >= planned) break else continue
                    }
                    val chunk = checkpoint(component.id, dest, offset, outcome.end, outcome.received)
                    kept.removeAll { it.startByte == chunk.startByte }
                    kept.add(chunk)
                    if (!onChunk(chunk)) return TransferResult.Paused
                    offset = outcome.end + 1L
                    if (offset >= planned) break
                }
                is WriteOutcome.FallbackFull -> return TransferResult.Failed("fallback-full")
                is WriteOutcome.Http -> return httpResult(outcome.code, outcome.retryAfter)
                is WriteOutcome.Eof -> return TransferResult.Failed("early-eof")
                is WriteOutcome.Cancelled -> return TransferResult.Paused
                is WriteOutcome.Failed -> return TransferResult.Failed(outcome.reason)
            }
        }
        return TransferResult.Completed
    }

    private suspend fun fetchFull(
        taskId: String,
        component: InputComponent,
        plan: YoutubeDownloadRequestPlan,
        dest: File,
        source: DownloadComponentSource,
        onProgress: suspend (DownloadChunk, Long?) -> Boolean,
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        dest.delete()
        val mediaLength = plan.requestPlan?.resourceLength?.takeIf { it > 0 }
        if (mediaLength != null) {
            return fetchChunked(taskId, component, plan, dest, source.copy(expectedBytes = mediaLength), mutableListOf(), onProgress, onChunk)
        }
        if (plan.requestPlan != null && plan.rangeMode != DownloadRangeMode.NONE) {
            return fetchUnknownLength(taskId, component, plan, dest, mutableListOf(), onProgress, onChunk)
        }
        val request = DownloadRequestFactory.build(plan, start = null, endInclusive = null, cookies = cookies, mediaRequests = mediaRequests)
        return when (val outcome = executeAndWrite(taskId, request, dest, 0L, Long.MAX_VALUE - 1L, DownloadRangeMode.NONE, source.expectedBytes,
            component.id, onProgress)) {
            is WriteOutcome.Ok -> {
                val chunk = checkpoint(component.id, dest, 0L, outcome.end, outcome.received)
                if (!onChunk(chunk)) TransferResult.Paused else TransferResult.Completed
            }
            is WriteOutcome.FallbackFull -> {
                val chunk = checkpoint(component.id, dest, 0L, dest.length() - 1L, dest.length())
                if (!onChunk(chunk)) TransferResult.Paused else TransferResult.Completed
            }
            is WriteOutcome.Http -> httpResult(outcome.code, outcome.retryAfter)
            is WriteOutcome.Eof -> TransferResult.Failed("early-eof")
            is WriteOutcome.Cancelled -> TransferResult.Paused
            is WriteOutcome.Failed -> TransferResult.Failed(outcome.reason)
        }
    }

    private suspend fun fetchUnknownLength(
        taskId: String, component: InputComponent, plan: YoutubeDownloadRequestPlan,
        dest: File, kept: MutableList<DownloadChunk>, onProgress: suspend (DownloadChunk, Long?) -> Boolean,
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        val size = minOf(chunkBytes, requireNotNull(plan.requestPlan).chunkLimit).coerceAtLeast(1)
        var offset = 0L
        // Only a contiguous verified prefix can be resumed without knowing EOF.
        for (chunk in kept.sortedBy { it.startByte }) {
            if (chunk.startByte != offset || chunk.receivedBytes <= 0 ||
                chunk.endByte != chunk.startByte + chunk.receivedBytes - 1) break
            offset = chunk.endByte + 1
        }
        kept.removeAll { it.startByte >= offset }
        if (dest.isFile) RandomAccessFile(dest, "rw").use { it.setLength(offset) }
        while (true) {
            coroutineContext.ensureActive()
            if (DownloadNetworkPolicy.waitingNetwork(wifiOnlyProvider(), network.current())) return TransferResult.WaitingNetwork
            val request = DownloadRequestFactory.build(plan, offset, offset + size - 1, cookies, mediaRequests)
            when (val outcome = executeAndWrite(taskId, request, dest, offset, offset + size - 1, plan.rangeMode, null,
                component.id, onProgress)) {
                is WriteOutcome.Ok -> {
                    if (outcome.received == 0L) return TransferResult.Completed
                    val chunk = checkpoint(component.id, dest, offset, outcome.end, outcome.received)
                    kept.removeAll { it.startByte == chunk.startByte }
                    kept.add(chunk)
                    if (!onChunk(chunk)) return TransferResult.Paused
                    offset += outcome.received
                    if (outcome.received < size || outcome.total?.let { offset == it } == true) return TransferResult.Completed
                }
                is WriteOutcome.Http -> return httpResult(outcome.code, outcome.retryAfter)
                is WriteOutcome.Cancelled -> return TransferResult.Paused
                is WriteOutcome.Failed -> return TransferResult.Failed(outcome.reason)
                else -> return TransferResult.Failed("MEDIA_RANGE_IGNORED")
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeAndWrite(
        taskId: String,
        request: Request,
        dest: File,
        requestedStart: Long,
        requestedEnd: Long,
        rangeMode: DownloadRangeMode,
        knownTotal: Long?,
        componentId: String,
        onProgress: suspend (DownloadChunk, Long?) -> Boolean,
    ): WriteOutcome {
        val call = client.newCall(request)
        calls[taskId] = call
        val cancellation = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        val response = try {
            awaitCall(call)
        } catch (cancelled: CancellationException) {
            cancellation?.dispose(); calls.remove(taskId, call); throw cancelled
        } catch (io: IOException) {
            cancellation?.dispose()
            calls.remove(taskId, call)
            if (io.message == "MEDIA_SESSION_CHANGED") return WriteOutcome.Failed("MEDIA_SESSION_CHANGED")
            if (call.isCanceled() || !coroutineContext.isActive) return WriteOutcome.Cancelled
            if (isNetworkLoss(io)) throw io
            return WriteOutcome.Failed(
                if (FileIntegrity.isNoSpace(io)) "ENOSPC" else (io.message ?: "io"),
            )
        }
        try {
            val code = response.code
            if (code == 403) return WriteOutcome.Http(403, null)
            if (code == 416) {
                val total = HttpUtil.getDocumentSize(response.header("Content-Range"))
                return if (requestedStart > 0L && requestedStart == total && (knownTotal == null || knownTotal == total) && dest.isFile && dest.length() == total) {
                    WriteOutcome.Ok(end = requestedStart - 1L, received = 0L)
                } else {
                    WriteOutcome.Http(416, null)
                }
            }
            if (code == 429) {
                val wait = retryAfterMillis(response) ?: backoff(0)
                return WriteOutcome.Http(429, wait)
            }
            if (code !in 200..299) return WriteOutcome.Http(code, null)
            val contentRange = DownloadRangeParser.parse(response.header("Content-Range"))
            val contentLength = response.header("Content-Length")?.toLongOrNull()
            val cropped = rangeMode == DownloadRangeMode.QUERY_PARAM && contentRange?.start == 0L &&
                contentRange.total == contentRange.end + 1 && contentRange.total <= requestedEnd - requestedStart + 1
            if (!cropped && contentRange?.total != null && knownTotal != null && knownTotal > 0L &&
                rangeMode != DownloadRangeMode.NONE && contentRange.total != knownTotal
            ) {
                // The object behind this URL is not the one the plan trusted.
                return WriteOutcome.Failed("total-mismatch")
            }
            val decision = if (rangeMode == DownloadRangeMode.NONE) {
                DownloadRangeParser.Decision.MATCH
            } else {
                DownloadRangeParser.decide(
                    code = code,
                    requestedStart = requestedStart,
                    requestedEnd = requestedEnd,
                    rangeMode = rangeMode,
                    contentRange = if (cropped) contentRange?.copy(start = requestedStart, end = requestedStart + contentRange.end) else contentRange,
                    contentLength = contentLength,
                )
            }
            if (decision == DownloadRangeParser.Decision.INVALID) {
                return WriteOutcome.Failed("range-mismatch")
            }
            if (decision == DownloadRangeParser.Decision.FULL_FALLBACK) {
                return WriteOutcome.FallbackFull
            }
            val body = response.body ?: return WriteOutcome.Failed("empty-body")
            val expectedCount = when {
                contentRange != null -> contentRange.end - contentRange.start + 1L
                contentLength != null && contentLength >= 0L -> contentLength
                requestedEnd < Long.MAX_VALUE / 2 -> requestedEnd - requestedStart + 1L
                else -> null
            }
            val total = knownTotal?.takeIf { it > 0L } ?: if (rangeMode == DownloadRangeMode.NONE) {
                contentLength?.takeIf { it > 0L }
            } else if (!cropped) contentRange?.total else null
            suspend fun reportProgress(received: Long): Boolean = onProgress(
                DownloadChunk(
                    id = "$componentId:$requestedStart",
                    componentId = componentId,
                    startByte = requestedStart,
                    endByte = (requestedStart + received - 1L).coerceAtLeast(requestedStart),
                    receivedBytes = received,
                    verified = false,
                    tempPath = dest.path,
                ),
                total,
            )
            if (!reportProgress(0L)) return WriteOutcome.Cancelled
            dest.parentFile?.mkdirs()
            try {
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(requestedStart)
                    val buf = ByteArray(16 * 1024)
                    var received = 0L
                    var lastProgressAt = System.nanoTime()
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            if (call.isCanceled()) {
                                if (request.tag(RequestPlan::class.java)?.let { mediaRequests?.isCurrent(it) == false } == true) return WriteOutcome.Failed("MEDIA_SESSION_CHANGED")
                                return WriteOutcome.Cancelled
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            if (rangeMode != DownloadRangeMode.NONE && received + n > requestedEnd - requestedStart + 1) return WriteOutcome.Failed("range-mismatch")
                            raf.write(buf, 0, n)
                            received += n.toLong()
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= 250_000_000L) {
                                if (!reportProgress(received)) return WriteOutcome.Cancelled
                                lastProgressAt = now
                            }
                        }
                    }
                    raf.fd.sync()
                    if (expectedCount != null && received < expectedCount) {
                        return WriteOutcome.Eof
                    }
                    if (rangeMode == DownloadRangeMode.NONE && knownTotal != null &&
                        knownTotal > 0L && received != knownTotal
                    ) {
                        // A non-ranged response must carry the whole trusted
                        // object; short or overlong bodies must not read as
                        // Completed. Deterministic → terminal.
                        return WriteOutcome.Failed("length-mismatch")
                    }
                    if (knownTotal != null && requestedStart == 0L && expectedCount == null && received == 0L) {
                        return WriteOutcome.Eof
                    }
                    val end = requestedStart + received - 1L
                    return WriteOutcome.Ok(
                        end = end.coerceAtLeast(requestedStart),
                        received = received,
                        total = if (cropped) null else contentRange?.total,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                if (io.message == "MEDIA_SESSION_CHANGED") return WriteOutcome.Failed("MEDIA_SESSION_CHANGED")
                if (isCancelled(taskId, io) || !coroutineContext.isActive) return WriteOutcome.Cancelled
                return WriteOutcome.Failed(
                    if (FileIntegrity.isNoSpace(io)) "ENOSPC" else (io.message ?: "io"),
                )
            }
        } finally {
            cancellation?.dispose()
            response.close()
            calls.remove(taskId, call)
        }
    }

    private fun checkpoint(
        componentId: String,
        dest: File,
        start: Long,
        end: Long,
        received: Long,
    ): DownloadChunk {
        val checksum = FileIntegrity.sha256(dest, start, received)
        FileIntegrity.writeChecksum(dest, start, checksum)
        return DownloadChunk(
            id = "$componentId:$start",
            componentId = componentId,
            startByte = start,
            endByte = end,
            receivedBytes = received,
            verified = true,
            tempPath = dest.path,
            checksum = checksum,
        )
    }

    private fun httpResult(code: Int, retryAfter: Long?): TransferResult = when (code) {
        403 -> TransferResult.Failed("403")
        429 -> TransferResult.Failed("429:${retryAfter ?: backoff(0)}")
        else -> TransferResult.Failed("http-$code")
    }

    private fun backoff(attempt: Int): Long {
        val base = DownloadSettings.BACKOFF_MS.getOrElse(attempt.coerceAtLeast(0)) {
            DownloadSettings.BACKOFF_MS.last()
        }
        val jitter = (base * 0.2 * random.nextDouble()).toLong()
        return base + jitter
    }

    private fun retryAfterMillis(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return StreamHttpException.parseRetryAfter(raw, System.currentTimeMillis())
    }

    private fun isCancelled(taskId: String, error: IOException): Boolean {
        val call = calls[taskId]
        return call?.isCanceled() == true ||
            error.message?.contains("Canceled", ignoreCase = true) == true
    }

    private fun isNetworkLoss(error: IOException): Boolean {
        if (network.current() == NetworkKind.NONE) return true
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is UnknownHostException || cur is ConnectException) return true
            val msg = cur.message.orEmpty()
            if (msg.contains("Network is unreachable", ignoreCase = true) ||
                msg.contains("Software caused connection abort", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) {
                    response.close()
                    return
                }
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

    private sealed class WriteOutcome {
        data class Ok(val end: Long, val received: Long, val total: Long? = null) : WriteOutcome()
        data object FallbackFull : WriteOutcome()
        data class Http(val code: Int, val retryAfter: Long?) : WriteOutcome()
        data object Eof : WriteOutcome()
        data object Cancelled : WriteOutcome()
        data class Failed(val reason: String) : WriteOutcome()
    }
}
