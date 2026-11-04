package com.hhst.youtubelite.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.account.presentation.AccountMenuHeader
import com.hhst.youtubelite.account.presentation.accountMenuItems
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.domain.LibraryContent
import com.hhst.youtubelite.library.domain.LibraryUiState
import com.hhst.youtubelite.library.domain.YouPage
import com.hhst.youtubelite.library.presentation.YouScreen
import com.hhst.youtubelite.settings.domain.SettingsPage
import com.hhst.youtubelite.settings.presentation.SettingsScreen
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; account sheet and settings frames at density 1. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class AccountSettingsScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun accountMenuMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var scale by mutableFloatStateOf(1f)
        val page = YouPage(FixtureLibraryRepository.profile(), FixtureLibraryRepository.historyEntries(),
            FixtureLibraryRepository.collections())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).testTag("account-scene")) {
                    YouScreen(LibraryUiState(signedIn = true, content = LibraryContent.Ready(page)), {})
                    MenuBottomSheet(
                        items = accountMenuItems, onDismiss = {}, onSelect = {}, tag = "account-menu",
                        paneTitle = "Account menu",
                        header = { AccountMenuHeader(page.account) {} },
                    )
                }
            }
        }
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            compose.runOnIdle { width = w; height = h }
            capture("account-menu-$w", "account", output, record, goldens)
        }
        compose.runOnIdle { width = 390; height = 844 }
        val phone = compose.onNodeWithTag("account-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, phone.left, 0.5f)
        assertEquals(390f, phone.width, 0.5f)
        assertEquals(820f, phone.bottom, 0.5f)
        assertEquals(13f + 58f + 4 * 48f, phone.height, 0.5f)
        compose.runOnIdle { width = 1280; height = 800 }
        val wide = compose.onNodeWithTag("account-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(440f, wide.left, 0.5f)
        assertEquals(400f, wide.width, 0.5f)
        assertEquals(776f, wide.bottom, 0.5f)
        compose.runOnIdle { width = 844; height = 390 }
        capture("account-menu-390-landscape", "account", output, record, goldens)
        compose.runOnIdle { width = 390; height = 844; scale = 2f }
        capture("account-menu-390-font2", "account", output, record, goldens)
    }

    @Test fun settingsMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var scale by mutableFloatStateOf(1f)
        var page by mutableStateOf(SettingsPage.Home)
        val preferences = mapOf(
            PreferenceKeys.ENABLE_BACKGROUND_PLAY to true,
            PreferenceKeys.ENABLE_PIP to true,
            PreferenceKeys.REMEMBER_LAST_POSITION to true,
            PreferenceKeys.ENABLE_HIDE_SHORTS to false,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing)
                    .requiredSize(width.dp, height.dp).testTag("settings-scene")) {
                    SettingsScreen(page, preferences, notificationsEnabled = true,
                        onNavigate = { page = it }, onToggle = { _, _ -> }, onAbout = {},
                        onOpenNotificationSettings = {}, onBack = {}, modifier = Modifier)
                }
            }
        }
        for ((w, h) in listOf(320 to 844, 390 to 844, 1280 to 800)) {
            compose.runOnIdle { width = w; height = h; page = SettingsPage.Home }
            capture("settings-home-$w", "settings", output, record, goldens)
        }
        compose.runOnIdle { width = 390; height = 844; page = SettingsPage.Home }
        val row = compose.onNodeWithTag("settings-row-general").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, row.left, 0.5f)
        assertEquals(390f, row.width, 0.5f)
        assertEquals(88f, row.top, 0.5f)
        assertEquals(52f, row.height, 0.5f)
        compose.runOnIdle { width = 1280; height = 800 }
        val wideRow = compose.onNodeWithTag("settings-row-general").fetchSemanticsNode().boundsInRoot
        assertEquals(280f, wideRow.left, 0.5f)
        assertEquals(720f, wideRow.width, 0.5f)
        for (sub in listOf(SettingsPage.General, SettingsPage.Playback, SettingsPage.Notifications)) {
            compose.runOnIdle { width = 390; height = 844; page = sub }
            capture("settings-${sub.argument}-390", "settings", output, record, goldens)
        }
        compose.runOnIdle { width = 844; height = 390; page = SettingsPage.Home }
        capture("settings-home-390-landscape", "settings", output, record, goldens)
        compose.runOnIdle { width = 390; height = 844; scale = 2f; page = SettingsPage.Home }
        capture("settings-home-390-font2", "settings", output, record, goldens)
    }

    private val output: File
        get() = File(compose.activity.getExternalFilesDir(null), "batch-c-acceptance").apply { mkdirs() }
    private val goldens: Set<String>
        get() = setOf("account-menu-390", "account-menu-1280", "account-menu-390-landscape",
            "account-menu-390-font2", "settings-home-320", "settings-home-390", "settings-home-1280",
            "settings-general-390", "settings-playback-390", "settings-notifications-390",
            "settings-home-390-landscape", "settings-home-390-font2")
    private val record: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordBatchCGoldens") == "true"

    private fun capture(name: String, folder: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val tag = if (folder == "account") "account-scene" else "settings-scene"
        val actual = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("$folder/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { i -> listOf(0, 8, 16).any { shift ->
            abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
        } }
        assertTrue("$name changed $changed pixels; inspect the saved screenshot", changed.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
