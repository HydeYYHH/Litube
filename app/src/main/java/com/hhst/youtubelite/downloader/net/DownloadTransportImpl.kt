package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.core.DownloadChunk
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
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        dest.parentFile?.mkdirs()
        var current = source
        var plan = DownloadRequestFactory.fromSource(current)
        var identityRounds = 0
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
                    fetchFull(taskId, component, plan, dest, current, onChunk)
                } else {
                    fetchChunked(taskId, component, plan, dest, current, kept, onChunk)
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
                        result.reason.startsWith("403") -> {
                            val recovered = forbidden?.recover(taskId, current.resourceIdentity)
                            if (recovered == null || recovered.exhausted) {
                                return TransferResult.Failed("403")
                            }
                            identityRounds++
                            if (identityRounds > 2) return TransferResult.Failed("403")
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
        kept: List<DownloadChunk>,
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        val knownTotal = source.expectedBytes
            ?: DownloadResourceIdentity.contentLength(source.resourceIdentity.orEmpty())
            ?: return fetchFull(taskId, component, plan, dest, source, onChunk)
        if (knownTotal <= 0L) {
            return fetchFull(taskId, component, plan, dest, source, onChunk)
        }
        val size = chunkBytes.coerceAtLeast(1L)
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
            val request = DownloadRequestFactory.build(plan, offset, end, cookies)
            when (val outcome = executeAndWrite(taskId, request, dest, offset, end, plan.rangeMode, planned)) {
                is WriteOutcome.Ok -> {
                    if (outcome.received <= 0L) {
                        offset = maxOf(offset, outcome.end + 1L)
                        if (offset >= planned) break else continue
                    }
                    val chunk = checkpoint(component.id, dest, offset, outcome.end, outcome.received)
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
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult {
        dest.delete()
        val request = DownloadRequestFactory.build(plan, start = null, endInclusive = null, cookies = cookies)
        return when (val outcome = executeAndWrite(taskId, request, dest, 0L, Long.MAX_VALUE - 1L, DownloadRangeMode.NONE, source.expectedBytes)) {
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

    private suspend fun executeAndWrite(
        taskId: String,
        request: okhttp3.Request,
        dest: File,
        requestedStart: Long,
        requestedEnd: Long,
        rangeMode: DownloadRangeMode,
        knownTotal: Long?,
    ): WriteOutcome {
        val call = client.newCall(request)
        calls[taskId] = call
        val response = try {
            awaitCall(call)
        } catch (io: IOException) {
            calls.remove(taskId, call)
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
                return if (requestedStart > 0L && dest.isFile && dest.length() >= requestedStart) {
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
            if (contentRange?.total != null && knownTotal != null && knownTotal > 0L &&
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
                    contentRange = contentRange,
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
            dest.parentFile?.mkdirs()
            try {
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(requestedStart)
                    val buf = ByteArray(16 * 1024)
                    var received = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            if (call.isCanceled()) return WriteOutcome.Cancelled
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            received += n.toLong()
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
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                if (isCancelled(taskId, io) || !coroutineContext.isActive) return WriteOutcome.Cancelled
                return WriteOutcome.Failed(
                    if (FileIntegrity.isNoSpace(io)) "ENOSPC" else (io.message ?: "io"),
                )
            }
        } finally {
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
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
        return null
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
        data class Ok(val end: Long, val received: Long) : WriteOutcome()
        data object FallbackFull : WriteOutcome()
        data class Http(val code: Int, val retryAfter: Long?) : WriteOutcome()
        data object Eof : WriteOutcome()
        data object Cancelled : WriteOutcome()
        data class Failed(val reason: String) : WriteOutcome()
    }
}
