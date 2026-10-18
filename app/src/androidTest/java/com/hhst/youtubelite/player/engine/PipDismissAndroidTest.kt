@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.service.PlaybackService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PipDismissAndroidTest {
    @Test fun lockingScreenKeepsBackgroundPlayback() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        OfflineWatchFixture("pipLock0001").use { fixture ->
            fixture.prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
            try {
                fixture.launch()
                fixture.instrumentation.runOnMainSync {
                    assertTrue(fixture.activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()))
                }
                fixture.await { fixture.activity.isInPictureInPictureMode }
                DeviceEvidence.shell("input keyevent 223") // KEYCODE_SLEEP, not a PiP dismissal.
                fixture.await { !fixture.app.getSystemService(PowerManager::class.java).isInteractive &&
                    !fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                fixture.instrumentation.runOnMainSync {
                    assertEquals("Locking must retain the watch session", fixture.id, fixture.engine.snapshot.value.videoId)
                    assertTrue("Background audio must continue", fixture.engine.snapshot.value.isPlaying)
                    assertTrue(fixture.model.uiState.value.visible)
                }
            } finally {
                DeviceEvidence.shell("input keyevent 224")
                DeviceEvidence.shell("wm dismiss-keyguard")
            }
        }
    }

    @Test fun expandAndRotationKeepPlayingButClosingStopsWithEitherBackgroundSetting() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        for (background in listOf(false, true)) OfflineWatchFixture("pipWatch001").use { fixture ->
            fixture.prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, background)
            fixture.launch()
            fun enter() {
                fixture.instrumentation.runOnMainSync {
                    assertTrue(fixture.activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()))
                }
                fixture.await { fixture.activity.isInPictureInPictureMode }
            }
            enter()
            systemPipAction(fixture, "expand_button")
            fixture.await { !fixture.activity.isInPictureInPictureMode && fixture.engine.snapshot.value.isPlaying }
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && fixture.engine.snapshot.value.isPlaying }
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && fixture.engine.snapshot.value.isPlaying }
            enter()
            systemPipAction(fixture, "dismiss")
            fixture.await { !fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !fixture.engine.snapshot.value.isPlaying && !fixture.model.uiState.value.visible }
            val notificationId = PlaybackService::class.java.getDeclaredField("NOTIFICATION_ID").apply { isAccessible = true }.getInt(null)
            fixture.await { fixture.app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == notificationId } }
            assertNull(fixture.engine.snapshot.value.videoId)
            fixture.instrumentation.runOnMainSync {
                val browser = ViewModelProvider(fixture.activity)[BrowserViewModel::class.java]
                assertFalse("Closing PiP must leave a usable page", PageKind.isPlayerSurface(PageKind.of(browser.uiState.value.url)))
            }
        }
    }

    private fun systemPipAction(fixture: OfflineWatchFixture, controlId: String) {
        val automation = fixture.instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            Thread.sleep(500) // Wait for the PiP transition before opening its system menu.
            fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes).orEmpty() }
            var control: AccessibilityNodeInfo? = null
            var opened = false
            var controls = ""
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (control == null && SystemClock.elapsedRealtime() < deadline) {
                if (opened && controlId == "expand_button" && !fixture.activity.isInPictureInPictureMode) return
                val windowNodes = automation.windows.mapNotNull { it.root }.flatMap(::nodes)
                controls = windowNodes.filter { it.isClickable }.joinToString { "${it.viewIdResourceName}:${it.contentDescription}" }
                control = windowNodes.firstOrNull {
                    it.isClickable && it.viewIdResourceName?.startsWith("com.android.") == true &&
                        it.viewIdResourceName?.substringAfterLast('/') == controlId
                }
                if (control == null && !opened) {
                    val bounds = Rect()
                    automation.windows.firstOrNull { it.isInPictureInPictureMode }?.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        DeviceEvidence.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                        opened = true
                    }
                }
                if (control == null) Thread.sleep(50)
            }
            assertNotNull("System PiP $controlId control missing (pip=${fixture.activity.isInPictureInPictureMode}, opened=$opened, controls=$controls)", control)
            assertTrue(control!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
