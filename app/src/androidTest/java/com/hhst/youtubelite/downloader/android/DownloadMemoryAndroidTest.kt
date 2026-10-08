@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.android

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadFinalizerImpl
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Uses retained download inputs without changing the task or its original files. */
class DownloadMemoryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun retainedInputsLargerThanHeapMuxDecodeAndSeek() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val taskId = InstrumentationRegistry.getArguments().getString("taskId").orEmpty()
        assumeTrue("Supply a retained large failed download with -e taskId", taskId.isNotBlank())
        val snapshot = GlobalContext.get().get<DownloadRepository>().transact { snapshot(taskId) }!!
        val inputs = snapshot.assets.filter { it.asset.kind == AssetKind.VIDEO }
            .flatMap { it.components }.map { File(app.cacheDir, "download/$taskId/${it.component.id}.part") }
            .filter { it.isFile && it.length() > 0 }
        val runtime = Runtime.getRuntime()
        assertTrue("Input must exceed the heap limit to reproduce whole-file allocation", inputs.maxOf { it.length() } > runtime.maxMemory())
        val originalLengths = inputs.map { it.length() }
        val output = File.createTempFile("download-memory-acceptance", ".mp4", app.cacheDir)
        val peak = AtomicLong()
        val monitor = Executors.newSingleThreadScheduledExecutor()
        monitor.scheduleAtFixedRate({ peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf) }, 0, 20, TimeUnit.MILLISECONDS)
        val record = linkedMapOf<String, Any>("inputBytes" to originalLengths, "heapLimitBytes" to runtime.maxMemory())
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val result = DownloadFinalizerImpl().muxAndVerify(inputs, output, false)
            assertTrue("mux $result", result is MuxResult.Ok)
            record["muxMs"] = android.os.SystemClock.elapsedRealtime() - started
            record["outputBytes"] = output.length()
            record["durationUs"] = (result as MuxResult.Ok).durationUs
            val ready = CountDownLatch(1)
            val frames = AtomicInteger()
            val error = AtomicReference<String>()
            lateinit var player: ExoPlayer
            activity.scenario.onActivity { host ->
                player = ExoPlayer.Builder(host).build().apply {
                    volume = 0f
                    setVideoSurfaceView(host.surface)
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { frames.incrementAndGet(); ready.countDown() }
                        override fun onPlayerError(failure: PlaybackException) { error.set(failure.errorCodeName); ready.countDown() }
                    })
                    setMediaItem(MediaItem.fromUri(output.toURI().toString()))
                    prepare(); play()
                }
            }
            try {
                assertTrue("First frame timed out", ready.await(20, TimeUnit.SECONDS))
                assertNull(error.get())
                assertTrue(frames.get() > 0)
                instrumentation.runOnMainSync { player.seekTo(90_000) }
                val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
                while (frames.get() < 2 && error.get() == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertNull(error.get())
                assertTrue("Seek frame timed out", frames.get() >= 2)
                record["decodedAndSeeked"] = true
            } finally { instrumentation.runOnMainSync { player.release() } }
            assertEquals(originalLengths, inputs.map { it.length() })
        } finally {
            monitor.shutdownNow()
            record["peakHeapBytes"] = peak.get()
            File(app.filesDir, "download-memory-acceptance.json").writeText(Gson().toJson(record))
            instrumentation.sendStatus(2, Bundle().apply { putString("downloadMemory", Gson().toJson(record)) })
            output.delete()
        }
    }
}
