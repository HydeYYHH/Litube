package com.hhst.youtubelite

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.ui.components.MediaActionItem
import com.hhst.youtubelite.ui.components.MediaActionsDialog
import com.hhst.youtubelite.ui.components.mediaActions
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.theme.YoutubeAppearance
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaActionsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val appearance = YoutubeAppearance.dark
    @After fun restoreAppearance() { YoutubeAppearance.update(appearance) }

    @Test fun longPressPreservesAllActionsAndCopyTitle() {
        YoutubeAppearance.update(false)
        val title = "A complete video title"
        compose.setContent {
            Box(Modifier.fillMaxSize().mediaActions(MediaActionItem("aqz-KE-bpKQ", title, "Blender", "bunny")) {}) {
                AppText("Video", Modifier.testTag("video"))
            }
        }
        compose.onNodeWithTag("video", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("media-actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close video actions").assertIsDisplayed()
        for (index in 0..5) compose.onNodeWithTag("media-action-$index").performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("media-actions").captureToImage().asAndroidBitmap()
        val action = compose.onNodeWithTag("media-action-5").captureToImage().asAndroidBitmap()
        val pixels = IntArray(action.width * action.height)
        action.getPixels(pixels, 0, action.width, 0, 0, action.width, action.height)
        assertEquals("The action panel must not inherit the default purple accent", 0, pixels.count {
            val r = android.graphics.Color.red(it); val g = android.graphics.Color.green(it); val b = android.graphics.Color.blue(it)
            b > r + 15 && r > g + 15
        })
        val output = File(compose.activity.getExternalFilesDir(null), "action-acceptance").apply { mkdirs() }
        File(output, "actions-light.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("media-action-5").performClick()
        compose.onNodeWithTag("media-actions").assertDoesNotExist()
        assertEquals(title, compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test fun darkPanelAndLargeTextRemainScrollableInAShortWindow() {
        YoutubeAppearance.update(true)
        var open by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                if (open) MediaActionsDialog(MediaActionItem("aqz-KE-bpKQ", "A long title that must not hide any action", "Blender", "bunny")) { open = false }
            }
        }
        for (index in 0..5) compose.onNodeWithTag("media-action-$index").performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("media-actions").captureToImage().asAndroidBitmap()
        assertTrue("Dark surface", android.graphics.Color.red(bitmap.getPixel(12, bitmap.height / 2)) < 80)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("media-actions").assertDoesNotExist()
    }
}
