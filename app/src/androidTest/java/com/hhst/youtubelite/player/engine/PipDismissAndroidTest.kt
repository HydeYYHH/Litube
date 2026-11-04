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
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.service.PlaybackService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PipDismissAndroidTest {
    /**
     * PiP entry is accepted only from RESUMED; after a rotation the configuration flips before the shell
     * transition resumes the activity, so the wait below is the platform's precondition — earlier entry
     * returns false by design on API 33+ shell transitions. Rejections capture surrounding state first
     * to make the rare refusal diagnosable.
     */
    private fun enterPip(fixture: OfflineWatchFixture, tag: String) {
        fixture.await { fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        var accepted = false
        var lifecycleState: Lifecycle.State? = null
        var pip = false
        var surface = false
        var pinned = -1
        fixture.instrumentation.runOnMainSync {
            lifecycleState = fixture.activity.lifecycle.currentState
            pip = fixture.activity.isInPictureInPictureMode
            surface = fixture.surface() != null
            accepted = fixture.activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
        if (!accepted) {
            pinned = runCatching {
                fixture.instrumentation.uiAutomation.windows.count { it.isInPictureInPictureMode }
            }.getOrDefault(-1)
            val stacks = DeviceEvidence.shell("dumpsys activity activities | grep -iE 'pin|pip'")
            System.err.println("PIP-REJECTED tag=$tag lifecycle=$lifecycleState pip=$pip surface=$surface " +
                "pinnedWindows=$pinned stacks=$stacks")
            DeviceEvidence.captureScene("native-pip-$tag-rejected")
            DeviceEvidence.writeJson("native-pip-$tag-rejected.json",
                """{"tag":"$tag","lifecycle":"$lifecycleState","pip":$pip,"surface":$surface,"pinnedWindows":$pinned,
                   "engine":"${fixture.engine.snapshot.value}","stacks":"${stacks.replace("\"", "'").trim()}"}""")
        }
        assertTrue("$tag: activity(state=$lifecycleState, pip=$pip, surface=$surface, pinnedWindows=$pinned) was refused PiP entry", accepted)
        fixture.await { fixture.activity.isInPictureInPictureMode }
    }

    @Test fun lockingScreenKeepsBackgroundPlayback() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        OfflineWatchFixture("pipLock0001").use { fixture ->
            fixture.prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
            try {
                fixture.launch()
                enterPip(fixture, "lock-first")
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
            fun enter(tag: String) = enterPip(fixture, tag)
            enter("round-$background-first")
            systemPipAction(fixture, "expand_button")
            // Requesting orientation while the exit transition still runs loses the request on shell-transition devices; resume returns only once it settles.
            fixture.await { fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !fixture.activity.isInPictureInPictureMode && fixture.engine.snapshot.value.isPlaying }
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            // The configuration flips mid-transition; resume returns once it settles.
            fixture.await { fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && fixture.engine.snapshot.value.isPlaying }
            fixture.instrumentation.runOnMainSync { fixture.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            fixture.await { fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                fixture.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && fixture.engine.snapshot.value.isPlaying }
            enter("round-$background-rotated")
            systemPipAction(fixture, "dismiss")
            try {
                fixture.await { !fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !fixture.engine.snapshot.value.isPlaying && !fixture.model.uiState.value.visible }
            } catch (failure: AssertionError) {
                val pinned = runCatching {
                    fixture.instrumentation.uiAutomation.windows.count { it.isInPictureInPictureMode }
                }.getOrDefault(-1)
                System.err.println("PIP-DISMISS-STUCK background=$background lifecycle=${fixture.activity.lifecycle.currentState} " +
                    "pip=${fixture.activity.isInPictureInPictureMode} pinnedWindows=$pinned " +
                    DeviceEvidence.shell("dumpsys activity activities | grep -iE 'pin|pip|7104'").lineSequence().take(6).joinToString(" | "))
                DeviceEvidence.captureScene("native-pip-dismiss-stuck")
                throw failure
            }
            val notificationId = PlaybackService::class.java.getDeclaredField("NOTIFICATION_ID").apply { isAccessible = true }.getInt(null)
            fixture.await { fixture.app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == notificationId } }
            assertNull(fixture.engine.snapshot.value.videoId)
            fixture.instrumentation.runOnMainSync {
                assertFalse("Closing PiP must hide the native player", fixture.model.uiState.value.visible)
                assertNotNull(fixture.activity.watchViewModel.uiState.value.content)
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
            fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes).orEmpty() }
            var control: AccessibilityNodeInfo? = null
            var opened = false
            var controls = ""
            var pipWindow = ""
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (control == null && SystemClock.elapsedRealtime() < deadline) {
                val windows = automation.windows
                val windowNodes = windows.mapNotNull { it.root }.flatMap(::nodes)
                controls = windowNodes.filter { it.isClickable }.joinToString { "${it.viewIdResourceName}:${it.contentDescription}" }
                control = windowNodes.firstOrNull {
                    it.isClickable && it.isEnabled && it.isVisibleToUser &&
                        it.viewIdResourceName?.startsWith("com.android.") == true &&
                        it.viewIdResourceName?.substringAfterLast('/') == controlId
                }
                val pinned = windows.firstOrNull { it.isInPictureInPictureMode }
                val root = pinned?.root
                val bounds = Rect().also { pinned?.getBoundsInScreen(it) }
                pipWindow = "bounds=$bounds root=${root?.className} actions=${root?.actionList}"
                if (control == null && !opened && root != null) {
                    // Android's PiP accessibility replacement maps the root's CLICK to
                    // showing the system menu; its bounds can still describe the host
                    // during a transition, so coordinate taps cannot prove the menu
                    // opened, and EXPAND/DISMISS would bypass the real buttons.
                    if (root.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                        opened = root.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                if (control == null) Thread.sleep(50)
            }
            if (control == null) DeviceEvidence.captureScene("native-pip-$controlId-missing")
            assertNotNull("System PiP $controlId control missing (pip=${fixture.activity.isInPictureInPictureMode}, opened=$opened, $pipWindow, controls=$controls)", control)
            assertTrue(control!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
