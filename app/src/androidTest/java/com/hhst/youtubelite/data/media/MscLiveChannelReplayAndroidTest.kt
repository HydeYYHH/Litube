package com.hhst.youtubelite.data.media

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.ChannelPage
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Capture alone, then replay in separate old/new App processes using the same test APK. */
class MscLiveChannelReplayAndroidTest {
    @Test fun aFixedPublicResponseMeasuresChannelParsingWithoutNetworkVariance() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveChannelReplay") == "1")
        val capture = args.getString("liveReplayCapture") == "1"
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val bodyFile = File(cache, "msc-live-channel-v1.body")
        val manifestFile = File(cache, "msc-live-channel-v1.json")
        val context = RequestContext(language = "en", region = "US", generation = -900_001)
        val base = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()
        val probe = MscLiveNetworkProbe(base, args.getString("liveProxyHost")?.takeIf(String::isNotBlank),
            args.getString("liveProxyPort")?.toIntOrNull() ?: 7890) { context }
        val report = JSONObject().put("mode", if (capture) "capture" else "replay").put("readOnly", true)
            .put("signedIn", false).put("timingsMs", JSONArray())
        try {
            if (capture) {
                check(!manifestFile.exists()) { "replay_fixture_already_exists" }
                var captured: HttpReply? = null
                val page = probe.sample("channel_capture") { probe.client.read { ctx, http ->
                    loadChannel(CHANNEL, ctx, http = object : HttpClient {
                        override fun execute(request: HttpRequest): HttpReply {
                            check(captured == null && request.method == "POST" && request.url.substringBefore('?') == BROWSE)
                            return http.execute(request).also { captured = it }
                        }
                    }).requireValue()
                } }
                val reply = checkNotNull(captured)
                check(reply.status == 200 && page.id == CHANNEL && page.page.tabs.isNotEmpty()) { "replay_fixture_shape" }
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                val manifest = JSONObject().put("schema", 1).put("status", reply.status)
                    .put("bodySha256", digest(bytes)).put("decodedBytes", bytes.size).put("characters", reply.body.length)
                    .put("structureSha256", structure(page)).put("tabs", page.page.tabs.size)
                    .put("items", page.page.items.size).put("groups", page.page.groups.size)
                    .put("groupItems", page.page.groups.sumOf { it.items.size }).put("hasContinuation", page.page.nextKey != null)
                bodyFile.writeBytes(bytes)
                manifestFile.writeText(manifest.toString())
                report.put("fixture", manifest).put("outcome", "captured")
            } else {
                check(bodyFile.isFile && manifestFile.isFile) { "replay_fixture_missing" }
                val manifest = JSONObject(manifestFile.readText())
                val bytes = bodyFile.readBytes()
                report.put("fixture", manifest)
                assertEquals(manifest.getString("bodySha256"), digest(bytes))
                assertEquals(manifest.getInt("decodedBytes"), bytes.size)
                val reply = HttpReply(manifest.getInt("status"), String(bytes, Charsets.UTF_8))
                var calls = 0
                val http = object : HttpClient {
                    override fun execute(request: HttpRequest): HttpReply {
                        check(request.method == "POST" && request.url.substringBefore('?') == BROWSE)
                        calls++
                        return reply
                    }
                }
                fun parse() = loadChannel(CHANNEL, context, http = http).requireValue()
                val expected = parse() // One warmup; fixture I/O and hashing stay outside measurements.
                assertEquals(manifest.getString("structureSha256"), structure(expected))
                repeat(5) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val page = parse()
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    report.getJSONArray("timingsMs").put(elapsedMs)
                    assertEquals(expected, page)
                    assertEquals(manifest.getString("structureSha256"), structure(page))
                }
                assertEquals(6, calls)
                report.put("replayCalls", calls).put("warmups", 1).put("structureEqual", true).put("outcome", "completed")
            }
        } catch (failure: Throwable) {
            report.put("outcome", "failed").put("failureType", failure.javaClass.simpleName)
            throw AssertionError("live_channel_replay_failed")
        } finally {
            report.put("network", probe.report())
            DeviceEvidence.writeJson("msc-live-channel-replay.json", report.toString(2))
            probe.http.connectionPool.evictAll()
            probe.http.dispatcher.executorService.shutdown()
        }
    }

    private fun structure(page: ChannelPage): String {
        // MenuEndpoint deliberately redacts its toString; include its fields only inside the digest.
        val menus = JSONArray((page.page.items + page.page.groups.flatMap { it.items }).map { item ->
            item.menuEndpoint?.let { JSONArray(listOf(it.panelId, it.params)) } ?: JSONObject.NULL
        })
        return digest((page.toString() + "\n" + menus).toByteArray(Charsets.UTF_8))
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val CHANNEL = "UCSMOQeBJ2RAnuFungnQOxLg"
        const val BROWSE = "https://www.youtube.com/youtubei/v1/browse"
    }
}
