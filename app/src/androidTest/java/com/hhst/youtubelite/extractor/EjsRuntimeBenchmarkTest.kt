package com.hhst.youtubelite.extractor

import android.app.ActivityManager
import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.grack.nanojson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.streams.ChallengeSolver
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Run with -e fullBenchmark true for 30 cold / 100 warm batches per player and backend. */
class EjsRuntimeBenchmarkTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val gson = Gson()
    private val signature = "AOq0QJ8wRQIgXkLtFPK7BpCTMXhJ8ZjTDGJOHJvOePWlNfCvQVoEYzICIQC2ZqLz"

    private fun context(): ExtractionContext = ExtractionContext(
        YoutubeSession("benchmark", YoutubeSession.Account.ANONYMOUS, 0, null, null, null,
            null, "benchmark", 0, null, "offline-fixture", JsonObject(), { "" }),
        object : Downloader() {
            override fun execute(request: Request): Response = throw IOException("BENCHMARK_HAS_NO_NETWORK")
        }, ChallengeSolver { _, _, _, _ -> ChallengeSolver.Solutions(emptyMap(), emptyMap()) },
        null, false, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(45), { true },
        { _, _, _, _, _ -> },
    )

    @Test fun realPlayersOnSandboxAndIndependentWebView() {
        val full = InstrumentationRegistry.getArguments().getString("fullBenchmark") == "true"
        val lowRam = app.getSystemService(ActivityManager::class.java).isLowRamDevice
        val libraries = app.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() } +
            "\nObject.assign(globalThis,lib);\n" + app.assets.open("ejs/yt.solver.core.js").bufferedReader().use { it.readText() }
        val players = mapOf(
            "7460dd14-plasma" to "q82CQICIzYEoVQvCfNlWPeOvJHOJGDTjZqJhXMTCpB7KPFtLkXgIQRw",
            "dac2d7b2-plasma" to "LqZ2CQICIzYEoVQvCfNlWPeOvJHOJGDTjZ8JhXMTCpB7APFtLkXgIQRw8JQ0qOK",
            "ecb23058-ias" to "jOq0QJ8wRQIgAkLtFPK7BpCTMXhJ8ZzTDGJOHJvOePWlNfCvQVoEYzICIQC2",
        )
        val results = mutableListOf<Map<String, Any>>()
        val budgetFailures = mutableListOf<String>()
        for (backend in listOf("sandbox", "webview")) {
            val sharedSandbox = if (backend == "sandbox") SandboxRuntime.create(app, context()) else null
            if (backend == "sandbox" && sharedSandbox == null) {
                results += mapOf("backend" to backend, "status" to "CAPABILITY_UNAVAILABLE")
                continue
            }
            try {
            for ((name, golden) in players) {
                val source = File(app.cacheDir, "benchmark-$name.js")
                instrumentation.context.assets.open("$name.js").use { input ->
                    source.outputStream().use(input::copyTo)
                }
                System.gc()
                val prepared = File(app.cacheDir, "benchmark-$name.prepared")
                val cold = mutableListOf<Long>()
                val warm = mutableListOf<Long>()
                var runtime: JavascriptRuntime? = null
                val baselineHeap = javaHeap()
                val peakHeap = AtomicLong(baselineHeap)
                val sampler = Executors.newSingleThreadScheduledExecutor()
                sampler.scheduleAtFixedRate({ peakHeap.accumulateAndGet(javaHeap(), ::maxOf) }, 0, 10, TimeUnit.MILLISECONDS)
                try {
                    repeat(if (full) 30 else 1) {
                        if (backend != "sandbox") runtime?.close()
                        val ctx = context().withTimeLimit(10_000)
                        val started = System.nanoTime()
                        runtime = if (backend == "sandbox") {
                            requireNotNull(sharedSandbox).also { it.restartIsolate(ctx) }
                        } else RemoteEjsRuntime(app, "benchmark")
                        val instance = requireNotNull(runtime)
                        instance.evaluate(libraries + "\n'loaded'", 10_000, ctx)
                        prepared.delete()
                        instance.compilePlayer(source, false, 10_000, ctx, prepared)
                        val actual = instance.evaluate("_solver.sig(${gson.toJson(signature)})", 1_000, ctx)
                        assertEquals(golden, actual)
                        cold += (System.nanoTime() - started) / 1_000_000
                    }
                    val instance = runtime ?: continue
                    repeat(if (full) 100 else 1) { batch ->
                        val s = (0 until 50).map { signature + "x${batch}_$it" }
                        val n = (0 until 50).map { "N9BWSTFT7vvBJrvQx${batch}_$it" }
                        val started = System.nanoTime()
                        val result = instance.evaluate("JSON.stringify({s:${gson.toJson(s)}.map(x=>_solver.sig(x)),n:${gson.toJson(n)}.map(x=>_solver.n(x))})", 1_000, context())
                        warm += (System.nanoTime() - started) / 1_000_000
                        val values = JsonParser.parseString(result).asJsonObject
                        assertEquals(50, values.getAsJsonArray("s").size())
                        assertTrue(values.getAsJsonArray("n").all { it.asString.isNotEmpty() && !it.asString.startsWith("enhanced_except_") })
                    }
                    val record = mapOf("backend" to instance.version, "player" to name, "coldCount" to cold.size,
                        "warmCount" to warm.size, "coldP95Ms" to p95(cold), "warmP95Ms" to p95(warm),
                        "mainPssKiB" to Debug.getPss(), "extraJavaHeapBytes" to (peakHeap.get() - baselineHeap),
                        "coldMode" to if (backend == "sandbox") "fresh-isolate-reused-service" else "fresh-ejs-host-and-renderer",
                        "rendererPss" to "REQUIRES_ADB_PROCESS_SAMPLING", "nOracle" to "PENDING_BROWSER_RECORDING")
                    results += record
                    instrumentation.sendStatus(2, Bundle().apply { putString("ejsBenchmark", gson.toJson(record)) })
                    File(app.filesDir, "ejs-benchmark.json").writeText(gson.toJson(results))
                    if (full) {
                        if (p95(cold) > if (lowRam) 10_000 else 5_000) budgetFailures += "cold p95: $record"
                        if (p95(warm) > if (lowRam) 300 else 100) budgetFailures += "warm p95: $record"
                        if (peakHeap.get() - baselineHeap > 32L * 1024 * 1024) budgetFailures += "Java heap: $record"
                    }
                } finally { if (backend != "sandbox") runtime?.close(); sampler.shutdownNow(); source.delete(); prepared.delete() }
            }
            } finally { sharedSandbox?.close() }
        }
        assertTrue(budgetFailures.joinToString("\n"), budgetFailures.isEmpty())
    }

    private fun javaHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun p95(values: List<Long>): Long = values.sorted()[((values.size * .95).toInt()).coerceAtMost(values.lastIndex)]
}
