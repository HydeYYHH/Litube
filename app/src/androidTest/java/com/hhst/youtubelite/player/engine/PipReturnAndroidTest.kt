@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.PhoneWindow
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.player.PlaybackSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PipReturnAndroidTest {
    @Before fun enterPhoneWindow() = PhoneWindow.enter()
    @After fun exitPhoneWindow() = PhoneWindow.exit()

    @Test fun returningToBrowseTaskKeepsOnlySystemPip() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        verifyTaskReturn(autoEnter = false)
    }

    @Test fun returningAfterHomeAutoPipKeepsOnlySystemPip() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        verifyTaskReturn(autoEnter = true)
    }

    private fun verifyTaskReturn(autoEnter: Boolean) {
        OfflineWatchFixture("pipBack0001").use { fixture ->
            ActivityScenario.launch<HomeActivity>(Intent(fixture.app, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_SCENARIO, "empty").putExtra("playback_test", true)).use { home ->
                lateinit var browse: HomeActivity
                home.onActivity { browse = it }
                fixture.launch(source = browse, startSeconds = 0)
                fixture.await { fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED }
                if (autoEnter) {
                    fixture.await { PipAutoEnter.lastRequestedAutoEnter && PipAutoEnter.lastPushError == null }
                    DeviceEvidence.shell("input keyevent KEYCODE_HOME")
                } else {
                    fixture.instrumentation.runOnMainSync {
                        assertTrue(fixture.activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()))
                    }
                }
                fixture.await { fixture.activity.isInPictureInPictureMode && PlaybackSession.inPip.value }
                DeviceEvidence.shell("input keyevent KEYCODE_HOME")
                fixture.await { !browse.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                // AppTask.moveToFront exercises the same task return as selecting the app card in Recents, without launcher-specific coordinates.
                val tasks = fixture.app.getSystemService(ActivityManager::class.java).appTasks
                @Suppress("DEPRECATION")
                val task = tasks.single { it.taskInfo.id == browse.taskId }
                var homeDrawn = false
                val drawListener = ViewTreeObserver.OnDrawListener {
                    if (browse.lifecycle.currentState == Lifecycle.State.RESUMED) homeDrawn = true
                }
                fixture.instrumentation.runOnMainSync {
                    browse.window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
                }
                try {
                    task.moveToFront()
                    fixture.instrumentation.runOnMainSync { browse.window.decorView.invalidate() }
                    // A two-window system transition must not require every Compose root (incl.
                    // the paused PiP host) to idle; require a resumed Home draw and its
                    // measured content before checking the browse tree; absent root cannot pass.
                    fixture.await("Returned Home did not draw its empty browse content") {
                        val roots = composeRoots(browse.window.decorView)
                        val nodes = roots.flatMap { semantics(it.semanticsOwner.rootSemanticsNode) }
                        homeDrawn && browse.lifecycle.currentState == Lifecycle.State.RESUMED &&
                            roots.isNotEmpty() && roots.all { it.isLifecycleInResumedState && !it.hasPendingMeasureOrLayout } &&
                            listOf("home-screen", "home-empty").all { tag ->
                                nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag && !it.boundsInWindow.isEmpty }
                            }
                    }
                    fixture.instrumentation.runOnMainSync {
                        assertTrue(fixture.activity.isInPictureInPictureMode)
                        assertTrue(PlaybackSession.inPip.value)
                        assertFalse("The PiP owner must not also enter in-app mini mode", fixture.model.uiState.value.mini)
                        val nodes = composeRoots(browse.window.decorView).flatMap { semantics(it.semanticsOwner.rootSemanticsNode) }
                        assertEquals("Returned browse page must have no in-app player window", 0,
                            nodes.count { it.config.getOrNull(SemanticsProperties.TestTag) == "player-window" })
                    }
                } finally {
                    fixture.instrumentation.runOnMainSync {
                        browse.window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
                    }
                }
                fixture.await { fixture.surface()?.let(fixture.model::hasRenderedFrame) == true }
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                val position = fixture.engine.snapshot.value.positionMs
                fixture.await { fixture.engine.snapshot.value.isPlaying && fixture.engine.snapshot.value.positionMs > position + 500 }
                DeviceEvidence.captureScene("pip-return-single-window-${if (autoEnter) "auto" else "manual"}")

            }
        }
    }

    private fun composeRoots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(composeRoots(view.getChildAt(index)))
    }

    private fun semantics(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::semantics)
}
