package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.ActionReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.transportError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MediaException(failure: RequestError) : RequestException(failure)

fun <T> ReadResult<T>.requireValue(): T = value?.takeIf { ok }
    ?: throw MediaException(error ?: RequestError(RequestFailure.PARSE, "missing_response"))

internal fun ActionReply.requireConfirmation() {
    if (!confirmed) throw com.hhst.youtubelite.watch.domain.UnconfirmedActionException(httpStatus,
        metadata + mapOf("response_shape" to responseShape.orEmpty()))
}

/** Runs synchronous core operations off the main thread and cancels their HTTP calls together. */
class MediaClient private constructor(
    http: OkHttpClient,
    private val context: () -> RequestContext,
    private val singleAttemptWrites: Boolean,
) {
    constructor(http: OkHttpClient, context: () -> RequestContext = { RequestContext() }) : this(http, context, false)

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val writer by lazy { forWrites() }
    internal val desiredStateWrites by lazy { DesiredStateWriteRecovery(this) }

    /** Each HTTP attempt is one-shot; an ambiguous sent mutation is never replayed here. */
    fun forWrites(): MediaClient = if (singleAttemptWrites) this else
        MediaClient(http.newBuilder().retryOnConnectionFailure(false).build(), context, true)

    /** Preparation, mutation and optional readback share one session and cancellation lifetime. */
    suspend fun <T> write(block: (RequestContext, HttpClient) -> T): T = writer.read(block)

    /** A business retry keeps its first identity while each HTTP attempt remains one-shot. */
    internal suspend fun <T> writeInSession(session: RequestContext, block: (RequestContext, HttpClient) -> T): T =
        pendingReads.withPermit { writer.execute(session, block) }

    suspend fun <T> read(block: (RequestContext, HttpClient) -> T): T = pendingReads.withPermit {
        execute(null, block)
    }

    /** Optional card repair must leave workers available for navigation and writes. */
    suspend fun <T> readBackground(block: (RequestContext, HttpClient) -> T): T = backgroundReads.withPermit {
        read(block)
    }

    suspend fun <T> readBackgroundIdempotent(block: (RequestContext, HttpClient) -> T): T = backgroundReads.withPermit {
        readIdempotent(block)
    }

    /**
     * One bounded retry for idempotent GET-shaped reads whose first attempt failed
     * with a network-shaped transport error (DNS, connect, TLS, timeout, reset).
     * Cancellation, auth, HTTP status and parse failures are never retried, and a
     * session change between attempts still aborts through the generation check.
     */
    suspend fun <T> readIdempotent(block: (RequestContext, HttpClient) -> T): T {
        var session: RequestContext? = null
        return try {
            read { context, http ->
                // Capture on the first worker admission, then start the actual read there.
                // A separate capture-only task would send it back behind queued slow reads.
                session = context
                block(context, http)
            }
        } catch (error: MediaException) {
            // Pre-capture failures stay single-attempt; a retry is bound to the captured identity.
            val captured = session ?: throw error
            if (error.failure.kind != RequestFailure.TRANSPORT || error.failure.code !in RETRYABLE_TRANSPORT_CODES) throw error
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
            pendingReads.withPermit { execute(captured, block) }
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
                    override fun execute(request: HttpRequest) = executeRequest(request, session, cancelled, calls)
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

    private fun executeRequest(
        request: HttpRequest,
        session: RequestContext,
        cancelled: AtomicBoolean,
        calls: MutableSet<Call>,
    ): HttpReply {
        var recoveries = 0
        while (true) {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
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
            val bodyStarted = AtomicBoolean(false)
            val body = if (request.method == "POST") request.body.toRequestBody(
                (request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                    ?: "application/json").toMediaType()).let { delegate ->
                if (!singleAttemptWrites) delegate else object : okhttp3.RequestBody() {
                    override fun contentType() = delegate.contentType()
                    override fun contentLength() = delegate.contentLength()
                    // Also prevents OkHttp's 503 Retry-After: 0 follow-up.
                    override fun isOneShot() = true
                    override fun writeTo(sink: okio.BufferedSink) {
                        bodyStarted.set(true)
                        delegate.writeTo(sink)
                    }
                }
            } else null
            val call = http.newCall(builder.method(request.method, body).build())
            calls.add(call)
            if (cancelled.get()) call.cancel()
            try {
                val reply = call.execute().use { response ->
                    if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                    val source = response.body?.source()
                    if (source != null && source.request(MAX_RESPONSE_BYTES + 1) && source.buffer.size > MAX_RESPONSE_BYTES)
                        throw MediaException(RequestError(RequestFailure.PARSE, "response_too_large"))
                    val headers = response.headers.toMap().toMutableMap()
                    response.headers.values("Set-Cookie").takeIf { it.isNotEmpty() }?.let {
                        headers.keys.removeAll { name -> name.equals("Set-Cookie", true) }
                        headers["Set-Cookie"] = it.joinToString("\n")
                    }
                    HttpReply(response.code, source?.readUtf8().orEmpty(), headers)
                }
                // A body source may finish after its caller has timed out or switched
                // identity. Do not let the parser publish that retired response.
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                if (session.generation != context().generation)
                    throw MediaException(RequestError(RequestFailure.AUTH, "session_changed"))
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                return reply
            } catch (error: IOException) {
                // This exception class and an untouched body jointly prove no mutation
                // was delivered. Unknown IO, TLS policy and all sent bodies stay one-shot.
                val unsentConnectionFailure = error is UnknownHostException || error is ConnectException ||
                    error is NoRouteToHostException || error is PortUnreachableException
                if (!singleAttemptWrites || body == null || bodyStarted.get() || recoveries > 0 || !unsentConnectionFailure)
                    throw error
                recoveries++
            } finally {
                calls.remove(call)
            }
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
        // Shared across clients: artwork and metadata repair can otherwise fill all four
        // workers together even though each caller has its own concurrency limit.
        val backgroundReads = Semaphore(2)
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
