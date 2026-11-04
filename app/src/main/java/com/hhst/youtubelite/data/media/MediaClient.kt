package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MediaException(val failure: RequestError) : IOException(failure.code)

fun <T> ReadResult<T>.requireValue(): T = value?.takeIf { ok }
    ?: throw MediaException(error ?: RequestError(RequestFailure.PARSE, "missing_response"))

internal fun ActionReply.requireConfirmation() {
    if (!confirmed) throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException(httpStatus,
        mapOf("response_shape" to responseShape.orEmpty()))
}

/** Runs synchronous core operations off the main thread and cancels their HTTP calls together. */
class MediaClient(
    http: OkHttpClient,
    private val context: () -> RequestContext = { RequestContext() },
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun <T> read(block: (RequestContext, HttpClient) -> T): T = pendingReads.withPermit {
        execute(null, block)
    }

    /**
     * One bounded retry for idempotent GET-shaped reads whose first attempt failed
     * with a network-shaped transport error (DNS, connect, TLS, timeout, reset).
     * Cancellation, auth, HTTP status and parse failures are never retried, and a
     * session change between attempts still aborts through the generation check.
     */
    suspend fun <T> readIdempotent(block: (RequestContext, HttpClient) -> T): T {
        val session = read { context, _ -> context }
        return try {
            pendingReads.withPermit { execute(session, block) }
        } catch (error: MediaException) {
            if (error.failure.kind != RequestFailure.TRANSPORT || error.failure.code !in RETRYABLE_TRANSPORT_CODES) throw error
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
            pendingReads.withPermit { execute(session, block) }
        }
    }

    suspend fun <A, B> readTogether(
        first: (RequestContext, HttpClient) -> A,
        second: (RequestContext, HttpClient) -> B,
    ): Pair<A, B> {
        val session = read { context, _ -> context }
        return coroutineScope {
            val other = async { pendingReads.withPermit { execute(session, second) } }
            val value = pendingReads.withPermit { execute(session, first) }
            value to other.await()
        }
    }

    private suspend fun <T> execute(captured: RequestContext?, block: (RequestContext, HttpClient) -> T): T = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val calls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Call, Boolean>())
        val future = workers.submit {
            try {
                // Resolved on the worker: the session lambda may block on an OAuth token
                // refresh, which must never run on the calling (possibly main) thread.
                val current = context()
                val session = captured ?: current
                if (session.generation != current.generation)
                    throw MediaException(RequestError(RequestFailure.AUTH, "session_changed"))
                val transport = object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        if (cancelled.get()) throw InterruptedException()
                        val current = context()
                        if (session.generation != current.generation)
                            throw MediaException(RequestError(RequestFailure.AUTH, "session_changed"))
                        val builder = Request.Builder().url(request.url)
                        request.headers.forEach { (name, value) ->
                            val header = if (name.equals("Authorization", true) &&
                                value == "Bearer ${session.accessToken}" && current.accessToken != null)
                                "Bearer ${current.accessToken}" else value
                            builder.header(name, header)
                        }
                        val body = if (request.method == "POST") request.body.toRequestBody(
                            (request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                                ?: "application/json").toMediaType()) else null
                        val call = http.newCall(builder.method(request.method, body).build())
                        calls.add(call)
                        if (cancelled.get()) call.cancel()
                        return try {
                            call.execute().use { response ->
                                val source = response.body?.source()
                                if (source != null && source.request(MAX_RESPONSE_BYTES + 1) && source.buffer.size > MAX_RESPONSE_BYTES)
                                    // Deliberate policy error: survives block() normalization untouched.
                                    throw MediaException(RequestError(RequestFailure.PARSE, "response_too_large"))
                                val headers = response.headers.toMap().toMutableMap()
                                response.headers.values("Set-Cookie").takeIf { it.isNotEmpty() }?.let {
                                    headers.keys.removeAll { name -> name.equals("Set-Cookie", true) }
                                    headers["Set-Cookie"] = it.joinToString("\n")
                                }
                                HttpReply(response.code, source?.readUtf8().orEmpty(), headers)
                            }
                        } finally { calls.remove(call) }
                    }
                }
                val value = try {
                    block(session, transport)
                } catch (error: IOException) {
                    // A raw IO failure inside the read logic (not the transport lambda's
                    // own deliberate protocol errors) normalizes into the shared
                    // classified MediaException so callers never see bare sockets.
                    if (error is MediaException) throw error
                    throw MediaException(transportError(error))
                }
                if (session.generation != context().generation) throw MediaException(RequestError(RequestFailure.AUTH, "session_changed"))
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            cancelled.set(true)
            calls.forEach(Call::cancel)
            future.cancel(true)
            workers.remove(future as Runnable)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        const val RETRY_DELAY_MS = 250L

        // Network-shaped transport codes from the core classifier; "transport_protocol",
        // "transport_failed" and cancellation keep their single-attempt semantics.
        val RETRYABLE_TRANSPORT_CODES = setOf(
            "transport_dns", "transport_connect", "transport_tls", "transport_timeout",
            "transport_unreachable", "transport_socket", "transport_io")

        // Admission suspends callers before submission; no blocking work falls back to them.
        val pendingReads = Semaphore(20)
        val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
            { task -> Thread(task, "media-read").apply { isDaemon = true } })
    }
}

/** Instrumentation opts into fixtures before Application creation; normal launches use the service. */
object ContentSource {
    @Volatile var fixtures: Boolean = false
}
