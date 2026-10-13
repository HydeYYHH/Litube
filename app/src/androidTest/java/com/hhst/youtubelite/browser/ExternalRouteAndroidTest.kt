@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.browser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import org.junit.Assert.*
import org.junit.Test

class ExternalRouteAndroidTest {
    @Test fun onlyMainActivityOwnsViewLinksWhileExplicitDownloadSharingRemains() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for (url in listOf("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://m.youtube.com/live/aaaaaaaaaaa", "https://youtu.be/aaaaaaaaaaa")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
            val activities = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(listOf(MainActivity::class.java.name), activities.map { it.activityInfo.name })
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertTrue(app.packageManager.queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == DownloadShareActivity::class.java.name })
    }

    @Test fun coldViewAndRepeatedViewUseTheExistingPlaybackActivity() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch()
            lateinit var browser: BrowserViewModel
            fixture.instrumentation.runOnMainSync { browser = ViewModelProvider(fixture.activity)[BrowserViewModel::class.java] }
            fixture.await { browser.uiState.value.url.startsWith(fixture.url) }
            val tab = browser.uiState.value.activeId
            fixture.instrumentation.runOnMainSync { fixture.model.onSeek(12_000) }
            fixture.await { fixture.engine.snapshot.value.positionMs >= 11_000 }
            repeat(2) {
                fixture.instrumentation.runOnMainSync {
                    fixture.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixture.url), fixture.app, MainActivity::class.java))
                }
                fixture.instrumentation.waitForIdleSync()
                fixture.await { browser.uiState.value.activeId == tab && !fixture.activity.isFinishing }
            }
            fixture.await { fixture.engine.snapshot.value.positionMs >= 11_000 && fixture.engine.snapshot.value.isPlaying }
        }
    }
}
