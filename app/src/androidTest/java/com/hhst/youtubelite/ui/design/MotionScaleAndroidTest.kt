package com.hhst.youtubelite.ui.design

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.launch

/** Verifies duration specs and frame timing independently. */
class MotionScaleAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString().trim() }
    }

    @Test fun motionScaleFollowsTheSystemAnimatorScale() {
        val original = shell("settings get global animator_duration_scale")
        try {
            // A fresh activity per scale: AppTheme reads the setting when it resumes.
            listOf("0" to 0, "0.5" to 300, "1" to 300, "2" to 300).forEach { (scale, expectedMs) ->
                shell("settings put global animator_duration_scale $scale")
                val reported = intArrayOf(-1)
                ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            AppTheme {
                                reported[0] = motionDuration(300, LocalMotionScale.current)
                                Box(Modifier.testTag("motion-probe"))
                            }
                        }
                    }
                    compose.waitUntil(10_000) { reported[0] == expectedMs }
                    compose.onNodeWithTag("motion-probe").assertExists()
                    assertEquals("scale $scale", expectedMs, reported[0])
                }
            }
        } finally {
            if (original.isBlank() || original == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $original")
        }
    }
    @Test fun composeAppliesTheDurationScaleExactlyOnce() = kotlinx.coroutines.runBlocking {
        for (scale in listOf(0f, 0.5f, 1f, 2f)) {
            val clock = androidx.compose.runtime.BroadcastFrameClock()
            val durationScale = object : androidx.compose.ui.MotionDurationScale {
                override val scaleFactor = scale
            }
            var frameMs = 0L
            var finishedAt = -1L
            val animation = launch(clock + durationScale) {
                androidx.compose.animation.core.Animatable(0f).animateTo(1f,
                    androidx.compose.animation.core.tween(motionDuration(300, scale)))
                finishedAt = frameMs
            }
            kotlinx.coroutines.yield()
            while (!animation.isCompleted && frameMs <= 1000L) {
                clock.sendFrame(frameMs * 1_000_000)
                kotlinx.coroutines.yield()
                if (!animation.isCompleted) frameMs += 10
            }
            animation.cancel()
            assertEquals("scale $scale", (300 * scale).toLong(), finishedAt)
        }
    }

}
