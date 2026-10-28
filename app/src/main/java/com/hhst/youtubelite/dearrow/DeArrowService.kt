package com.hhst.youtubelite.dearrow

import com.google.gson.JsonParser
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticNetwork
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeArrow display decoration: community-submitted titles and thumbnails.
 *
 * Branding queries use `https://sponsor.ajay.app/api/branding/<prefix>` where
 * `<prefix>` is the first 4 hex characters of the video ID's SHA-256 — the
 * plain video ID is never sent to that endpoint; the response is a bucket of
 * candidates and the caller must strict-match the requested ID. Thumbnail
 * bitmaps come from `https://dearrow-thumb.ajay.app`, whose URL does carry
 * the full video ID — the settings copy must not claim the hash prefix makes
 * these requests anonymous. No Google cookies, OAuth tokens or visitor
 * headers are attached to third-party requests.
 *
 * Attribution: community data under the SponsorBlock database/API license
 * (CC BY-NC-SA 4.0); see `assets/licenses/dearrow-attribution.txt`.
 *
 * Canonical titles and thumbnails are never mutated: replacement values only
 * decorate display, and both toggles are read before and after every
 * suspension so a mid-flight switch-off cannot re-apply stale content.
 */
class DeArrowService(
    private val http: OkHttpClient,
    private val prefs: ExtensionManager,
    // Process-scoped singleton owner (Koin `single`): bounded worker pool so
    // a fast scroll cannot queue an unbounded number of bucket fetches.
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2)),
    private val brandingUrl: String = BRANDING_URL,
) {

    /** Resolved display decoration for one video; nulls mean "keep original". */
    data class Branding(val videoId: String, val title: String?, val thumbTime: Double?)

    private val titlesEnabledFlow = MutableStateFlow(prefs.isEnabled(PreferenceKeys.DEARROW_TITLES))
    private val thumbnailsEnabledFlow = MutableStateFlow(prefs.isEnabled(PreferenceKeys.DEARROW_THUMBNAILS))
    val titlesEnabled: StateFlow<Boolean> = titlesEnabledFlow.asStateFlow()
    val thumbnailsEnabled: StateFlow<Boolean> = thumbnailsEnabledFlow.asStateFlow()

    init {
        prefs.addOnChangedListener { key ->
            if (key == "*" || key == PreferenceKeys.DEARROW_TITLES || key == PreferenceKeys.DEARROW_THUMBNAILS) {
                titlesEnabledFlow.value = prefs.isEnabled(PreferenceKeys.DEARROW_TITLES)
                thumbnailsEnabledFlow.value = prefs.isEnabled(PreferenceKeys.DEARROW_THUMBNAILS)
                if (inactive()) synchronized(inFlight) { inFlight.values.toList() }.forEach { it.cancel() }
            }
        }
    }

    /** True when neither decoration is enabled; no request may leave the app. */
    fun inactive(): Boolean = !titlesEnabledFlow.value && !thumbnailsEnabledFlow.value

    private class Entry(val branding: Branding?, val at: Long, val ttlMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= MAX_ITEMS) return false
            cachedBytes -= approxBytes(eldest.value)
            return true
        }
    }
    private var cachedBytes = 0
    private var cacheGeneration = 0L

    private val inFlight = ConcurrentHashMap<String, Deferred<Branding?>>()
    private val requests = Semaphore(2)

    /**
     * Decoration for [videoId], merging concurrent callers onto one bucket
     * fetch. Returns a Branding whose fields may be null (keep original), or
     * null when disabled, still unknown, or the fetch failed. Never throws.
     */
    suspend fun branding(videoId: String): Branding? {
        if (videoId.isBlank() || inactive()) return null
        cached(videoId)?.let { return it }
        val deferred = synchronized(inFlight) {
            if (inactive()) return null
            inFlight[videoId] ?: run {
                if (inFlight.size >= MAX_IN_FLIGHT) return null
                val generation = synchronized(cache) { cacheGeneration }
                scope.async(start = CoroutineStart.LAZY) { requests.withPermit { fetch(videoId, generation) } }.also { pending ->
                    inFlight[videoId] = pending
                    pending.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(videoId, pending) } }
                }
            }
        }
        deferred.start()
        return deferred.await().takeUnless { inactive() }
    }

    private fun cached(videoId: String): Branding? = synchronized(cache) {
        val entry = cache[videoId] ?: return null
        if (System.currentTimeMillis() - entry.at > entry.ttlMs) {
            cachedBytes -= approxBytes(entry)
            cache.remove(videoId)
            return null
        }
        entry.branding
    }

    private suspend fun fetch(videoId: String, generation: Long): Branding? {
        val diagnostic = DiagnosticContext(videoId = videoId)
        val prefix = sha256(videoId).substring(0, 4)
        val request = Request.Builder()
            .url("$brandingUrl/$prefix")
            .tag(DiagnosticNetwork.Policy::class.java, DiagnosticNetwork.Policy(setOf(204, 404)))
            .build()
        val operation = AppLog.operation(CATEGORY, "dearrow_branding", diagnostic)
        try {
            val (status, body) = read(DiagnosticNetwork.tag(request, diagnostic))
            val branding = when (status) {
                200 -> parse(videoId, body)
                // 204 and 404 are legitimate "no submissions for this bucket".
                204, 404 -> Branding(videoId, null, null)
                else -> throw IOException("dearrow HTTP $status")
            }
            store(branding, if (branding.hasContent()) SUCCESS_TTL_MS else MISS_TTL_MS, generation)
            operation.finish(DiagnosticOutcome.SUCCESS,
                if (branding.hasContent()) "branding_found" else "bucket_empty")
            return branding
        } catch (cancelled: CancellationException) {
            operation.finish(DiagnosticOutcome.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            // Rate limits and transport failures back off briefly; nothing was learned.
            store(Branding(videoId, null, null), FAILURE_TTL_MS, generation)
            operation.finish(DiagnosticOutcome.FAILURE, "dearrow_fetch_failed", failure)
            return null
        }
    }

    private suspend fun read(request: Request): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source()
                        if (source != null && source.request(MAX_RESPONSE_BYTES + 1) && source.buffer.size > MAX_RESPONSE_BYTES)
                            throw IOException("dearrow_response_too_large")
                        it.code to source?.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun Branding.hasContent(): Boolean = title != null || thumbTime != null

    fun clear() = synchronized(inFlight) {
        synchronized(cache) {
            cacheGeneration++
            cache.clear()
            cachedBytes = 0
        }
        inFlight.values.toList().forEach { it.cancel() }
        inFlight.clear()
    }

    internal fun store(branding: Branding, ttlMs: Long, generation: Long = synchronized(cache) { cacheGeneration }) = synchronized(cache) {
        if (generation != cacheGeneration) return@synchronized
        val previous = cache.put(branding.videoId, Entry(branding, System.currentTimeMillis(), ttlMs))
        if (previous != null) cachedBytes -= approxBytes(previous)
        cachedBytes += approxBytes(branding)
        while (cachedBytes > MAX_BYTES && cache.isNotEmpty()) {
            val eldest = cache.keys.first()
            cachedBytes -= approxBytes(cache.remove(eldest) ?: continue)
        }
    }

    private fun approxBytes(entry: Entry): Int = approxBytes(entry.branding)
    private fun approxBytes(branding: Branding?): Int =
        64 + (branding?.videoId?.length ?: 0) + (branding?.title?.length ?: 0)

    /**
     * Strict full-ID match inside the returned bucket, then the trust rule:
     * candidates are ordered by quality, and only the first entry may be
     * used — when it asks to keep the original (or is unverified), lower
     * ranked alternatives are never promoted.
     */
    internal fun parse(videoId: String, body: String?): Branding {
        if (body.isNullOrBlank()) return Branding(videoId, null, null)
        val bucket = JsonParser.parseString(body).asJsonObject
        val entry = bucket.getAsJsonObject(videoId) ?: return Branding(videoId, null, null)
        val duration = entry.get("videoDuration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

        val title = entry.getAsJsonArray("titles")?.firstOrNull()
            ?.takeIf { it.isJsonObject && trusted(it.asJsonObject) && !original(it.asJsonObject) }
            ?.asJsonObject?.get("title")?.takeIf { !it.isJsonNull }?.asString
            // '>' characters are auto-formatter casing directives; without a
            // formatter the official guidance is to strip them.
            ?.replace(">", "")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 }

        val thumbTime = entry.getAsJsonArray("thumbnails")?.firstOrNull()
            ?.takeIf { it.isJsonObject && trusted(it.asJsonObject) && !original(it.asJsonObject) }
            ?.asJsonObject?.get("timestamp")?.takeIf { !it.isJsonNull }?.asDouble
            ?.takeIf { it.isFinite() && it >= 0.0 && (duration <= 0.0 || it < duration) }

        return Branding(videoId, title, thumbTime)
    }

    /** First-entry trust rule: locked, or non-negative votes. */
    private fun trusted(candidate: com.google.gson.JsonObject): Boolean =
        candidate.get("locked")?.takeIf { !it.isJsonNull }?.asBoolean == true ||
            (candidate.get("votes")?.takeIf { !it.isJsonNull }?.asInt ?: 0) >= 0

    private fun original(candidate: com.google.gson.JsonObject): Boolean =
        candidate.get("original")?.takeIf { !it.isJsonNull }?.asBoolean == true

    internal companion object {
        private val CATEGORY = AppLog.Category.EXTENSION
        const val BRANDING_URL = "https://sponsor.ajay.app/api/branding"
        const val MAX_ITEMS = 500
        const val MAX_IN_FLIGHT = 64
        const val MAX_RESPONSE_BYTES = 1024L * 1024
        const val MAX_BYTES = 2 * 1024 * 1024
        const val SUCCESS_TTL_MS = 6 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 30 * 60 * 1000L
        const val FAILURE_TTL_MS = 5 * 60 * 1000L

        fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
