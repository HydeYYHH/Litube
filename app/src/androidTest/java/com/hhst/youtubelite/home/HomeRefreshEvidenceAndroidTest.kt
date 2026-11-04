package com.hhst.youtubelite.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.HomeContent
import com.hhst.youtubelite.home.domain.HomeUiState
import com.hhst.youtubelite.home.presentation.HomeScreen
import com.hhst.youtubelite.ui.theme.AppAppearance
import com.hhst.youtubelite.ui.theme.AppTheme
import com.hhst.youtubelite.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Saves review evidence for the refresh states; no golden comparison happens here. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class HomeRefreshEvidenceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousAppearance = ThemeMode.System

    @Before fun applyAppearance() {
        previousAppearance = AppAppearance.mode
        compose.runOnUiThread {
            AppAppearance.update(if (args().getString("variant").orEmpty().startsWith("dark")) ThemeMode.Dark else ThemeMode.Light)
        }
    }

    @After fun restoreAppearance() {
        compose.runOnUiThread { AppAppearance.update(previousAppearance) }
    }

    private fun args() = InstrumentationRegistry.getArguments()
    private fun output() = File(compose.activity.getExternalFilesDir(null), "home-refresh-evidence").apply { mkdirs() }

    @Test fun captureRefreshStates() {
        val suffix = "${args().getString("width") ?: "390"}-${args().getString("variant") ?: "light-font1"}"
        val output = output()
        val width = (args().getString("width") ?: "390").toInt()
        val fontScale = (args().getString("fontScale") ?: "1").toFloat()
        val feed = (HomeScenario.Feed.initialContent() as HomeContent.Feed)
        var state by mutableStateOf(HomeUiState(content = feed.copy(refreshing = true, userRefresh = true), signedInPreview = true))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) { HomeScreen(state, {}) }
            }
        }
        compose.onNodeWithTag("home-refresh-indicator").assertExists()
        save("home-refresh-user-$suffix", output)
        compose.onNodeWithTag("home-feed").performTouchInput {
            down(Offset(center.x, 200f)); moveBy(Offset(0f, 300f))
        }
        save("home-pull-held-$suffix", output)
        compose.onNodeWithTag("home-feed").performTouchInput { up() }
        compose.runOnIdle { state = HomeUiState(content = HomeContent.Empty(refreshing = true, userRefresh = true)) }
        compose.onNodeWithTag("home-refresh-indicator").assertExists()
        save("home-empty-refresh-user-$suffix", output)
    }

    @Test fun captureAboutBusyIndicator() {
        val suffix = "${args().getString("width") ?: "390"}-${args().getString("variant") ?: "light-font1"}"
        val width = (args().getString("width") ?: "390").toInt()
        val fontScale = (args().getString("fontScale") ?: "1").toFloat()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AppTheme {
                    Box(Modifier.requiredSize(width.dp, 844.dp).testTag("about-busy")) {
                        com.hhst.youtubelite.ui.about.AboutScreen("Litube", "4.0.0", {}, true, "", {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("about-busy").captureToImage().asAndroidBitmap()
        File(output(), "about-busy-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(name: String, output: File) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("home-screen").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
