@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.watch.WatchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Records composited display frames, including SurfaceView, on the real animation clock. */
// This probe requires the elapsed-clock metadata emitted by API 35 screenrecord.
@SdkSuppress(minSdkVersion = 35)
class PlayerFrameContinuityAndroidTest {
    @Before fun requireDisplayRecordingOptIn() {
        assumeTrue("Run composited-display diagnostics with -e visual_frames 1",
            InstrumentationRegistry.getArguments().getString("visual_frames") == "1")
    }

    @Test fun fullscreenRoundTripsKeepTheDecodedPictureVisible() = withPortraitRotation {
        OfflineWatchFixture().use { fixture ->
            fixture.launch()
            fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
            hideChrome(fixture)
            val surface = requireNotNull(fixture.surface())
            val prepares = fixture.engine.mediaPrepareCount
            val switches = fixture.engine.surfaceSwitchCount
            val position = fixture.model.positionState.value
            recordPictures(fixture, "manual-fullscreen") {
                repeat(2) {
                    fixture.instrumentation.runOnMainSync {
                        fixture.model.setFullscreenManual(true)
                        fixture.model.onHideControls()
                    }
                    fixture.await { fixture.model.uiState.value.fullscreen }
                    fixture.instrumentation.runOnMainSync {
                        fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    assertLiveSurface(fixture, surface, prepares, switches)
                    fixture.instrumentation.runOnMainSync {
                        fixture.model.setFullscreenManual(false)
                        fixture.model.onHideControls()
                    }
                    fixture.await { !fixture.model.uiState.value.fullscreen }
                    fixture.instrumentation.runOnMainSync {
                        fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    assertLiveSurface(fixture, surface, prepares, switches)
                }
                assertTrue("Playback must advance through fullscreen round trips", fixture.model.positionState.value > position)
            }
        }
    }

    @Test fun fullscreenTouchSwipesKeepThePictureThroughDragReleaseAndRotation() = withPortraitRotation {
        OfflineWatchFixture("swipePix001").use { fixture ->
            fixture.launch()
            fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
            hideChrome(fixture)
            val surface = requireNotNull(fixture.surface())
            val prepares = fixture.engine.mediaPrepareCount
            val switches = fixture.engine.surfaceSwitchCount
            val position = fixture.model.positionState.value
            recordPictures(fixture, "gesture-fullscreen") {
                // The recorder runs independently: the full gesture and window transition stay inside one recording.
                swipe(fixture, swipeCoordinates(fixture, up = true))
                fixture.await { fixture.model.uiState.value.fullscreen }
                assertLiveSurface(fixture, surface, prepares, switches)
                fixture.instrumentation.runOnMainSync {
                    fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                assertLiveSurface(fixture, surface, prepares, switches)
                hideChrome(fixture)
                swipe(fixture, swipeCoordinates(fixture, up = false))
                fixture.await {
                    !fixture.model.uiState.value.fullscreen &&
                        fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                }
                assertLiveSurface(fixture, surface, prepares, switches)
                assertTrue("Playback must advance through the real swipes and rotation", fixture.model.positionState.value > position)
            }
        }
    }

    @Test fun backToMiniAndRestoreKeepTheDecodedPictureVisibleAcrossWindows() = withPortraitRotation {
        OfflineWatchFixture().use { fixture ->
            val intent = Intent(fixture.app, HomeActivity::class.java)
                .putExtra(ContentRepositories.EXTRA_FIXTURE_SCENE, true)
                .putExtra("playback_test", true)
            ActivityScenario.launch<HomeActivity>(intent).use { source ->
                lateinit var home: HomeActivity
                source.onActivity { home = it }
                fixture.launch(source = home)
                fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
                hideChrome(fixture)
                val prepares = fixture.engine.mediaPrepareCount
                val position = fixture.model.positionState.value
                recordPictures(fixture, "back-mini-restore") {
                    fixture.instrumentation.runOnMainSync { fixture.activity.onBackPressedDispatcher.onBackPressed() }
                    fixture.await { fixture.model.uiState.value.mini && home.lifecycle.currentState == Lifecycle.State.RESUMED }
                    val monitor = fixture.instrumentation.addMonitor(WatchActivity::class.java.name, null, false)
                    try {
                        fixture.instrumentation.runOnMainSync { fixture.model.onMiniRestore() }
                        fixture.activity = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as WatchActivity
                        fixture.await { !fixture.model.uiState.value.mini && fixture.engine.snapshot.value.firstFrameRendered }
                        // Cross-window restore legitimately replaces the host view; its new surface must draw before the cover leaves.
                        assertLiveSurface(fixture, requireNotNull(fixture.surface()), prepares, fixture.engine.surfaceSwitchCount)
                        assertTrue("Playback must advance through Back and mini restore", fixture.model.positionState.value > position)
                    } finally {
                        fixture.instrumentation.removeMonitor(monitor)
                    }
                }
            }
        }
    }

    private fun withPortraitRotation(block: () -> Unit) {
        fun shell(command: String): String {
            val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes().decodeToString().trim() }
        }
        val autoRotate = shell("settings get system accelerometer_rotation")
        val userRotation = shell("settings get system user_rotation")
        val immersiveConfirmation = shell("settings get secure immersive_mode_confirmations")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        shell("settings put secure immersive_mode_confirmations confirmed")
        try { block() } finally {
            for ((name, value) in listOf("accelerometer_rotation" to autoRotate, "user_rotation" to userRotation)) {
                shell(if (value == "null" || value.isBlank()) "settings delete system $name"
                    else "settings put system $name $value")
            }
            shell(if (immersiveConfirmation == "null" || immersiveConfirmation.isBlank())
                "settings delete secure immersive_mode_confirmations"
            else "settings put secure immersive_mode_confirmations $immersiveConfirmation")
        }
    }

    private fun hideChrome(fixture: OfflineWatchFixture) {
        fixture.instrumentation.runOnMainSync { fixture.model.onHideControls() }
        fixture.await { !fixture.model.uiState.value.controlsVisible }
        Thread.sleep(400) // Match WatchFullscreenSwipe: let chrome leave the touch hit path.
    }

    private data class SwipeCoordinates(val x: Float, val fromY: Float, val toY: Float)

    private fun swipeCoordinates(fixture: OfflineWatchFixture, up: Boolean): SwipeCoordinates {
        var coordinates: SwipeCoordinates? = null
        fixture.instrumentation.runOnMainSync {
            val surface = requireNotNull(fixture.surface())
            val visible = Rect()
            val globalOrigin = Point()
            check(surface.getGlobalVisibleRect(visible, globalOrigin)) { "The decoded surface must be visible" }
            val onScreen = IntArray(2)
            surface.getLocationOnScreen(onScreen)
            visible.offset(onScreen[0] - globalOrigin.x, onScreen[1] - globalOrigin.y)
            val decor = fixture.activity.window.decorView
            val windowOrigin = IntArray(2)
            decor.getLocationOnScreen(windowOrigin)
            val metrics = fixture.activity.resources.displayMetrics
            check(visible.intersect(Rect(windowOrigin[0], windowOrigin[1],
                windowOrigin[0] + decor.width, windowOrigin[1] + decor.height)) &&
                visible.intersect(Rect(1, 1, metrics.widthPixels - 1, metrics.heightPixels - 1)))
            // Starting 400px above the embedded center can leave the screen;
            // start near the visible bottom so every event stays in bounds.
            val fromY = if (up) visible.bottom - minOf(32f, visible.height() * .1f)
                else visible.exactCenterY()
            val travel = if (up) maxOf(400f, decor.height * .18f) else visible.height() * .25f
            val toY = (fromY + if (up) -travel else travel).coerceIn(1f, metrics.heightPixels - 2f)
            val minimum = decor.height * .14f + ViewConfiguration.get(fixture.activity).scaledTouchSlop
            assertTrue("The in-bounds swipe must pass the 35%-of-40% commit distance ($fromY -> $toY, min=$minimum)",
                kotlin.math.abs(toY - fromY) > minimum)
            coordinates = SwipeCoordinates(visible.exactCenterX(), fromY, toY)
        }
        return requireNotNull(coordinates)
    }

    private fun swipe(fixture: OfflineWatchFixture, path: SwipeCoordinates) {
        val downTime = SystemClock.uptimeMillis()
        var released = false
        var lastY = path.fromY
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, path.x, y, 0)
            try { fixture.instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, path.fromY)
            for (step in 1..12) {
                lastY = path.fromY + (path.toY - path.fromY) * step / 12
                send(MotionEvent.ACTION_MOVE, lastY)
                Thread.sleep(40)
            }
            send(MotionEvent.ACTION_UP, path.toY)
            released = true
        } finally {
            if (!released) runCatching { send(MotionEvent.ACTION_CANCEL, lastY) }
        }
    }

