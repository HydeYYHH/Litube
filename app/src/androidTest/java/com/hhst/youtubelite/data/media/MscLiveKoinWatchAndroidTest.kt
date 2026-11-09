package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger

/** Same test APK runs against the previous and fixed main APK; no Activity or account IO. */
class MscLiveKoinWatchAndroidTest {
    @Test fun productionFactoryWatchThenCommentsUsesOneOpening() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveKoinWatch") == "1")
        val rounds = (args.getString("liveRounds")?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val id = args.getString("videoId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: "aqz-KE-bpKQ"
        val expectShared = args.getString("liveExpectSharedFactory") == "1"
        val openings = AtomicInteger()
        val continuations = AtomicInteger()
        val base = GlobalContext.get().get<OkHttpClient>().newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/next")) {
                val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                if (body.contains("\"continuation\"")) continuations.incrementAndGet() else openings.incrementAndGet()
            }
            chain.proceed(request)
        }.build()
        val probe = MscLiveNetworkProbe(base,
            args.getString("liveProxyHost")?.takeIf(String::isNotBlank),
            args.getString("liveProxyPort")?.toIntOrNull() ?: 7890) {
            RequestContext(language = "en", region = "US", generation = -904)
        }
        val operations = JSONArray()
        val report = JSONObject().put("experiment", "production_koin_watch_comments")
            .put("readOnly", true).put("signedIn", false).put("cookies", false).put("rounds", rounds)
            .put("factory", "target_apk_appModule").put("overrides", "MediaClient_only")
            .put("timingInterpretation", "Watch cost is reported separately; each round reloads real factories, preserving the connection pool")
        var successful = 0
        fun save() {
            report.put("operations", operations).put("network", probe.report()).put("successfulRounds", successful)
            DeviceEvidence.writeJson("msc-live-koin-watch-comments.json", report.toString(2))
        }
        suspend fun <T> observe(label: String, block: suspend () -> T): T? = try {
            probe.sample(label, block)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            operations.put(JSONObject().put("label", label).put("outcome", "failed")
                .put("failureType", failure.javaClass.simpleName).apply {
                    (failure as? MediaException)?.failure?.let {
                        put("kind", it.kind.name)
                        put("code", it.code.takeIf { code -> code.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: JSONObject.NULL)
                        put("status", it.status ?: JSONObject.NULL)
                    }
                })
            null
        } finally { save() }
        try {
            repeat(rounds) { round ->
                ProductionContentGraph(probe.client).use { graph ->
                    val label = "round_${round + 1}"
                    val beforeOpening = openings.get()
                    val watch = observe("${label}_watch") { graph.sources.watch.watchFresh(id) } ?: return@use
                    operations.put(JSONObject().put("label", "${label}_watch").put("outcome", "ok")
                        .put("openingRequests", openings.get() - beforeOpening)
                        .put("related", watch.related.size))
                    val beforeCommentsOpening = openings.get()
                    val beforeContinuation = continuations.get()
                    val comments = observe("${label}_comments") {
                        graph.sources.comments().comments(CommentTarget.Video(id), CommentSort.Newest, null)
                    } ?: return@use
                    val addedOpening = openings.get() - beforeCommentsOpening
                    operations.put(JSONObject().put("label", "${label}_comments").put("outcome", "ok")
                        .put("openingRequests", addedOpening)
                        .put("continuationRequests", continuations.get() - beforeContinuation)
                        .put("threads", comments.threads.size).put("next", comments.nextCursor != null)
                        .put("sortFallback", comments.sortFallback)
                        .put("turnedOff", comments.turnedOff))
                    successful++
                    save()
                    if (expectShared) assertEquals("The target APK's factory must reuse its Watch opening", 0, addedOpening)
                }
            }
            report.put("outcome", when (successful) {
                rounds -> "completed"
                0 -> "no_usable_public_watch_comments"
                else -> "partial_coverage"
            })
            assumeTrue("Inspect msc-live-koin-watch-comments.json for typed public-network failures", successful > 0)
        } finally { save() }
    }
}
