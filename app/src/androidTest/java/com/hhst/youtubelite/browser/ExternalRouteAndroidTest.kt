@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.browser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import org.junit.Assert.*
import org.junit.Test

class ExternalRouteAndroidTest {
    @Test fun nativeHomeOwnsViewLinksWhileExplicitDownloadSharingRemains() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for (url in listOf("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://m.youtube.com/live/aaaaaaaaaaa", "https://youtu.be/aaaaaaaaaaa")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
            val activities = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(listOf(HomeActivity::class.java.name), activities.map { it.activityInfo.name })
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
            repeat(2) {
                ActivityScenario.launch<WatchActivity>(WatchActivity.intent(fixture.app, fixture.id)).use {
                    fixture.await { fixture.engine.snapshot.value.videoId == fixture.id }
                    fixture.await { fixture.engine.snapshot.value.positionMs >= 11_000 && fixture.engine.snapshot.value.isPlaying }
                }
            }
        }
    }
}
