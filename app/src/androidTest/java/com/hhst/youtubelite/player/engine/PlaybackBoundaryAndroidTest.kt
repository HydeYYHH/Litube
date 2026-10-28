package com.hhst.youtubelite.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBoundaryAndroidTest {
    @Test fun stoppingSamplesTheAttachedVideoBeforeClearingIt() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch()
            val previous = fixture.engine.onWatchBoundary
            val observed = mutableListOf<Pair<String?, Long>>()
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.engine.onWatchBoundary = { state, position ->
                        observed += state.videoId to position
                        previous?.invoke(state, position)
                    }
                    fixture.engine.seekTo(3_250)
                    fixture.engine.pause()
                    fixture.engine.stop()
                }
                assertEquals(1, observed.size)
                assertEquals(fixture.id, observed.single().first)
                assertTrue(observed.single().second in 3_250..3_750)
                assertNull(fixture.engine.snapshot.value.videoId)
            } finally {
                fixture.instrumentation.runOnMainSync { fixture.engine.onWatchBoundary = previous }
            }
        }
    }
}
