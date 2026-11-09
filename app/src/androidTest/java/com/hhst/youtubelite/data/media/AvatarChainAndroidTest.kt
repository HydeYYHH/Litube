package com.hhst.youtubelite.data.media

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.YoutubeThumbnail
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.loadHome
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger

/** Read-only, opt-in measurements through the app's actual directory and bitmap loader. */
class AvatarChainAndroidTest {
    @Before fun prepareImages() {
        YoutubeThumbnail.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    private val channels = listOf(
        "UCsXVk37bltHxD1rDPwtNM8Q", // Kurzgesagt
        "UCBJycsmduvYEL83R_U4JriQ", // MKBHD
        "UCX6OQ3DkcsbYNE6H8uQQuVA", // MrBeast
        "UCYO_jab_esuFRV4b17AJtAw", // 3Blue1Brown
        "UCHnyfMqiRRG1u-2MsSQLbXA", // Veritasium
        "UCsooa4yRKGN_zEE8iknghZA", // TED-Ed
        "UCVHFbqXqoYvEWM1Ddxl0QDg", // Android Developers
        "UCddiUEpeqJcYeBxX1IVBKvQ", // The Verge
    )

    @Test fun measureRealHomeMenuAvatarChain() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("avatarProbe") == "1")
        val client = MediaClient(GlobalContext.get().get<OkHttpClient>()) { RequestContext() }
        val rows = JSONArray()
        val report = JSONObject().put("readOnly", true).put("profile", "anonymous").put("measurements", rows)
        val directory = MediaAvatarDirectory(client)
        try {
            val cards = client.read { context, http -> loadHome(HomeSection.MUSIC, context, http = http).requireValue() }
                .items.filter { it.kind == "video" && it.menuEndpoint != null }.take(3)
            report.put("sampledCards", cards.size)
            for (card in cards) {
                val at = SystemClock.elapsedRealtime()
                val id = directory.channel(card.id, card.menuEndpoint!!)
                val avatar = id?.let { directory.avatar(it) }
                val image = avatar?.let { YoutubeThumbnail.await(it, 128) }
                rows.put(JSONObject().put("video", card.id).put("channelResolved", id != null)
                    .put("matchesInlineChannel", id != null && id == card.channelId)
                    .put("avatarResolved", avatar != null).put("decoded", image != null)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - at))
            }
        } catch (e: MediaException) { report.put("error", e.failure.code) }
        finally {
            directory.dispose()
            DeviceEvidence.writeJson("avatar-home-menu.json", report.toString(2))
        }
    }

    @Test fun measureRealAvatarChain() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("avatarProbe") == "1")
        val rows = JSONArray()
        val report = JSONObject().put("device", Build.MODEL).put("readOnly", true)
            .put("profile", if (MediaSession.snapshot().signedIn) "signed_in" else "anonymous")
            .put("measurements", rows)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val base = GlobalContext.get().get<OkHttpClient>()
        val http = base.newBuilder().cache(null).addInterceptor { chain ->
            calls.incrementAndGet()
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                chain.proceed(chain.request()).also { statuses += it.code }
            } catch (e: Exception) {
                failures += e.javaClass.simpleName
                throw e
            } finally { active.decrementAndGet() }
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val known = linkedMapOf<String, String>()
        suspend fun batch(label: String, ids: List<String>, shared: Boolean = false) {
            calls.set(0); peak.set(0); statuses.clear(); failures.clear()
            val directory = MediaAvatarDirectory(client)
            val start = SystemClock.elapsedRealtime()
            val row = JSONObject().put("label", label).put("callers", ids.size)
            try {
                val results = withTimeout(150_000) {
                    coroutineScope {
                        ids.map { id -> async {
                            val at = SystemClock.elapsedRealtime()
                            val url = directory.avatar(id)
                            if (url != null) synchronized(known) { known[id] = url }
                            JSONObject().put("channel", id).put("resolved", url != null)
                                .put("allowed", url?.let(YoutubeThumbnail::isAllowed) ?: false)
                                .put("host", url?.let { java.net.URI(it).host } ?: JSONObject.NULL)
                                .put("elapsedMs", SystemClock.elapsedRealtime() - at)
                        } }.awaitAll()
                    }
                }
                row.put("results", JSONArray(results)).put("resolved", results.count { it.getBoolean("resolved") })
                if (shared) {
                    val before = calls.get()
                    row.put("cachedResolved", directory.avatar(ids.first()) != null)
                        .put("cacheExtraRequests", calls.get() - before)
                }
            } catch (e: Exception) { row.put("error", e.javaClass.simpleName) }
            finally {
                directory.dispose()
                row.put("wallMs", SystemClock.elapsedRealtime() - start).put("httpCalls", calls.get())
                    .put("peakHttp", peak.get()).put("statuses", JSONArray(statuses.toList()))
                    .put("failures", JSONArray(failures.toList()))
                rows.put(row)
                DeviceEvidence.writeJson("avatar-chain.json", report.toString(2))
            }
        }
        try {
            // Every batch has a fresh directory, so these exercise network reads, not its cache.
            repeat(3) { batch("sequential_$it", listOf(channels[it])) }
            for (n in listOf(2, 4, 8)) batch("distinct_$n", channels.take(n))
            batch("same_channel_24", List(24) { channels.first() }, shared = true)
            // Resolve errors swallowed by the directory are measured once without that boundary.
            if (known.isEmpty()) {
                try { client.readBackgroundIdempotent { context, transport ->
                    loadChannel(channels.first(), context, http = transport).requireValue()
                }.also { report.put("diagnosticAvatarPresent", !it.thumbnail.isNullOrBlank()) } }
                catch (e: MediaException) { report.put("diagnosticFailure", e.failure.code).put("diagnosticKind", e.failure.kind.name) }
            }
            // Uses the exact production CDN allowlist, 2-worker queue, retry and decoder.
            for (n in listOf(1, 2, 4, 8)) {
                YoutubeThumbnail.clearMemory()
                val urls = synchronized(known) { known.values.take(n) }
                val start = SystemClock.elapsedRealtime()
                val results = coroutineScope { urls.map { url -> async {
                    val at = SystemClock.elapsedRealtime()
                    val image = YoutubeThumbnail.await(url, 128)
                    JSONObject().put("decoded", image != null).put("width", image?.width ?: 0)
                        .put("height", image?.height ?: 0).put("elapsedMs", SystemClock.elapsedRealtime() - at)
                } }.awaitAll() }
                rows.put(JSONObject().put("label", "production_images_$n").put("callers", urls.size)
                    .put("wallMs", SystemClock.elapsedRealtime() - start)
                    .put("decoded", results.count { it.getBoolean("decoded") }).put("results", JSONArray(results)))
            }
        } finally {
            DeviceEvidence.writeJson("avatar-chain.json", report.toString(2))
        }
    }
}
