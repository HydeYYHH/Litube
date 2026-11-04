package com.hhst.youtubelite.watch

import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.watch.presentation.withWatchPresentation
import org.junit.Assert.*
import org.junit.Test

class WatchPlayerStateTest {
    @Test fun handoffRetainsGeometryAndUsesLivePlaybackAndFirstFrame() {
        val old = PlayerUiState(videoId = "video", visible = true, loading = true, fullscreen = true)
        val live = old.copy(mini = true, fullscreen = false, loading = false, isPlaying = true, firstFrameRendered = true)
        val rendered = live.withWatchPresentation(old)
        assertTrue(rendered.fullscreen)
        assertFalse(rendered.mini)
        assertFalse(rendered.loading)
        assertTrue(rendered.isPlaying)
        assertTrue(rendered.firstFrameRendered)
        assertFalse(live.copy(firstFrameRendered = false).withWatchPresentation(old).firstFrameRendered)
        assertSame(live, live.withWatchPresentation(old.copy(videoId = "other")))
    }
}