    private fun assertLiveSurface(fixture: OfflineWatchFixture, surface: SurfaceView, prepares: Int, switches: Int) {
        var observedOwner = false
        fun hasCover(view: View): Boolean {
            fun tagged(node: SemanticsNode): Boolean =
                node.config.getOrNull(SemanticsProperties.TestTag) == "player-handoff-frame" || node.children.any(::tagged)
            val owner = runCatching {
                view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
            }.getOrNull()
            if (owner != null) observedOwner = true
            if (owner?.let { tagged(it.rootSemanticsNode) } == true) return true
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                if (hasCover(view.getChildAt(index))) return true
            }
            return false
        }
        fixture.await {
            observedOwner = false
            fixture.model.hasRenderedFrame(surface) && fixture.model.uiState.value.firstFrameRendered &&
                fixture.engine.snapshot.value.isPlaying && !hasCover(fixture.activity.window.decorView) && observedOwner
        }
        fixture.instrumentation.runOnMainSync {
            assertSame("Fullscreen gestures and rotation must retain the original SurfaceView", surface, fixture.surface())
            assertEquals("Gestures must not prepare the media again", prepares, fixture.engine.mediaPrepareCount)
            assertEquals("Gestures must not switch the decoder's output surface", switches, fixture.engine.surfaceSwitchCount)
        }
    }

    private fun recordPictures(fixture: OfflineWatchFixture, label: String, action: () -> Unit) {
        val recording = RecordedPictureProbe(fixture, label)
        var actionStartNs = 0L
        var actionEndNs = 0L
        val actionResult = runCatching {
            recording.awaitStarted()
            Thread.sleep(PREFIX_MS) // Recorder-only warmup; production clocks stay unchanged.
            actionStartNs = SystemClock.elapsedRealtimeNanos()
            try { action() } finally { actionEndNs = SystemClock.elapsedRealtimeNanos() }
        }
        val tail = runCatching { if (actionEndNs > 0L) Thread.sleep(SUFFIX_MS) }
        val evidence = runCatching {
            val video = recording.stopAndCopy()
            if (actionStartNs > 0L) recording.verify(video, actionStartNs, actionEndNs)
        }
        (actionResult.exceptionOrNull() ?: tail.exceptionOrNull())?.let { error ->
            evidence.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        evidence.getOrThrow()
    }

    private class RecordedPictureProbe(private val fixture: OfflineWatchFixture, private val label: String) {
        private val token = "litube-$label-${UUID.randomUUID()}"
        private val remotePath = "/sdcard/Download/$token.mp4"
        private val pid = AtomicInteger()
        private val exitCode = AtomicInteger(Int.MIN_VALUE)
        private val readerFailure = AtomicReference<Throwable?>()
        private val pidReady = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val exitWall = AtomicLong()
        private val launchWall = SystemClock.elapsedRealtime()
        private var signalWall = 0L
        private val output = executeScript("""
            screenrecord --size 360x800 --bit-rate 1000000 --time-limit 120 $remotePath </dev/null >/dev/null 2>&1 &
            recorder=${'$'}!
            echo PID:${'$'}recorder
            wait "${'$'}recorder"
            echo EXIT:${'$'}?
        """.trimIndent())
        private val reader = Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { stream ->
                    stream.forEachLine { line ->
                        when {
                            line.startsWith("PID:") -> {
                                pid.set(line.removePrefix("PID:").toInt())
                                pidReady.countDown()
                            }
                            line.startsWith("EXIT:") -> exitCode.set(line.removePrefix("EXIT:").toInt())
                        }
                    }
                }
            } catch (error: Throwable) {
                readerFailure.set(error)
            } finally {
                exitWall.set(SystemClock.elapsedRealtime())
                pidReady.countDown()
                finished.countDown()
            }
        }, "fullscreen-record-output").apply { isDaemon = true; start() }

        // UiAutomation uses Runtime.exec(String), not a shell parser: the -c
        // argument must be whitespace-free; the inner shell gets the full script.
        // executeShellCommandRw would simplify stdin but only exists from API 31.
        private fun executeScript(script: String): ParcelFileDescriptor {
            val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return fixture.instrumentation.uiAutomation.executeShellCommand(
                "sh -c echo\${IFS}$encoded|base64\${IFS}-d|sh")
        }

        fun awaitStarted() {
            assertTrue("$label recorder must publish its own PID", pidReady.await(10, TimeUnit.SECONDS))
            readerFailure.get()?.let { throw AssertionError("$label recorder startup failed", it) }
            assertTrue("$label recorder PID must be valid", pid.get() > 0)
            assertTrue("$label recorder exited before the action", finished.count > 0)
        }

        private fun interruptOwnRecorder() {
            if (pid.get() <= 0 || exitCode.get() != Int.MIN_VALUE) return
            // Check the unique output path as well as the PID; never signal a recycled PID or another test's recorder.
            val stop = """
                command=${'$'}(tr '\000' ' ' </proc/${pid.get()}/cmdline)
                case "${'$'}command" in
                  *$remotePath*) kill -2 ${pid.get()} ;;
                  *) echo RECORDER_IDENTITY_MISMATCH ;;
                esac
            """.trimIndent()
            ParcelFileDescriptor.AutoCloseInputStream(executeScript(stop)).use { stream ->
                assertTrue("$label must only stop its own recorder", stream.readBytes().decodeToString().isBlank())
            }
        }

        fun stopAndCopy(): File {
            val directory = File(fixture.app.cacheDir, "visual-frame-recordings").apply { mkdirs() }
            val video = File(directory, "$token.mp4")
            try {
                signalWall = SystemClock.elapsedRealtime()
                interruptOwnRecorder()
                assertTrue("$label recorder must exit and flush its MP4", finished.await(15, TimeUnit.SECONDS))
                reader.join(1_000)
                val source = fixture.instrumentation.uiAutomation.executeShellCommand("cat $remotePath")
                ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                    video.outputStream().use { input.copyTo(it) }
                }
                fixture.instrumentation.sendStatus(0, Bundle().apply { putString("video_recording", video.absolutePath) })
                readerFailure.get()?.let { throw AssertionError("$label recorder output failed", it) }
                assertEquals("$label recorder must finish normally after SIGINT", 0, exitCode.get())
                assertTrue("$label recording must not be empty: $video", video.length() > 0L)
                return video
            } finally {
                if (exitCode.get() == Int.MIN_VALUE) {
                    // A failure must not leave the recorder running into the next test; retry here with the same PID/path ownership check.
                    runCatching { interruptOwnRecorder() }
                    finished.await(5, TimeUnit.SECONDS)
                }
                // Scoped storage blocks reading shell-owned sdcard files; keep the private cache copy for host analysis.
                if (exitCode.get() != Int.MIN_VALUE && finished.count == 0L) {
                    val cleanup = fixture.instrumentation.uiAutomation.executeShellCommand("rm -f $remotePath")
                    ParcelFileDescriptor.AutoCloseInputStream(cleanup).use { it.readBytes() }
                } else {
                    // Preserve a still-open file rather than unlinking a writer whose exit is unconfirmed; the test already fails.
                    fixture.instrumentation.sendStatus(0, Bundle().apply {
                        putString("video_recorder_cleanup_failed", "pid=${pid.get()} path=$remotePath")
                    })
                }
                output.close()
                reader.join(1_000)
            }
        }

        /** API 35 screenrecord writes elapsedRealtime timestamps into an MP4 metadata track. */
        private fun readFrameElapsedNanos(video: File, expectedFrames: Int): List<Long> {
            val extractor = MediaExtractor()
            var elapsed: List<Long>? = null
            try {
                extractor.setDataSource(video.absolutePath)
                for (track in 0 until extractor.trackCount) {
                    if (extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) != "application/octet-stream") continue
                    extractor.selectTrack(track)
                    try {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        val size = extractor.sampleSize
                        assertTrue("$label metadata sample must be bounded and readable", size in 16L..1_048_576L)
                        val buffer = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals("$label must read the entire metadata sample", size.toInt(), extractor.readSampleData(buffer, 0))
                        buffer.position(0)
                        val magic = ByteArray(16)
                        buffer.get(magic)
                        if (!magic.contentEquals("#VV1NSC0PET1ME2#".toByteArray(Charsets.US_ASCII))) continue
                        assertTrue("$label must contain exactly one V2 metadata track", elapsed == null)
                        assertTrue("$label V2 metadata header must be complete", size >= 32L)
                        assertEquals("$label Winscope metadata version", 2, buffer.int)
                        // This offset maps wall clock to elapsed clock; frame entries are already elapsed nanoseconds.
                        buffer.long
                        val count = buffer.int
                        assertEquals("$label metadata must describe every encoded frame", expectedFrames, count)
                        // The C++ writer uses uint32_t here (its prose comment says 8B).
                        assertEquals("$label V2 payload length", 32L + count * 8L, size)
                        val timestamps = List(count) { buffer.long }.sorted()
                        assertTrue("$label elapsed frame timestamps must be positive", timestamps.all { it > 0L })
                        assertEquals("$label elapsed frame timestamps must be unique", count, timestamps.distinct().size)
                        assertTrue("$label V2 metadata must have one complete sample", !extractor.advance())
                        elapsed = timestamps
                    } finally { extractor.unselectTrack(track) }
                }
            } finally { extractor.release() }
            assertTrue("$label API 35 screenrecord must expose its V2 elapsed-time metadata", elapsed != null)
            return requireNotNull(elapsed)
        }

        fun verify(video: File, actionStartNs: Long, actionEndNs: Long) {
            val extractor = MediaExtractor()
            val rawTimes = mutableListOf<Long>()
            try {
                extractor.setDataSource(video.absolutePath)
                val track = (0 until extractor.trackCount).first { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                while (extractor.sampleTime >= 0L) {
                    assertEquals("$label must contain complete encoded frames", 0,
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
                    rawTimes += extractor.sampleTime
                    if (!extractor.advance()) break
                }
            } finally { extractor.release() }
            val ordered = rawTimes.sorted() // MP4 decode order may contain B-frame reordering.
            assertTrue("$label must contain a real video timeline", ordered.size >= 2)
            assertEquals("$label cannot map duplicate PTS to distinct decoded frames", ordered.size, ordered.distinct().size)
            val times = ordered.map { it - ordered.first() }
            val elapsed = readFrameElapsedNanos(video, times.size)
            // MPEG4Writer may adjust STTS durations by <100us, so MP4 PTS is only an index, not an elapsed clock.
            val durationMs = times.last() / 1_000L
            val wallMs = exitWall.get() - launchWall
            val actionMs = (actionEndNs - actionStartNs) / 1_000_000L
            val maxGapNs = elapsed.zipWithNext { before, after -> after - before }.maxOrNull() ?: Long.MAX_VALUE
            val summary = "$label video=$video wallMs=$wallMs durationMs=$durationMs actionMs=$actionMs " +
                "prefixMs=${actionStartNs / 1_000_000L - launchWall} suffixMs=${signalWall - actionEndNs / 1_000_000L} " +
                "flushMs=${exitWall.get() - signalWall} maxGapUs=${maxGapNs / 1_000L} frames=${times.size} " +
                "actionStartElapsedNs=$actionStartNs actionEndElapsedNs=$actionEndNs " +
                "firstFrameElapsedNs=${elapsed.first()} lastFrameElapsedNs=${elapsed.last()}"
            val trace = File(video.parentFile, "${video.nameWithoutExtension}.txt")
            trace.writeText("$summary\n")
            fixture.instrumentation.sendStatus(0, Bundle().apply { putString("video_recording_timing", summary) })
            val retriever = MediaMetadataRetriever()
            val blueCounts = ArrayList<Int>(times.size)
            var pixels = IntArray(0)
            try {
                retriever.setDataSource(video.absolutePath)
                val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                assertEquals("$label decoded frame indexes must match presentation timestamps", times.size, frames)
                val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                for (first in times.indices step 2) {
                    val count = minOf(2, times.size - first)
                    val bitmaps = retriever.getFramesAtIndex(first, count, params)
                    try {
                        assertEquals("$label must decode every requested frame (index=$first)", count, bitmaps.size)
                        for (bitmap in bitmaps) {
                            val width = bitmap.width
                            val height = bitmap.height
                            if (pixels.size != width * height) pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            var blue = 0
                            // Half-resolution capture keeps the encoder off the app's frame budget; the grid preserves sampling density.
                            for (y in 2 until height step 4) for (x in 2 until width step 4) {
                                val color = pixels[y * width + x]
                                // Blue survives the mini controls' 40% scrim.
                                if (Color.blue(color) > 100 &&
                                    Color.blue(color) > maxOf(Color.red(color), Color.green(color)) + 60) blue++
                            }
                            blueCounts += blue
                        }
                    } finally { bitmaps.forEach(Bitmap::recycle) }
                }
            } finally { retriever.release() }
            val details = times.indices.joinToString(",") { "${times[it]}:${elapsed[it]}:${blueCounts[it]}" }
            trace.appendText("ptsUs:elapsedNs:blueSamples=$details\n")
            fixture.instrumentation.sendStatus(0, Bundle().apply { putString("video_pixel_trace", "$summary ptsUs:elapsedNs:blueSamples=$details") })
            assertTrue("Recorder warmup must precede the action: $summary", actionStartNs / 1_000_000L - launchWall >= PREFIX_MS)
            assertTrue("Recorder tail must follow the action: $summary", signalWall - actionEndNs / 1_000_000L >= SUFFIX_MS)
            assertTrue("Recording coverage has a missing interval: $summary", maxGapNs <= MAX_FRAME_GAP_MS * 1_000_000L)
            val firstBlue = blueCounts.indexOfFirst { it > MIN_PICTURE_SAMPLES }
            val lastBlue = blueCounts.indexOfLast { it > MIN_PICTURE_SAMPLES }
            assertTrue("$label recording never contains the decoded blue picture: $video", firstBlue >= 0)
            // Encoded frame times are compared on the elapsed clock: recorder
            // startup and muxer flush latency cannot create false coverage.
            val marginNs = BLUE_MARGIN_MS * 1_000_000L
            assertTrue("$label first encoded frame must precede the action by 250ms: $summary",
                elapsed.first() <= actionStartNs - marginNs)
            assertTrue("$label last encoded frame must follow the action by 250ms: $summary",
                elapsed.last() >= actionEndNs + marginNs)
            assertTrue("$label blue prefix does not cover the action: $video firstBlueElapsedNs=${elapsed[firstBlue]}",
                elapsed[firstBlue] <= actionStartNs - marginNs)
            assertTrue("$label blue suffix does not cover the action: $video lastBlueElapsedNs=${elapsed[lastBlue]}",
                elapsed[lastBlue] >= actionEndNs + marginNs)
            val missing = (firstBlue..lastBlue).filter { blueCounts[it] <= MIN_PICTURE_SAMPLES }
            assertTrue("$label lost the picture inside the recorded action: $video missingPTS=${missing.map { times[it] }}", missing.isEmpty())
        }
    }

    private companion object {
        // System rotation shrinks and fades its snapshot; keep a visible blue region while rejecting missing, white, red or black frames.
        const val MIN_PICTURE_SAMPLES = 128
        const val PREFIX_MS = 3_000L
        const val SUFFIX_MS = 900L
        const val BLUE_MARGIN_MS = 250L
        const val MAX_FRAME_GAP_MS = 150L
    }
}
