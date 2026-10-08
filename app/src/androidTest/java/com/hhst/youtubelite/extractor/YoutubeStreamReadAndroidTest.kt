package com.hhst.youtubelite.extractor

import android.os.Bundle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan

/** Explicitly invoked real-network first-read acceptance; follows the device's WebView account. */
class YoutubeStreamReadAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    @Test fun selectedMediaAndManifestsCanBeRead() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ids = InstrumentationRegistry.getArguments().getString("videoIds").orEmpty()
            .split(',').mapNotNull(VideoId::parse)
        assumeTrue("Supply -e videoIds id1,id2 for real-network acceptance", ids.isNotEmpty())
        val extractor = GlobalContext.get().get<Extractor>()
        val policy = GlobalContext.get().get<YoutubeMediaRequests>()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).addInterceptor(policy.interceptor()).build()
        val records = mutableListOf<Map<String, Any>>()
        val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleAtFixedRate({
            val stacks = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                "${thread.name} ${thread.state}\n" + frames.joinToString("\n") { "  at $it" }
            }
            File(instrumentation.targetContext.filesDir, "youtube-read-thread-stacks.txt").writeText(stacks)
            File(instrumentation.targetContext.filesDir, "youtube-stream-diagnostics.txt").writeText(extractor.extractionDiagnostics())
        }, 30, 30, TimeUnit.SECONDS)
        fun read(id: String, url: String, plan: RequestPlan, manifest: Boolean) {
            val started = System.nanoTime()
            val length = if (manifest) -1L else 16L * 1024
            client.newCall(policy.build(url, plan, 0, length)).execute().use { response ->
                assertTrue("Media HTTP stage failed: ${response.code}", response.isSuccessful)
                val body = requireNotNull(response.body)
                val source = body.source()
                val bytes = source.readByteArray(minOf(if (manifest) 1024 else 16L * 1024,
                    body.contentLength().takeIf { it > 0 } ?: 1024))
                assertTrue("No first bytes", bytes.isNotEmpty())
                val record = mapOf("videoId" to id, "profile" to plan.profile.name,
                    "protocol" to plan.protocol.name, "httpStatus" to response.code,
                    "firstReadBytes" to bytes.size, "firstReadMs" to (System.nanoTime() - started) / 1_000_000)
                records += record
                instrumentation.sendStatus(2, Bundle().apply { putString("youtubeStreamRead", Gson().toJson(record)) })
            }
        }
        try {
            for (id in ids) {
                val stream = extractor.extractFresh(id).stream.await()
                val selected = listOfNotNull(stream.formats.firstOrNull { it.audioOnly },
                    stream.formats.firstOrNull { !it.audioOnly && it.height <= 720 } ?: stream.formats.firstOrNull { !it.audioOnly })
                assertTrue("No supported media or manifest", selected.isNotEmpty() || stream.manifests.isNotEmpty())
                for (format in selected) read(id, format.url, requireNotNull(format.requestPlan), false)
                for (manifest in stream.manifests.distinctBy { it.protocol }) read(id, manifest.url, manifest.requestPlan, true)
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "youtube-stream-read.json").writeText(Gson().toJson(records))
            File(instrumentation.targetContext.filesDir, "youtube-stream-diagnostics.txt").writeText(extractor.extractionDiagnostics())
            watchdog.shutdownNow()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
