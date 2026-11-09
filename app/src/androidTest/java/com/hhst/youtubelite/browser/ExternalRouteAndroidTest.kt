@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.browser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.navigation.ContentLinkActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import org.junit.Assert.*
import org.junit.Test

class ExternalRouteAndroidTest {
    @Test fun nativeRouterOwnsViewLinksWhileExplicitDownloadSharingRemains() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for (url in listOf("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://m.youtube.com/live/aaaaaaaaaaa", "https://youtu.be/aaaaaaaaaaa")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
            val activities = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(listOf(ContentLinkActivity::class.java.name), activities.map { it.activityInfo.name })
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertTrue(app.packageManager.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == DownloadShareActivity::class.java.name })
    }

    @Test fun repeatedWatchEntryKeepsThePlayingVideoAndPosition() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync { fixture.model.onSeek(12_000) }
            fixture.await { fixture.engine.snapshot.value.positionMs >= 11_000 }
            val prepares = fixture.engine.mediaPrepareCount
            repeat(2) {
                // Re-enter from the live host; another ActivityScenario would clear its
                // task and close a playing PiP rather than exercise navigation.
                fixture.launch(source = fixture.activity)
                fixture.await { fixture.engine.snapshot.value.videoId == fixture.id }
                fixture.await { fixture.engine.snapshot.value.positionMs >= 11_000 && fixture.engine.snapshot.value.isPlaying }
                assertEquals("Same-video entry must not prepare playback again", prepares, fixture.engine.mediaPrepareCount)
            }
        }
    }
}
