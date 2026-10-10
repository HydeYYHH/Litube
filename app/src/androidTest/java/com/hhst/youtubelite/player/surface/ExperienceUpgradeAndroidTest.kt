package com.hhst.youtubelite.player.surface

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import com.hhst.youtubelite.R
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.gallery.GalleryImages
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.engine.PlaybackDiagnostics
import com.hhst.youtubelite.ui.theme.AppTheme
import com.tencent.mmkv.MMKV
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExperienceUpgradeAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun miniOnlyClosesOnReleaseInsideTargetAndCancellationKeepsIt() {
        val store = MiniPlayerStore(MMKV.mmkvWithID("upgrade-close-target")!!.apply { clearAll() })
        var handle: MiniPlayerHandle? = null
        var density = 1f
        var closed = 0
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, true, {},
                modifier = Modifier.testTag("root"), onDismiss = { closed++ }) {
                handle = LocalMiniPlayerHandle.current
                Box(Modifier.fillMaxSize().testTag("window"))
            }
        } }
        compose.waitForIdle()
        fun enter() {
            val root = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
            val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                handle!!.begin()
                handle!!.dragFromStart(root.center.x-window.center.x, root.bottom-52*density-window.center.y)
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.close_player)).assertIsDisplayed()
        }
        compose.runOnIdle { handle!!.begin(); handle!!.dragFromStart(0f, 5*density); handle!!.release() }
        compose.waitForIdle(); assertEquals(0, closed)
        enter()
        compose.runOnIdle { handle!!.dragFromStart(-400*density, 0f); handle!!.release() }
        compose.waitForIdle(); assertEquals(0, closed)
        enter(); compose.runOnIdle { handle!!.cancel() }; compose.waitForIdle(); assertEquals(0, closed)
        enter(); compose.runOnIdle { handle!!.pinchScale(1.2f); handle!!.end(true) }; compose.waitForIdle(); assertEquals(0, closed)
        enter(); compose.runOnIdle { handle!!.release(); handle!!.release() }; compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun miniTouchShortSwipeAndFastOutsideFlingDoNotClose() {
        var closed = 0
        val store = MiniPlayerStore(MMKV.mmkvWithID("upgrade-close-touch")!!.apply { clearAll() })
        compose.setContent { AppTheme {
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, true, {}, onDismiss = { closed++ }) {
                MiniPlayerChrome(PlayerUiState(mini = true, controlsVisible = false), {}, {}, {}, {}, {}, {}, Modifier.testTag("chrome"))
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("chrome").performTouchInput { swipe(center, center + Offset(0f, 30f), 200) }
        compose.waitForIdle(); assertEquals(0, closed)
        compose.onNodeWithTag("chrome").performTouchInput { swipe(center, center + Offset(0f, 250f), 50) }
        compose.waitForIdle(); assertEquals(0, closed)
    }

    @Test fun infoUpdatesRealDiagnosticFieldsAndCopiesAll() {
        var state by mutableStateOf(PlayerUiState(diagnostics = PlaybackDiagnostics(nominalFps = 30f, renderedFps = 29.8f, videoDecoder = "c2.decoder", videoCodec = "avc1", droppedFrames = 12)))
        compose.setContent { AppTheme { InfoDialog(state, {}, {}) } }
        compose.onNodeWithText("c2.decoder").assertExists()
        compose.onNodeWithText("29.8 fps").assertExists()
        compose.runOnIdle { state = state.copy(diagnostics = state.diagnostics.copy(renderedFps = 24.1f)) }
        compose.onNodeWithText("24.1 fps").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.copy_all)).performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertTrue(clipboard.primaryClip?.getItemAt(0)?.text.toString().contains("c2.decoder"))
        }
    }

    @Test fun exportedArchiveRedactsCredentialsAndOpensThroughFileProvider() {
        AppLog.event(AppLog.Category.PLAYER, "upgrade_export_test", mapOf("error" to "token=secret https://host/video?sig=secret"))
        val file = AppLog.export(compose.activity)
        ZipFile(file).use { zip ->
            for (name in listOf("summary.md", "timeline.jsonl", "context.json", "manifest.json")) assertNotNull(zip.getEntry(name))
            zip.entries().asSequence().forEach { entry ->
                assertFalse(zip.getInputStream(entry).bufferedReader().readText().contains("secret"))
            }
        }
        val uri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.download.fileprovider", file)
        compose.activity.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
    }

    @Test fun galleryDecodesBoundedImageAndUsesActualFormat() {
        val context = compose.activity
        val url = "https://yt3.ggpht.com/diagnostic-test.png"
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "gallery").apply { mkdirs() }, hash)
        val original = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 85, it) }; original.recycle()
        val image = GalleryImages.load(context, url)
        assertEquals("image/jpeg", image.mime); assertEquals("jpg", image.extension)
        assertTrue(image.bitmap.width <= 2048); image.bitmap.recycle(); file.delete()
        assertFalse(GalleryImages.allowed("https://evil.com/image.png"))
    }
}
