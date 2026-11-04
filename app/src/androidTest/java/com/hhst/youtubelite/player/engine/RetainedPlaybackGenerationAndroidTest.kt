package com.hhst.youtubelite.player.engine

import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production close/resume callbacks must retain ownership of prepared media. */
class RetainedPlaybackGenerationAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun closingAndReopeningTheSameWatchAcceptsItsNewSurfaceFrame() {
        checkRetainedFrame(shorts = false)
    }

    @Test fun returningToThePreparedShortAcceptsItsNewSurfaceFrame() {
        checkRetainedFrame(shorts = true)
    }

    @Test fun cancellingAPendingReplacementDoesNotRestoreThePreviousVideo() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        OfflineWatchFixture(onStreamRead = {
            if (reads.incrementAndGet() == 2) {
                reading.countDown()
                try { check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { returned.countDown() }
            }
        }).use { fixture ->
            try {
                fixture.launch(startSeconds = 0) { compose.waitForIdle() }
                fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
                val prepares = fixture.engine.mediaPrepareCount
                val nextId = "nextTest001"
                fixture.instrumentation.runOnMainSync { fixture.model.openVideo(nextId, 0) }
                assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                fixture.instrumentation.runOnMainSync {
                    fixture.engine.cancelPending()
                    assertFalse(fixture.engine.snapshot.value.prepared)
                    assertFalse(fixture.engine.snapshot.value.firstFrameRendered)
                }
                release.countDown()
                assertTrue(returned.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.waitForIdle()
                // Completing the cancelled read cannot publish either the old attached media or the cancelled replacement as prepared.
                assertEquals(nextId, fixture.engine.snapshot.value.videoId)
                assertFalse(fixture.engine.snapshot.value.prepared)
                assertFalse(fixture.engine.snapshot.value.firstFrameRendered)
                assertFalse(fixture.engine.snapshot.value.isPlaying)
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
            } finally { release.countDown() }
        }
    }

    private fun checkRetainedFrame(shorts: Boolean) {
        OfflineWatchFixture(videoAsset = "player/freeze-motion/video.ts").use { fixture ->
            fixture.launch(startSeconds = 0) { compose.waitForIdle() }
            fixture.await { fixture.engine.snapshot.value.firstFrameRendered }
            val surface = requireNotNull(fixture.surface())
            val prepares = fixture.engine.mediaPrepareCount
            if (shorts) fixture.instrumentation.runOnMainSync {
                fixture.model.onShortsOpened()
                fixture.model.openShort(fixture.id)
                fixture.engine.play()
            }
            fixture.instrumentation.runOnMainSync {
                if (shorts) fixture.model.onShortsStopped()
                else {
                    fixture.model.enterMiniPlayer()
                    fixture.model.onMiniClose()
                }
                // Recreate the actual holder as a returning window does; the decoder and the prepared media remain under engine ownership.
                surface.visibility = View.INVISIBLE
            }
            compose.waitForIdle()
            fixture.await { !surface.holder.surface.isValid && !fixture.engine.snapshot.value.firstFrameRendered }
            fixture.instrumentation.runOnMainSync {
                if (shorts) {
                    fixture.model.onShortsOpened()
                    fixture.model.openShort(fixture.id)
                } else {
                    fixture.model.openVideo(fixture.id)
                    fixture.model.onPlayPause()
                }
                surface.visibility = View.VISIBLE
            }
            compose.waitForIdle()
            fixture.await { surface.holder.surface.isValid && fixture.engine.snapshot.value.isPlaying }
            val position = fixture.engine.snapshot.value.positionMs
            fixture.await { fixture.engine.snapshot.value.positionMs > position + 500 }
            fixture.await { fixture.model.hasRenderedFrame(surface) }
            assertTrue(fixture.model.uiState.value.firstFrameRendered)
            assertFalse(fixture.model.uiState.value.loading)
            assertEquals("Returning to prepared media must not reload it", prepares, fixture.engine.mediaPrepareCount)
        }
    }
}
