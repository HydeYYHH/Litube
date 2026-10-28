package com.hhst.youtubelite

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.ui.theme.AppAppearance
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule

/**
 * The long-press action panel is a pure surface, so every check here runs through the
 * real home host: layout and theming of the opened panel, one execution boundary trip
 * to the clipboard, and scrollability under large system text.
 */
class MediaActionsAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val appearance = AppAppearance.dark
    private val bunnyTitle = "Big Buck Bunny | A short film by the Blender Foundation"
    private val bunnyDescription = "$bunnyTitle, 9:56"

    @After fun restore() {
        AppAppearance.update(appearance)
        runShell("settings put system font_scale 1.0")
    }

    @Test fun longPressPreservesAllActionsWithoutDefaultAccent() {
        AppAppearance.update(false)
        val home = launchHome()
        compose.onNodeWithContentDescription(bunnyDescription, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("media-actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close video actions").assertIsDisplayed()
        for (kind in menuActions()) compose.onNodeWithTag("media-action-${kind.name}").performScrollTo().assertIsDisplayed()
        // Copy title is deliberately absent from the menu; the executor keeps it for link-less items.
        compose.onNodeWithTag("media-action-CopyTitle").assertDoesNotExist()
        val bitmap = compose.onNodeWithTag("media-actions").captureToImage().asAndroidBitmap()
        val action = compose.onNodeWithTag("media-action-${com.hhst.youtubelite.ui.components.MediaActionKind.CopyLink.name}").captureToImage().asAndroidBitmap()
        val pixels = IntArray(action.width * action.height)
        action.getPixels(pixels, 0, action.width, 0, 0, action.width, action.height)
        assertEquals("The action panel must not inherit the default purple accent", 0, pixels.count {
            val r = android.graphics.Color.red(it); val g = android.graphics.Color.green(it); val b = android.graphics.Color.blue(it)
            b > r + 15 && r > g + 15
        })
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "action-acceptance").apply { mkdirs() }
        File(output, "actions-light.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Close video actions").performClick()
        compose.onNodeWithTag("media-actions").assertDoesNotExist()
        home.close()
    }

    @Test fun copyLinkExecutesThroughTheHostBoundaryAndReachesTheClipboard() {
        val home = launchHome()
        compose.onNodeWithContentDescription(bunnyDescription, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("media-actions").assertIsDisplayed()
        compose.onNodeWithTag("media-action-CopyLink", useUnmergedTree = true).performScrollTo().performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "https://youtu.be/aqz-KE-bpKQ" }
        assertEquals("https://youtu.be/aqz-KE-bpKQ", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithTag("media-actions").assertDoesNotExist()
        home.close()
    }

    @Test fun darkPanelAndLargeTextRemainScrollableInAShortWindow() {
        AppAppearance.update(true)
        runShell("settings put system font_scale 2.0")
        val home = launchHome()
        compose.onNodeWithContentDescription(bunnyDescription, useUnmergedTree = true).performTouchInput { longClick() }
        for (kind in menuActions()) compose.onNodeWithTag("media-action-${kind.name}").performScrollTo().assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("media-actions").captureToImage().asAndroidBitmap()
        assertTrue("Dark surface", android.graphics.Color.red(bitmap.getPixel(12, bitmap.height / 2)) < 80)
        compose.onNodeWithContentDescription("Close video actions").performClick()
        compose.onNodeWithTag("media-actions").assertDoesNotExist()
        home.close()
    }

    @Test fun copyTitleDoesNotRequireAVideoLink() {
        val home = launchHome()
        home.onActivity { host ->
            host.mediaActionExecutor.execute(
                com.hhst.youtubelite.ui.components.MediaActionItem("not-a-video-id", "A local title"),
                com.hhst.youtubelite.ui.components.MediaActionKind.CopyTitle)
            val clipboard = host.getSystemService(ClipboardManager::class.java)
            assertEquals("A local title", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        home.close()
    }

    /**
     * The watch screen hosts the same panel on related-rail thumbnails, and its
     * activity is a PlaybackActivity: the executor must resolve there too, or
     * every watch-page panel row would close without running (regression guard).
     */
    @Test fun watchHostRunsPanelActionsThroughItsOwnExecutor() {
        val queue = org.koin.java.KoinJavaComponent.getKoin()
            .get<com.hhst.youtubelite.player.QueueRepository>()
        queue.clear()
        ActivityScenario.launch<com.hhst.youtubelite.watch.WatchActivity>(
            com.hhst.youtubelite.watch.WatchActivity.intent(
                ApplicationProvider.getApplicationContext(), "bunny")
        ).use { watch ->
            watch.onActivity { host ->
                val executor = (host as com.hhst.youtubelite.ui.components.MediaActionHost).mediaActionExecutor
                executor.execute(com.hhst.youtubelite.ui.components.MediaActionItem("dream", "Sintel"),
                    com.hhst.youtubelite.ui.components.MediaActionKind.PlayNext)
            }
        }
        assertEquals("TLkA0RELQ1g", queue.state.value.items.single().videoId)
        assertTrue(queue.state.value.enabled)
        queue.clear()
    }

    /** The menu lists every action except Copy title (review decision; the executor keeps it). */
    private fun menuActions() = com.hhst.youtubelite.ui.components.MediaActionKind.entries
        .filter { it != com.hhst.youtubelite.ui.components.MediaActionKind.CopyTitle }

    private fun launchHome(): ActivityScenario<HomeActivity> =
        ActivityScenario.launch<HomeActivity>(
            Intent(ApplicationProvider.getApplicationContext<Context>(), HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_SCENARIO, HomeScenario.Feed.argument)
        ).also {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(bunnyDescription, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

    private fun runShell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }
}
