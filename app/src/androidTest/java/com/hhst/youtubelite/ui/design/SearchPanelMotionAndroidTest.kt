package com.hhst.youtubelite.ui.design

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchPanelMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var originalScale: String? = null
    private var open by mutableStateOf(true)
    private var closes = 0

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString().trim() }
    }

    @Before fun enableMotion() {
        if (InstrumentationRegistry.getArguments().getString("motion_managed_externally") == "1") return
        originalScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 1")
    }

    @After fun restoreMotion() {
        compose.mainClock.autoAdvance = true
        originalScale?.let { original ->
            if (original.isBlank() || original == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $original")
        }
    }

    private fun showPanel() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            if (open) SearchPanel("", emptyList(), {}, {}, { closes++; open = false }, {})
        } }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("search-overlay").assertExists()
    }

    private fun assertExitCompletesOnce() {
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("search-overlay").assertExists()
        compose.runOnIdle {
            assertEquals("The host must retain the panel during its exit", 0, closes)
            assertFalse(compose.activity.isFinishing)
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun repeatedBackButtonClicksFinishOneExitBeforeRemovingThePanel() {
        showPanel()
        compose.onNodeWithTag("search-panel-back").performClick()
        compose.onNodeWithTag("search-panel-back").performClick()
        assertExitCompletesOnce()
    }

    @Test fun systemBackUsesTheSameExitAndConsumesRepeatedBackEvents() {
        showPanel()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertExitCompletesOnce()
    }

    @Test fun inputIsFocusedBeforeTheEntryAnimationFinishes() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            SearchPanel("", emptyList(), {}, {}, {}, {})
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("search-input").assertIsFocused()
    }

    @Test fun backDuringEntryCannotRefocusOrReopenThePanel() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            if (open) SearchPanel("", emptyList(), {}, {}, { closes++; open = false }, {})
        } }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("search-input").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, closes)
            assertFalse(compose.activity.isFinishing)
        }
    }

    @Test fun panelPixelsFadeBeforeTheHostRemovesIt() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 26)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides null) {
                Box(Modifier.fillMaxSize().background(Color.Red).testTag("search-host")) {
                    if (open) SearchPanel("", emptyList(), {}, {}, { open = false }, {})
                }
            }
        } }
        compose.mainClock.advanceTimeBy(240)
        fun sample(): Color {
            val pixels = compose.onNodeWithTag("search-host").captureToImage().toPixelMap()
            return pixels[pixels.width - 2, 24]
        }
        val opaque = sample()
        compose.onNodeWithTag("search-panel-back").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("search-overlay").assertExists()
        val fading = sample()
        assertTrue("The panel must fade during exit, not disappear fully opaque: $opaque -> $fading",
            fading.green < opaque.green && fading.green > 0f)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(Color.Red, sample())
    }
}
