@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.surface

import android.content.Context
import android.media.AudioManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real chrome and popup routing; no network or playback timing is involved. */
class PlayerControlSizingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()
    private val calls = mutableMapOf<String, Int>()
    private fun actions() = proxyActions("ControlSizingActions") { name, _ ->
        calls[name] = (calls[name] ?: 0) + 1
    }

    private fun orient(orientation: Int) {
        compose.runOnUiThread { compose.activity.requestedOrientation = orientation }
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
    }

    private fun back() = InstrumentationRegistry.getInstrumentation()
        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)

    private fun button(node: SemanticsNodeInteraction, frame: Rect, density: Float, widthDp: Int = 48): Rect {
        node.assertIsDisplayed()
        val rect = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Button is too small to tap reliably: $rect", rect.width >= widthDp * density - 1 && rect.height >= 48 * density - 1)
        assertTrue("Button outside video frame", rect.left >= frame.left - 1 && rect.right <= frame.right + 1 &&
            rect.top >= frame.top - 1 && rect.bottom <= frame.bottom + 1)
        return rect
    }

    @Test fun embeddedControlsRemainReadableAndOverflowActionsStayReachable() {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        var width by mutableIntStateOf(411)
        var fontScale by mutableFloatStateOf(1f)
        var density = 1f
        val callbacks = actions()
        compose.setContent { AppTheme(darkTheme = true, dynamicColor = false) {
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                    PlayerSurface(sample(), remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(5_000_000L) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(emptyList()) },
                        callbacks, {}, {}, managedByHost = true,
                        modifier = Modifier.size(width.dp, (width * 9 / 16).dp).testTag("control-frame"))
                }
            }
        } }
        for ((nextWidth, scale) in listOf(411 to 1f, 320 to 1f, 411 to 1.5f, 320 to 1.5f, 411 to 2f, 320 to 2f)) {
            compose.runOnIdle { width = nextWidth; fontScale = scale }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("1:11:12").assertIsDisplayed()
            compose.onNodeWithText("2:22:24").assertIsDisplayed()
            val more = compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options))
            val full = compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_fullscreen))
            val moreRect = button(more, frame, density, widthDp = 40)
            val fullRect = button(full, frame, density)
            assertTrue("Top and bottom buttons overlap", moreRect.bottom <= fullRect.top)
            DeviceEvidence.captureScene("controls-embedded-$nextWidth-font${(scale * 10).toInt()}")
            val old = calls["onFullscreenToggle"] ?: 0
            full.performTouchInput { click() }
            compose.runOnIdle { assertEquals(old + 1, calls["onFullscreenToggle"]) }

            val quality = compose.onAllNodesWithText("Auto 1080p").fetchSemanticsNodes()
            if (quality.isNotEmpty()) {
                val q = compose.onNodeWithText("Auto 1080p")
                val qRect = button(q, frame, density)
                assertTrue("Quality and fullscreen targets overlap", qRect.right <= fullRect.left + 1)
                q.performTouchInput { click() }
            } else {
                more.performTouchInput { click() }
                compose.onNodeWithText(compose.activity.getString(R.string.player_info_quality))
                    .performScrollTo().performTouchInput { click() }
            }
            val oldQuality = calls["onQuality"] ?: 0
            compose.onNodeWithText("720p").assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(oldQuality + 1, calls["onQuality"]) }
            if (compose.onAllNodesWithText("1.0x").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("1.0x").performTouchInput { click() }
            } else {
                more.performTouchInput { click() }
                compose.onNodeWithText(compose.activity.getString(R.string.info_playback_speed))
                    .performScrollTo().performTouchInput { click() }
            }
            val oldSpeed = calls["onSpeed"] ?: 0
            compose.onNodeWithText("1.5x").performScrollTo().performTouchInput { click() }
            compose.runOnIdle { assertEquals(oldSpeed + 1, calls["onSpeed"]) }
            more.performTouchInput { click() }
            compose.onNodeWithText(compose.activity.getString(R.string.segments))
                .performScrollTo().performTouchInput { click() }
            compose.onNodeWithText(compose.activity.getString(R.string.no_segments)).assertIsDisplayed()
            back()
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).performTouchInput { click() }
        compose.onNodeWithText(compose.activity.getString(R.string.queue)).performScrollTo().performTouchInput { click() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.close)).assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).assertIsDisplayed()
    }

    @Test fun tallNarrowPlayerWrapsControlsWithoutShrinkingTouchTargets() {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        var density = 1f
        compose.setContent { AppTheme(darkTheme = true, dynamicColor = false) {
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, 2f)) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                    PlayerSurface(sample(), remember { mutableStateOf(4_272_000L) },
                        remember { mutableStateOf(5_000_000L) }, remember { mutableStateOf(null) },
                        remember { mutableStateOf(null) }, remember { mutableStateOf(listOf("Readable subtitle")) },
                        actions(), {}, {}, managedByHost = true,
                        modifier = Modifier.size(320.dp, 400.dp).testTag("control-frame"))
                }
            }
        } }
        compose.waitForIdle()
        val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithText("1:11:12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("2:22:24").assertIsDisplayed()
        val speed = button(compose.onNodeWithText("1.0x"), frame, density)
        val quality = button(compose.onNodeWithText("Auto 1080p"), frame, density)
        val full = button(compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_fullscreen)), frame, density)
        assertTrue("Time must have its own row when the controls no longer fit", time.bottom <= speed.top)
        assertTrue("Bottom targets overlap", speed.right <= quality.left + 1 && quality.right <= full.left + 1)
        val subtitle = compose.onNodeWithText("Readable subtitle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Wrapped time row overlaps captions", subtitle.bottom <= time.top)
        DeviceEvidence.captureScene("controls-tall-320-font20")
    }

    @Test fun visualsGrowWithThePlayerSlotWhileTouchTargetsStayAccessible() {
        orient(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        var width by mutableIntStateOf(320)
        var height by mutableIntStateOf(180)
        compose.setContent { AppTheme(darkTheme = true, dynamicColor = false) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerSurface(sample(), remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(emptyList()) }, actions(), {}, {}, managedByHost = true,
                    modifier = Modifier.size(width.dp, height.dp).testTag("control-frame"))
            }
        } }
        compose.waitForIdle()
        val playLabel = compose.activity.getString(R.string.action_play)
        val fullLabel = compose.activity.getString(R.string.action_fullscreen)
        val smallPlay = compose.onNodeWithContentDescription(playLabel).fetchSemanticsNode().boundsInRoot.height
        fun centerSeparation(): Float = compose.onNodeWithContentDescription(playLabel).fetchSemanticsNode().boundsInRoot.center.x -
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_previous)).fetchSemanticsNode().boundsInRoot.center.x
        val smallSeparation = centerSeparation()
        fun assertCentered() {
            val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
            for (label in listOf(playLabel, compose.activity.getString(R.string.action_previous), compose.activity.getString(R.string.action_next))) {
                val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                assertEquals("Center buttons must follow the video center", frame.center.y, bounds.center.y, 1f)
            }
            val subtitles = compose.onNodeWithContentDescription(compose.activity.getString(R.string.subtitles)).fetchSemanticsNode().boundsInRoot
            val more = compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("CC and settings are too far apart", more.center.x - subtitles.center.x <= 41f * density)
        }
        assertCentered()
        val smallIcon = compose.onNodeWithContentDescription(fullLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val smallTitle = compose.onNodeWithText(sample().title).fetchSemanticsNode().boundsInRoot.height
        DeviceEvidence.captureScene("responsive-player-small")
        compose.runOnIdle { width = 700; height = 350 }
        compose.waitForIdle()
        assertCentered()
        assertTrue("Wide players should spread the center controls", centerSeparation() > smallSeparation * 1.2f)
        assertTrue(compose.onNodeWithContentDescription(playLabel).fetchSemanticsNode().boundsInRoot.height > smallPlay)
        assertTrue(compose.onNodeWithContentDescription(fullLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height > smallIcon)
        assertTrue(compose.onNodeWithText(sample().title).fetchSemanticsNode().boundsInRoot.height > smallTitle)
        compose.onNodeWithContentDescription(fullLabel).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls["onFullscreenToggle"]) }
        DeviceEvidence.captureScene("responsive-player-large")
    }

    @Test fun fullscreenControlsRemainSeparatedWithLargeFonts() {
        orient(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        var fontScale by mutableFloatStateOf(1f)
        var density = 1f
        val callbacks = actions()
        val host = PlayerWindowHost(compose.activity,
            compose.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager, {}, { false }, { null })
        compose.setContent { AppTheme(darkTheme = true, dynamicColor = false) {
            PlayerWindowEffects(compose.activity, host, true, 1920, 1080)
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                PlayerSurface(sample().copy(fullscreen = true), remember { mutableStateOf(4_272_000L) },
                    remember { mutableStateOf(5_000_000L) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(emptyList()) },
                    callbacks, {}, {}, modifier = Modifier.fillMaxSize().testTag("control-frame"))
            }
        } }
        compose.waitUntil(10_000) {
            var hidden = false
            compose.runOnUiThread { hidden = statusBarsHidden(compose.activity) }
            hidden
        }
        for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("control-frame").fetchSemanticsNode().boundsInRoot
            val rects = listOf(R.string.queue, R.string.segments, R.string.subtitles, R.string.more_options).map {
                button(compose.onNodeWithContentDescription(compose.activity.getString(it)), frame, density,
                    widthDp = if (it == R.string.subtitles || it == R.string.more_options) 40 else 48)
            }
            rects.zipWithNext().forEach { (a, b) -> assertTrue("Top targets overlap", a.right <= b.left + 1) }
            button(compose.onNodeWithText("Auto 1080p"), frame, density)
            button(compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_fullscreen)), frame, density)
            compose.onNodeWithText("1:11:12").assertIsDisplayed()
            Thread.sleep(200) // SurfaceView and vector redraw follow the platform frame clock.
            compose.waitForIdle()
            DeviceEvidence.captureScene("controls-fullscreen-font${(scale * 10).toInt()}")
        }
    }

    private fun sample() = PlayerUiState(visible = true, title = "Kimi no Na wa - Sparkle", author = "Calmato ASMR",
        durationMs = 8_544_000L, activeQuality = "1080p", videoWidth = 1920, videoHeight = 1080,
        qualities = listOf("1080p", "720p", "480p"))
}
