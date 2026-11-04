package com.hhst.youtubelite.watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.ui.theme.AppAppearance
import com.hhst.youtubelite.ui.theme.ThemeMode
import com.hhst.youtubelite.watch.data.WatchScenario
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class WatchWindowAppearanceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()


    private fun backgroundColor(decor: View): Int? {
        val background = decor.background ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        val bounds = Rect(background.bounds)
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            background.setBounds(0, 0, decor.width, decor.height)
            val canvas = Canvas(pixel)
            canvas.translate(-(decor.width / 2).toFloat(), -(decor.height / 2).toFloat())
            background.draw(canvas)
            pixel.getPixel(0, 0)
        } finally {
            background.bounds = bounds
            pixel.recycle()
        }
    }

    @Test fun windowFollowsAppAppearanceAndStaysBlackBehindFullscreenResizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = AppAppearance.mode
        val player = GlobalContext.get().get<PlayerViewModel>()
        instrumentation.runOnMainSync {
            player.onMiniClose()
            AppAppearance.update(ThemeMode.Dark)
        }
        try {
            ActivityScenario.launch<WatchActivity>(
                WatchActivity.intent(instrumentation.targetContext, "bunny", WatchScenario.Loading),
            ).use { host ->
                @Suppress("DEPRECATION")
                fun awaitAppearance(expected: Int, lightIcons: Boolean) {
                    var observed = "No window sample"
                    try {
                        compose.waitUntil(5_000) {
                            var matches = false
                            host.onActivity {
                                val decor = it.window.decorView
                                val background = backgroundColor(decor)
                                val navigation = it.window.navigationBarColor
                                val bars = WindowInsetsControllerCompat(it.window, decor)
                                // Android 15+ enforces transparent bars for target 35+; the sampled window supplies their backing color.
                                val expectedNavigation = if (Build.VERSION.SDK_INT >= 35 &&
                                    it.applicationInfo.targetSdkVersion >= 35) Color.TRANSPARENT else expected
                                observed = "background=${decor.background?.javaClass?.name}/" +
                                    "${background?.toUInt()?.toString(16)}, navigation=${navigation.toUInt().toString(16)}, " +
                                    "lightNavigation=${bars.isAppearanceLightNavigationBars}, " +
                                    "lightStatus=${bars.isAppearanceLightStatusBars}, theme=${AppAppearance.mode}, " +
                                    "fullscreen=${player.uiState.value.fullscreen}, visible=${player.uiState.value.visible}"
                                matches = background == expected && navigation == expectedNavigation &&
                                    bars.isAppearanceLightNavigationBars != lightIcons &&
                                    bars.isAppearanceLightStatusBars != lightIcons
                            }
                            matches
                        }
                    } catch (failure: ComposeTimeoutException) {
                        throw AssertionError("Expected background=${expected.toUInt().toString(16)}, " +
                            "lightIcons=$lightIcons; $observed", failure)
                    }
                }

                awaitAppearance(Color.BLACK, lightIcons = true)
                host.onActivity {
                    val preview = it.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowDisablePreview))
                    try {
                        assertTrue("Watch must retain the source window instead of showing a theme-only preview",
                            preview.getBoolean(0, false))
                    } finally { preview.recycle() }
                    AppAppearance.update(ThemeMode.Light)
                }
                awaitAppearance(Color.WHITE, lightIcons = false)
                host.onActivity { player.setFullscreen(true) }
                awaitAppearance(Color.BLACK, lightIcons = true)
                // Exercise the real native onResume/onPostResume path that previously restored a white bar over fullscreen playback.
                host.moveToState(Lifecycle.State.STARTED)
                host.moveToState(Lifecycle.State.RESUMED)
                awaitAppearance(Color.BLACK, lightIcons = true)
                host.onActivity { AppAppearance.update(ThemeMode.Dark) }
                awaitAppearance(Color.BLACK, lightIcons = true)
                host.onActivity { AppAppearance.update(ThemeMode.Light) }
                awaitAppearance(Color.BLACK, lightIcons = true)
                host.onActivity { player.setFullscreen(false) }
                awaitAppearance(Color.WHITE, lightIcons = false)
            }
        } finally {
            instrumentation.runOnMainSync {
                player.onMiniClose()
                AppAppearance.update(original)
            }
        }
    }
}
