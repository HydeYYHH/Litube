@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.QueueOwner
import com.hhst.youtubelite.player.PlayerViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class RemoteResumeAndroidTest {
    @Test fun aNewShortStartsAtZeroWithoutReadingAccountProgress() {
        OfflineWatchFixture("resume00004").use { fixture ->
            val field = fixture.engine.javaClass.getDeclaredField("remoteResumePosition").apply { isAccessible = true }
            val previous = field.get(fixture.engine)
            val calls = AtomicInteger()
            val load: suspend (String) -> Long? = { calls.incrementAndGet(); 30_000L }
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, true)
                    field.set(fixture.engine, load)
                }
                fixture.launch()
                fixture.await { fixture.engine.snapshot.value.positionMs in 30_000..35_000 }
                assertEquals(1, calls.get())
                fixture.instrumentation.runOnMainSync {
                    fixture.engine.stop()
                    org.koin.core.context.GlobalContext.get().get<PlayerViewModel>().apply {
                        onShortsOpened()
                        openShort(fixture.id)
                    }
                }
                fixture.await {
                    val state = fixture.engine.snapshot.value
                    state.firstFrameRendered && state.isPlaying && state.positionMs in 0..5_000
                }
                assertEquals("Shorts must skip the account request, not only its wait", 1, calls.get())
            } finally {
                fixture.instrumentation.runOnMainSync {
                    field.set(fixture.engine, previous)
                    // This fixture hosts a watch surface. End the simulated Shorts host before it closes, so a later watch fixture does not inherit hidden video.
                    fixture.model.onShortsClosed()
                    fixture.engine.setVideoVisible(true)
                }
            }
        }
    }

    @Test fun privatePlaybackDoesNotReadOrOverwriteStoredProgress() {
        OfflineWatchFixture("resume00003").use { fixture ->
            val cacheField = fixture.engine.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            val cache = cacheField.get(fixture.engine) as JsonCache
            val identity = MediaSession.snapshot()
            val key = PlaybackProgress.cacheKey(fixture.id,
                QueueOwner.from(identity.signedIn, identity.accountId, identity.onBehalfOfUser).id)
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, true)
                    fixture.prefs.setEnabled(PreferenceKeys.PRIVATE_BROWSING, true)
                    cache.put(key, ResumePoint(20_000), 60_000)
                }
                assertTrue(MediaSession.privateBrowsing())
                fixture.launch()
                fixture.await { fixture.engine.snapshot.value.positionMs in 0..5_000 }
                fixture.instrumentation.runOnMainSync { fixture.engine.seekTo(30_000) }
                fixture.await { fixture.engine.snapshot.value.positionMs >= 30_000 }
                fixture.instrumentation.runOnMainSync { fixture.engine.pause() }
                assertEquals(20_000L, cache.get(key, ResumePoint::class.java)?.positionMs)
            } finally { cache.invalidate(key) }
        }
    }

    @Test fun accountProgressStartsOnceAndAnExplicitZeroDoesNotReadItAgain() {
        OfflineWatchFixture("resume00001").use { fixture ->
            val field = fixture.engine.javaClass.getDeclaredField("remoteResumePosition").apply { isAccessible = true }
            val previous = field.get(fixture.engine)
            val calls = AtomicInteger()
            val load: suspend (String) -> Long? = { calls.incrementAndGet(); 30_000L }
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, true)
                    field.set(fixture.engine, load)
                }
                fixture.launch()
                fixture.await { fixture.engine.snapshot.value.positionMs in 30_000..35_000 }
                assertEquals(1, calls.get())
                val prepares = fixture.engine.mediaPrepareCount
                fixture.instrumentation.runOnMainSync {
                    fixture.engine.stop()
                    fixture.engine.play(fixture.url + "&t=0")
                }
                fixture.await { fixture.engine.snapshot.value.isPlaying && fixture.engine.snapshot.value.positionMs in 0..5_000 }
                assertEquals(1, calls.get())
                assertEquals(prepares + 1, fixture.engine.mediaPrepareCount)
            } finally { fixture.instrumentation.runOnMainSync { field.set(fixture.engine, previous) } }
        }
    }

    @Test fun aSlowAccountReadIsCancelledAndFallsBackToTheIdentityScopedLocalPosition() {
        OfflineWatchFixture("resume00002").use { fixture ->
            val field = fixture.engine.javaClass.getDeclaredField("remoteResumePosition").apply { isAccessible = true }
            val previous = field.get(fixture.engine)
            val cacheField = fixture.engine.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            val cache = cacheField.get(fixture.engine) as JsonCache
            val identity = MediaSession.snapshot()
            val owner = QueueOwner.from(identity.signedIn, identity.accountId, identity.onBehalfOfUser)
            val key = PlaybackProgress.cacheKey(fixture.id, owner.id)
            val cancelled = AtomicBoolean()
            val load: suspend (String) -> Long? = {
                try { delay(60_000); 50_000L } finally { cancelled.set(true) }
            }
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, true)
                    cache.put(key, ResumePoint(20_000), 60_000)
                    field.set(fixture.engine, load)
                }
                fixture.launch()
                fixture.await { fixture.engine.snapshot.value.positionMs in 20_000..25_000 }
                assertTrue(cancelled.get())
            } finally {
                fixture.instrumentation.runOnMainSync { field.set(fixture.engine, previous); cache.invalidate(key) }
            }
        }
    }
}
