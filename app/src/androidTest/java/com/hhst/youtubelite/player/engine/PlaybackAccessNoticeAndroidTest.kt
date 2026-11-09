package com.hhst.youtubelite.player.engine

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hhst.youtubelite.extractor.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class PlaybackAccessNoticeAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun botCheckExplainsTheNetworkOrSessionRestrictionWithoutCallingItPayment() {
        OfflineWatchFixture("verify00001").use { fixture ->
            fixture.launch(awaitReady = false)
            val field = fixture.engine.javaClass.getDeclaredField("extractor").apply { isAccessible = true }
            val previous = field.get(fixture.engine)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val denied = PlaybackResolutionException(RequestError(RequestFailure.VERIFICATION, "playability_bot_check"))
            var calls = 0
            val cache = object : Cache by MemCache() {
                override fun getStream(videoId: String): Stream? { calls++; throw denied }
                override fun getMetadata(videoId: String) = Metadata().apply { id = videoId }
            }
            try {
                fixture.instrumentation.runOnMainSync {
                    field.set(fixture.engine, Extractor(cache, scope))
                    fixture.model.openVideo("verify00002", 0)
                }
                fixture.await { fixture.engine.snapshot.value.error != null }
                val state = fixture.engine.snapshot.value
                assertEquals(fixture.app.getString(com.hhst.youtubelite.R.string.player_verification_required), state.error)
                assertNull(state.notice)
                assertFalse(state.isBuffering)
                assertFalse(state.prepared)
                assertNotNull(state.failureReport)
                assertEquals("A server verification denial must not start automatic retry loops", 1, calls)
            } finally {
                fixture.instrumentation.runOnMainSync { field.set(fixture.engine, previous) }
                scope.cancel()
            }
        }
    }

    @Test fun paymentStopsLoadingAndShowsANoticeWithoutAnErrorReport() {
        OfflineWatchFixture("access00001").use { fixture ->
            fixture.launch(awaitReady = false)
            val field = fixture.engine.javaClass.getDeclaredField("extractor").apply { isAccessible = true }
            val previous = field.get(fixture.engine)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val denied = PlaybackResolutionException(RequestError(RequestFailure.UNSUPPORTED, "playability_unplayable"),
                PlaybackResolutionDiagnostics("AUTHENTICATED", "fixture", 0, listOf(
                    PlayerLoadAttempt(PlayerClient.VISIONOS, "UNPLAYABLE", null, 1, PlayerAccessRestriction.PAYMENT_REQUIRED))))
            val cache = object : Cache by MemCache() {
                override fun getStream(videoId: String): Stream? = throw denied
                override fun getMetadata(videoId: String) = Metadata().apply { id = videoId; title = "Paid video" }
            }
            try {
                fixture.instrumentation.runOnMainSync {
                    field.set(fixture.engine, Extractor(cache, scope))
                    fixture.model.openVideo("access00002", 0)
                }
                fixture.await { fixture.model.uiState.value.notice == PlaybackNotice.PAYMENT_REQUIRED }
                val state = fixture.engine.snapshot.value
                assertFalse(state.isBuffering)
                assertFalse(state.isPlaying)
                assertFalse(state.userWantsPlay)
                assertNull(state.error)
                assertNull(state.failureReport)
                assertFalse(fixture.model.uiState.value.failureDialogVisible)
                compose.onNodeWithTag("player-access-notice", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("player-buffering", useUnmergedTree = true).assertDoesNotExist()
            } finally {
                fixture.instrumentation.runOnMainSync { field.set(fixture.engine, previous) }
                scope.cancel()
            }
        }
    }
}
