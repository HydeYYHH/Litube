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
import com.hhst.youtubelite.extractor.ExtractionContext
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.ExtractionTransport
import com.hhst.youtubelite.extractor.WebAccount
import com.hhst.youtubelite.extractor.YoutubeWebSession
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import com.hhst.youtubelite.extractor.RemoteEjsRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticExportAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private fun extraction(context: DiagnosticContext) = ExtractionContext(
        YoutubeWebSession("fixture", WebAccount.ANONYMOUS, 0, null, null, null, null, "fixture", 0,
            null, "offline", JsonObject(emptyMap()), { "" }),
        object : ExtractionTransport {
            override fun get(context: com.hhst.youtubelite.extractor.ExtractionContext, url: String, headers: Map<String, List<String>>) = throw IOException("JS_NO_NETWORK")
            override fun post(context: com.hhst.youtubelite.extractor.ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) = throw IOException("JS_NO_NETWORK")
        }, false, false, true, "",
        System.nanoTime() + TimeUnit.SECONDS.toNanos(20), { true }, ExtractionDiagnostics().scoped(context))

    private fun rows(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readLines()
        .filter { it.isNotBlank() }.map { DiagnosticJson.json.parseToJsonElement(it) as JsonObject }

    @Test fun unresponsiveSnapshotProviderYieldsAPartialArchive() {
        val release = java.util.concurrent.CountDownLatch(1)
        AppLog.registerSnapshot("blocked-fixture") {
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { }
            }
            mapOf("state" to "ready")
        }
        try {
            val start = SystemClock.elapsedRealtime()
            ZipFile(AppLog.export(app)).use { zip ->
                val context = DiagnosticJson.json.parseToJsonElement(zip.getInputStream(zip.getEntry("context.json")).bufferedReader().readText()) as JsonObject
                assertEquals("unavailable", ((context["providers"] as JsonObject)["blocked-fixture"] as JsonObject)["state"]!!.jsonPrimitive.content)
                val manifest = DiagnosticJson.json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()) as JsonObject
                assertEquals("false", (((manifest["statistics"] as JsonObject)["process_flush"] as JsonObject)["provider:blocked-fixture"]!!).jsonPrimitive.content)
            }
            assertTrue(SystemClock.elapsedRealtime() - start < 6500)
        } finally {
            release.countDown()
            AppLog.registerSnapshot("blocked-fixture") { mapOf("state" to "released") }
        }
    }

    @Test fun corruptStorageRetainsFailureWithoutTheCachedBody() {
        val store = com.hhst.youtubelite.core.PreferenceStore.of(app, "diagnostic-corruption-fixture")
        try {
            store.putString("diagnostic_fixture:value", "{private-cache-content")
            assertNull(com.hhst.youtubelite.core.PreferenceJsonCache(store).get("diagnostic_fixture:value", String::class.java))
            ExtractionDiagnostics().event("fixture_cache_lookup", "ENGINE", "HIT", 0, 0)
            ZipFile(AppLog.export(app)).use { zip ->
                val timeline = rows(zip, "timeline.jsonl")
                assertTrue(timeline.any { it["event"]!!.jsonPrimitive.content == "cache_corrupt" })
                assertEquals("unknown", timeline.last { it["event"]!!.jsonPrimitive.content == "fixture_cache_lookup.aggregate" }
                    ["fields"]!!.jsonObject["http_status"]!!.jsonPrimitive.content)
                assertFalse(timeline.joinToString().contains("private-cache-content"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("incidents/") })
            }
        } finally { store.clearAll() }
    }

    @Test fun exportFlushesBusyEjsThroughControlChannelAndKeepsAllModules() {
        // The remote EJS runtime only exists where the WebView data-directory
        // isolation is supported; the scenario cannot start below that.
        org.junit.Assume.assumeTrue(
            com.hhst.youtubelite.extractor.EjsRuntimeProcess.canIsolate(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()))
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
                    val manifest = DiagnosticJson.json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()) as JsonObject
                    assertTrue((manifest["processes"] as JsonArray).any { it.jsonPrimitive.content.endsWith(":youtube_ejs") })
                    val flush = (manifest["statistics"] as JsonObject)["process_flush"] as JsonObject
                    assertTrue(flush.entries.any { it.key.startsWith("youtube_ejs-") && it.value.jsonPrimitive.content.toBoolean() })
                    val incidents = zip.entries().asSequence().filter { it.name.startsWith("incidents/") }.flatMap { rows(zip, it.name).asSequence() }.toList()
                    val categories = incidents.filter { it["event"]!!.jsonPrimitive.content == "fixture_context" }.map { it["category"]!!.jsonPrimitive.content }.toSet()
                    assertTrue(categories.containsAll(AppLog.Category.entries.map { it.name }))
                    val sessionRows = rows(zip, "timeline.jsonl").filter {
                        (it["context"] as? JsonObject)?.get("cast_session_id")?.jsonPrimitive?.contentOrNull == cast.sessionId
                    }
                    assertEquals(1, sessionRows.count { it["event"]!!.jsonPrimitive.content == "session.start" })
                    assertEquals(1, sessionRows.count { it["event"]!!.jsonPrimitive.content == "session.end" })
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
        StalledBodyServer().use { server ->
            val context = DiagnosticContext(videoId = "wDIrpvH8MzE", taskId = "stall-transfer", generation = 8)
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).readTimeout(45, TimeUnit.SECONDS).build()
            val call = client.newCall(DiagnosticNetwork.tag(Request.Builder().url(server.url).build(), context))
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
                    val stall = timeline.firstOrNull { it["event"]!!.jsonPrimitive.content == "transfer_progress.stalled"
                        && (it["context"] as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull == "stall-transfer" }
                    assertNotNull("Actual transfer stall was not diagnosed", stall)
                    assertEquals("wDIrpvH8MzE", (stall!!["context"] as JsonObject)["video_id"]!!.jsonPrimitive.content)
                    assertEquals(200, (stall["fields"] as JsonObject)["http_status"]!!.jsonPrimitive.int)
                    assertEquals(0, (stall["fields"] as JsonObject)["bytes_read"]!!.jsonPrimitive.int)
                    assertFalse(timeline.any { it["event"]!!.jsonPrimitive.content == "transfer_progress.recovered"
                        && (it["context"] as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull == "stall-transfer" })
                    assertFalse(timeline.joinToString().contains("private-signature"))
                }
            } finally { call.cancel(); response.close(); executor.shutdownNow() }
        }
    }

    private class StalledBodyServer : java.io.Closeable {
        private val socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private val release = java.util.concurrent.CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private val serving = executor.submit {
            socket.accept().use { connection ->
                connection.soTimeout = 5_000
                val reader = connection.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                release.await(45, TimeUnit.SECONDS)
            }
        }
        val url = "http://127.0.0.1:${socket.localPort}/videoplayback?itag=95&sig=private-signature"

        override fun close() {
            release.countDown()
            socket.close()
            try { serving.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    @Test fun decoderAndFirstFrameCallbacksKeepTheOldVideoIdentityAfterSwitch() {
        val origin = DiagnosticContext(videoId = "Wh9klmWAm5s", generation = 1)
        val item = MediaItem.Builder().setUri(Uri.parse("https://fixture.invalid/media"))
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("litube.diagnostic_context", DiagnosticJson.encodeContext(origin)) }).build()).build()
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
            val errors = rows(zip, "timeline.jsonl").filter { it["event"]!!.jsonPrimitive.content == "video_codec_error" }
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.all { (it["context"] as JsonObject)["video_id"]!!.jsonPrimitive.content == "Wh9klmWAm5s" })
            assertFalse(errors.joinToString().contains("private-decoder-message"))
        }
    }
}
