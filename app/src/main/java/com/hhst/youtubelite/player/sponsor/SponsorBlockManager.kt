package com.hhst.youtubelite.player.sponsor

import android.util.Log
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "SponsorBlockManager"

/**
 * SponsorBlock client: fetches skip segments and reports them to the engine.
 *
 * API: `https://sponsor.ajay.app/api/skipSegments/<sha256(videoId)[0..4)>`
 * with categories driven by extension prefs (`skip_sponsors`, `skip_self_promo`,
 * `skip_poi_highlight`).
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
            if (key == "*" ||
                key == PreferenceKeys.SKIP_SPONSORS ||
                key == PreferenceKeys.SKIP_SELF_PROMO ||
                key == PreferenceKeys.SKIP_POI_HIGHLIGHT
            ) {
                applyEnabledCategories()
            }
        }
    }

    /** Current segments (empty before the fetch completes or when disabled). */
    val currentSegments: List<Segment> get() = segments

    /** Request id: a slow reply for video A must not clobber video B's. */
    private val requestSeq = java.util.concurrent.atomic.AtomicLong()

    /** In-flight call; a newer [load] cancels the superseded one outright. */
    private val currentCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()

    /** Fetches segments for [videoId]; no-op when every category is disabled. */
    fun load(videoId: String) {
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
            // null = fetch failed: for the SAME video keep the working segments
            // (a transient 5xx must not kill skipping for the rest of it); for
            // a new video segments were already cleared above.
            val fetched = runCatching { fetch(id, categories) }
                .onFailure { Log.w(TAG, "sponsorblock fetch failed", it) }
                .getOrNull()
            if (requestId != requestSeq.get()) return@launch // superseded
            if (fetched != null) {
                segments = fetched
                segmentsVideoId = id
            }
            onSegmentsLoaded?.invoke(segments)
        }
    }

    private fun enabledCategories(): List<String> = buildList {
        if (prefs.isEnabled(PreferenceKeys.SKIP_SPONSORS)) add("sponsor")
        if (prefs.isEnabled(PreferenceKeys.SKIP_SELF_PROMO)) add("selfpromo")
        if (prefs.isEnabled(PreferenceKeys.SKIP_POI_HIGHLIGHT)) add("poi_highlight")
    }

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

    private fun fetch(videoId: String, categories: List<String>): List<Segment> {
        val prefix = sha256(videoId).substring(0, 4)
        val categoryParam = URLEncoder.encode(
            categories.joinToString(",", "[", "]") { "\"$it\"" },
            Charsets.UTF_8.name(),
        )
        val request = Request.Builder()
            .url("https://sponsor.ajay.app/api/skipSegments/$prefix?service=YouTube&categories=$categoryParam")
            .build()
        val call = http.newCall(request)
        currentCall.set(call)
        call.execute().use { response ->
            val body = response.body?.string()
            return interpretHttp(response.code, body, videoId)
                ?: throw java.io.IOException("sponsorblock HTTP ${response.code}")
        }
    }

    companion object {
        /**
         * 404 is a valid empty result. 2xx is parsed. Timeout / 5xx return
         * null so [load] keeps working segments for the same video.
         */
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

/** Pure SponsorBlock response parser (no Android deps; unit-testable). */
object SponsorBlockParser {

    private val gson = com.google.gson.Gson()
    private val type =
        object : com.google.gson.reflect.TypeToken<List<SponsorBlockResponse>>() {}.type

    fun parse(json: String, videoId: String): List<SponsorBlockManager.Segment> {
        val responses: List<SponsorBlockResponse> =
            runCatching { gson.fromJson<List<SponsorBlockResponse>>(json, type) }
                .getOrNull() ?: return emptyList()
        return responses
            .filter { it.videoID == videoId }
            .flatMap { it.segments }
            .mapNotNull { data ->
                val pair = data.segment
                if (pair.size < 2) return@mapNotNull null
                SponsorBlockManager.Segment(
                    startMs = (pair[0] * 1000).toLong(),
                    endMs = (pair[1] * 1000).toLong(),
                    category = data.category,
                )
            }
    }

    private data class SponsorBlockResponse(
        val videoID: String = "",
        val segments: List<SegmentData> = emptyList(),
    )

    private data class SegmentData(
        val category: String = "",
        val segment: List<Double> = emptyList(),
    )
}
