package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MediaException(val failure: RequestError) : IOException(failure.code)

fun <T> ReadResult<T>.requireValue(): T = value?.takeIf { ok }
    ?: throw MediaException(error ?: RequestError(RequestFailure.PARSE, "missing_response"))

/** Runs synchronous core operations off the main thread and cancels their HTTP calls together. */
class MediaClient(
    http: OkHttpClient,
    private val context: () -> RequestContext = { RequestContext() },
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun <T> read(block: (RequestContext, HttpClient) -> T): T = suspendCancellableCoroutine { continuation ->
        val session = context()
        val cancelled = AtomicBoolean(false)
        val calls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Call, Boolean>())
        val future = workers.submit {
            try {
                val transport = object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        if (cancelled.get()) throw InterruptedException()
                        val builder = Request.Builder().url(request.url)
                        request.headers.forEach { (name, value) -> builder.header(name, value) }
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
                                    throw IOException("response_too_large")
                                HttpReply(response.code, source?.readUtf8().orEmpty(), response.headers.toMap())
                            }
                        } finally { calls.remove(call) }
                    }
                }
                val value = block(session, transport)
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
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "media-read").apply { isDaemon = true } }
    }
}

/** Instrumentation opts into fixtures before Application creation; normal launches use the service. */
object ContentSource {
    @Volatile var fixtures: Boolean = false
}
