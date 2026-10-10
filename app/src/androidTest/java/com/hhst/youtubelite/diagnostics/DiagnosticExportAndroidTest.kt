@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.diagnostics

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.grack.nanojson.JsonObject
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import com.hhst.youtubelite.extractor.RemoteEjsRuntime
import com.hhst.youtubelite.core.MmkvJsonCache
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.streams.*
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticExportAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val gson = Gson()
    private fun extraction(context: DiagnosticContext) = ExtractionContext(
        YoutubeSession("fixture", YoutubeSession.Account.ANONYMOUS, 0, null, null, null, null, "fixture", 0,
            null, "offline", JsonObject(), { "" }),
        object : Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response = throw IOException("JS_NO_NETWORK")
        }, ChallengeSolver { _, _, _, _ -> ChallengeSolver.Solutions(emptyMap(), emptyMap()) }, null,
        false, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), { true }, ExtractionDiagnostics().scoped(context))

    private fun rows(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readLines()
        .filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }

    @Test fun corruptStorageRetainsFailureWithoutTheCachedBody() {
        val kv = requireNotNull(MMKV.mmkvWithID("diagnostic-corruption-fixture"))
        try {
            assertTrue(kv.encode("diagnostic_fixture:value", "{private-cache-content"))
            assertNull(MmkvJsonCache(kv).get("diagnostic_fixture:value", String::class.java))
            ExtractionDiagnostics().event("fixture_cache_lookup", "ENGINE", "HIT", 0, 0)
            ZipFile(AppLog.export(app)).use { zip ->
                val timeline = rows(zip, "timeline.jsonl")
                assertTrue(timeline.any { it.get("event").asString == "cache_corrupt" })
                assertEquals("unknown", timeline.last { it.get("event").asString == "fixture_cache_lookup.aggregate" }
                    .getAsJsonObject("fields").get("http_status").asString)
                assertFalse(timeline.joinToString().contains("private-cache-content"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("incidents/") })
            }
        } finally { kv.clearAll(); kv.close() }
    }

    @Test fun exportFlushesBusyEjsThroughControlChannelAndKeepsAllModules() {
        val root = DiagnosticContext(videoId = "Wh9klmWAm5s", taskId = "diagnostic-fixture", generation = 7)
        val executor = Executors.newSingleThreadExecutor()
        RemoteEjsRuntime(app, "diagnostic-fixture").use { runtime ->
            assertEquals("ready", runtime.evaluate("'ready'", 15_000, extraction(root)))
            val busy = executor.submit<String> { runtime.evaluate("const until=Date.now()+10000;while(Date.now()<until){};'done'", 15_000, extraction(root)) }
            try {
                for (category in AppLog.Category.entries) AppLog.detail(category, "fixture_context", mapOf("phase" to "fixture"), root)
                val cast = CastDiagnosticTrace()
                cast.video("Wh9klmWAm5s", 7); cast.request("LOAD", 99); cast.response("LOAD_FAILED", 99)
                cast.finish(); cast.finish()
                AppLog.event(AppLog.Category.PLAYER, "fixture_decode_failed", mapOf("phase" to "decoder", "cookie" to "private-credential"),
                    IOException("MEDIA_SESSION_CHANGED"), context = root)
                val started = SystemClock.elapsedRealtime()
                val archive = AppLog.export(app)
                assertTrue("Export exceeded its bounded waits", SystemClock.elapsedRealtime() - started < 5_000)
                assertFalse("The control flush must finish while evaluation is still busy", busy.isDone)
                ZipFile(archive).use { zip ->
                    for (name in listOf("summary.md", "timeline.jsonl", "context.json", "manifest.json")) assertNotNull(zip.getEntry(name))
                    val manifest = JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()).asJsonObject
                    assertTrue(manifest.getAsJsonArray("processes").any { it.asString.endsWith(":youtube_ejs") })
                    val flush = manifest.getAsJsonObject("statistics").getAsJsonObject("process_flush")
                    assertTrue(flush.entrySet().any { it.key.startsWith("youtube_ejs-") && it.value.asBoolean })
                    val incidents = zip.entries().asSequence().filter { it.name.startsWith("incidents/") }.flatMap { rows(zip, it.name).asSequence() }.toList()
                    val categories = incidents.filter { it.get("event").asString == "fixture_context" }.map { it.get("category").asString }.toSet()
                    assertTrue(categories.containsAll(AppLog.Category.entries.map { it.name }))
                    val sessionRows = rows(zip, "timeline.jsonl").filter {
                        it.getAsJsonObject("context")?.get("cast_session_id")?.asString == cast.sessionId
                    }
                    assertEquals(1, sessionRows.count { it.get("event").asString == "session.start" })
                    assertEquals(1, sessionRows.count { it.get("event").asString == "session.end" })
                    assertFalse(incidents.joinToString().contains("private-credential"))
                    val summary = zip.getInputStream(zip.getEntry("summary.md")).bufferedReader().readText()
                    assertTrue(summary.contains("Wh9klmWAm5s")); assertTrue(summary.contains("timeline.jsonl:"))
                }
                // Preserve the validation artifact even when Android evicts cache on a full emulator.
                archive.copyTo(java.io.File(app.filesDir, "diagnostic-validation-export.zip"), overwrite = true)
                assertEquals("done", busy.get(15, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun http200WithNoBodyProgressRetainsHeadersAndCancelsWithoutRecovery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload").setBodyDelay(40, TimeUnit.SECONDS))
            val context = DiagnosticContext(videoId = "wDIrpvH8MzE", taskId = "stall-transfer", generation = 8)
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).readTimeout(45, TimeUnit.SECONDS).build()
            val call = client.newCall(DiagnosticNetwork.tag(Request.Builder().url(server.url("/videoplayback?itag=95&sig=private-signature")).build(), context))
            val response = call.execute()
            assertEquals(200, response.code)
            val executor = Executors.newSingleThreadExecutor()
            val reading = executor.submit { runCatching { response.body!!.bytes() } }
            try {
                val deadline = SystemClock.elapsedRealtime() + 34_000
                while (SystemClock.elapsedRealtime() < deadline) { DiagnosticNetwork.sample(); Thread.sleep(200) }
                call.cancel(); reading.get(3, TimeUnit.SECONDS)
                ZipFile(AppLog.export(app)).use { zip ->
                    val timeline = rows(zip, "timeline.jsonl")
                    val stall = timeline.firstOrNull { it.get("event").asString == "transfer_progress.stalled" && it.getAsJsonObject("context")?.get("task_id")?.asString == "stall-transfer" }
                    assertNotNull("Actual transfer stall was not diagnosed", stall)
                    assertEquals("wDIrpvH8MzE", stall!!.getAsJsonObject("context").get("video_id").asString)
                    assertEquals(200, stall.getAsJsonObject("fields").get("http_status").asInt)
                    assertEquals(0, stall.getAsJsonObject("fields").get("bytes_read").asInt)
                    assertFalse(timeline.any { it.get("event").asString == "transfer_progress.recovered" && it.getAsJsonObject("context")?.get("task_id")?.asString == "stall-transfer" })
                    assertFalse(timeline.joinToString().contains("private-signature"))
                }
            } finally { call.cancel(); response.close(); executor.shutdownNow() }
        }
    }

    @Test fun decoderAndFirstFrameCallbacksKeepTheOldVideoIdentityAfterSwitch() {
        val origin = DiagnosticContext(videoId = "Wh9klmWAm5s", generation = 1)
        val item = MediaItem.Builder().setUri(Uri.parse("https://fixture.invalid/media"))
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("litube.diagnostic_context", gson.toJson(origin)) }).build()).build()
        val timeline = object : Timeline() {
            override fun getWindowCount() = 1
            override fun getPeriodCount() = 1
            override fun getWindow(index: Int, window: Window, projection: Long): Window = window.apply { mediaItem = item }
            override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period = period.set("fixture", "fixture", 0, 200_000_000, 0)
            override fun getIndexOfPeriod(uid: Any) = if (uid == "fixture") 0 else -1
            override fun getUidOfPeriod(index: Int): Any = "fixture"
        }
        val event = AnalyticsListener.EventTime(SystemClock.elapsedRealtime(), timeline, 0, null, 93_724, timeline, 0, null, 93_724, 0)
        val telemetry = PlaybackTelemetry()
        AppLog.operation(AppLog.Category.PLAYER, "playback", DiagnosticContext(videoId = "wDIrpvH8MzE", generation = 2))
        telemetry.onVideoCodecError(event, IllegalStateException("private-decoder-message"))
        telemetry.onRenderedFirstFrame(event, Any(), SystemClock.elapsedRealtime())
        ZipFile(AppLog.export(app)).use { zip ->
            val errors = rows(zip, "timeline.jsonl").filter { it.get("event").asString == "video_codec_error" }
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.all { it.getAsJsonObject("context").get("video_id").asString == "Wh9klmWAm5s" })
            assertFalse(errors.joinToString().contains("private-decoder-message"))
        }
    }
}
