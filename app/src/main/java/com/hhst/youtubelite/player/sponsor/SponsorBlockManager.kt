package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.diagnostics.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Call
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Request

/**
 * SponsorBlock client: fetches skip segments and reports them to the engine.
 *
 * API: `https://sponsor.ajay.app/api/skipSegments/<sha256(videoId)[0..4)>`
 * with categories driven by extension prefs.
 */
class SponsorBlockManager(
    private val http: OkHttpClient,
    private val prefs: ExtensionManager,
    // Process-scoped singleton owner (Koin `single`): this scope is never
    // cancelled, so an instance must live for the whole process — a
    // shorter-lived instance would leak its in-flight fetches.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    /** Called when fresh segments arrive (may be off the main thread). */
    var onSegmentsLoaded: ((List<Segment>) -> Unit)? = null

    @Volatile
    private var segments: List<Segment> = emptyList()

    /** The video [segments] belong to: a refetch failure must not wipe them,
     *  but a video switch must (the old segments would mis-skip). */
    @Volatile
    private var segmentsVideoId: String? = null

    init {
        prefs.addOnChangedListener { key ->
            if (key == "*" || CATEGORY_PREFS.any { it.first == key }) {
                applyEnabledCategories()
            }
        }
    }

    /** Current segments (empty before the fetch completes or when disabled). */
    val currentSegments: List<Segment> get() = segments

    /** Request id: a slow reply for video A must not clobber video B's. */
    private val requestSeq = AtomicLong()

    /** In-flight call; a newer [load] cancels the superseded one outright. */
    private val currentCall = AtomicReference<Call?>()

    /** Fetches segments for [videoId]; no-op when every category is disabled. */
    fun load(videoId: String, diagnosticContext: DiagnosticContext? = null) {
        val id = VideoId.parse(videoId) ?: return
        val requestId = requestSeq.incrementAndGet()
        currentCall.getAndSet(null)?.cancel()
        if (segmentsVideoId != id) {
            segmentsVideoId = id
            segments = emptyList()
            onSegmentsLoaded?.invoke(emptyList())
        }
        val categories = enabledCategories()
        if (categories.isEmpty()) {
            segments = emptyList()
            onSegmentsLoaded?.invoke(emptyList())
            return
        }
        scope.launch {
            val context = (diagnosticContext ?: DiagnosticContext(videoId = id)).child(requestId.toString())
            val operation = AppLog.operation(AppLog.Category.PLAYER, "sponsorblock", context, mapOf("categories" to categories))
            // Failed refreshes retain segments for the current video.
            val result = runCatching { fetch(id, categories, context) }
            if (requestId != requestSeq.get()) { operation.finish(DiagnosticOutcome.SUPERSEDED, "video_replaced"); return@launch }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                operation.finish(DiagnosticOutcome.FAILURE,
                    if (failure is SponsorBlockParseException) "parse_failed" else "fetch_failed", failure)
                onSegmentsLoaded?.invoke(segments)
                return@launch
            }
            val fetched = result.getOrThrow()
            segments = fetched
            segmentsVideoId = id
            onSegmentsLoaded?.invoke(segments)
            operation.finish(DiagnosticOutcome.SUCCESS,
                if (fetched.isEmpty()) "no_segments" else "segments_loaded",
                fields = mapOf("segment_count" to fetched.size))
        }
    }

    private fun enabledCategories(): List<String> =
        CATEGORY_PREFS.filter { prefs.isEnabled(it.first) }.map { it.second }

    /** Drop categories the user just disabled; refetch if a category was enabled. */
    private fun applyEnabledCategories() {
        val cats = enabledCategories()
        if (cats.isEmpty()) {
            currentCall.getAndSet(null)?.cancel()
            segments = emptyList()
            onSegmentsLoaded?.invoke(emptyList())
            return
        }
        val allowed = cats.toSet()
        val filtered = segments.filter { it.category in allowed }
        if (filtered.size != segments.size) {
            segments = filtered
            onSegmentsLoaded?.invoke(segments)
        }
        val id = segmentsVideoId ?: return
        load(id)
    }

    private fun fetch(videoId: String, categories: List<String>, diagnostic: DiagnosticContext): List<Segment> {
        val prefix = sha256(videoId).substring(0, 4)
        val categoryParam = URLEncoder.encode(
            categories.joinToString(",", "[", "]") { "\"$it\"" },
            Charsets.UTF_8.name(),
        )
        val request = Request.Builder()
            .url("https://sponsor.ajay.app/api/skipSegments/$prefix?service=YouTube&categories=$categoryParam&actionType=skip&actionType=poi")
            .tag(DiagnosticNetwork.Policy::class.java, DiagnosticNetwork.Policy(setOf(404)))
            .build()
        val call = http.newCall(DiagnosticNetwork.tag(request, diagnostic))
        currentCall.set(call)
        call.execute().use { response ->
            val body = response.body?.string()
            return interpretHttp(response.code, body, videoId)
                ?: throw IOException("sponsorblock HTTP ${response.code}")
        }
    }

    companion object {
        private val CATEGORY_PREFS = listOf(
            PreferenceKeys.SKIP_SPONSORS to "sponsor",
            PreferenceKeys.SKIP_SELF_PROMO to "selfpromo",
            PreferenceKeys.SKIP_INTERACTION to "interaction",
            PreferenceKeys.SKIP_INTRO to "intro",
            PreferenceKeys.SKIP_OUTRO to "outro",
            PreferenceKeys.SKIP_PREVIEW to "preview",
            PreferenceKeys.SKIP_MUSIC_OFFTOPIC to "music_offtopic",
            PreferenceKeys.SKIP_FILLER to "filler",
            PreferenceKeys.SKIP_POI_HIGHLIGHT to "poi_highlight",
        )

        /** Distinguishes empty results, HTTP failures and invalid responses. */
        fun interpretHttp(code: Int, body: String?, videoId: String): List<Segment>? {
            if (code == 404) return emptyList()
            if (code in 200..299) return SponsorBlockParser.parse(body.orEmpty(), videoId)
            return null
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** Parses SponsorBlock responses for one video. */
object SponsorBlockParser {

    private val gson = Gson()
    private val type =
        object : TypeToken<List<Response>>() {}.type

    fun parse(json: String, videoId: String): List<SponsorBlockManager.Segment> = try {
        val responses = gson.fromJson<List<Response>>(json, type)
            ?: throw IllegalArgumentException("Missing SponsorBlock response")
        responses
            .filter { it.videoID == videoId }
            .flatMap { it.segments }
            .mapNotNull { data ->
                val pair = data.segment
                if (pair.size < 2 || !pair[0].isFinite() || !pair[1].isFinite()
                    || pair[0] < 0 || pair[1] < pair[0]
                    || pair[1] == pair[0] && data.category != "poi_highlight") return@mapNotNull null
                SponsorBlockManager.Segment(
                    startMs = (pair[0] * 1000).toLong(),
                    endMs = (pair[1] * 1000).toLong(),
                    category = data.category,
                )
            }
    } catch (failure: Exception) {
        throw SponsorBlockParseException(failure)
    }

    private data class Response(
        val videoID: String = "",
        val segments: List<SegmentData> = emptyList(),
    )

    private data class SegmentData(
        val category: String = "",
        val segment: List<Double> = emptyList(),
    )
}

class SponsorBlockParseException(cause: Exception) : IOException("Invalid SponsorBlock response", cause)
