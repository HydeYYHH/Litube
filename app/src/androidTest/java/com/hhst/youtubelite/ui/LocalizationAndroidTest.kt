package com.hhst.youtubelite.ui

import android.content.res.Configuration
import android.graphics.Paint
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.datasource.AudioTrackChoice
import com.hhst.youtubelite.ui.components.audioTrackLabel
import com.hhst.youtubelite.ui.about.AboutScreen
import com.hhst.youtubelite.ui.theme.AppTheme
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class LocalizationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allCatalogLocalesSelectTheIntendedResourcesOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entries = instrumentation.context.assets.open("localization/catalog.json").bufferedReader().use {
            JSONArray(it.readText())
        }
        val failures = mutableListOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val tag = entry.getString("tag")
            val tags = buildList {
                add(tag)
                val aliases = entry.getJSONArray("aliases")
                for (alias in 0 until aliases.length()) add(aliases.getString(alias))
            }.distinct()
            for (currentTag in tags) {
                val requested = Locale.forLanguageTag(currentTag)
                // API 23 represents Chinese script selections with regional system locales.
                val locale = if (Build.VERSION.SDK_INT < 24 && requested.language == "zh" && requested.country.isEmpty()) {
                    when (requested.script) {
                        "Hant" -> Locale.TRADITIONAL_CHINESE
                        "Hans" -> Locale.SIMPLIFIED_CHINESE
                        else -> requested
                    }
                } else requested
                val configuration = Configuration(compose.activity.resources.configuration).apply {
                    setLocale(locale)
                }
                val context = compose.activity.createConfigurationContext(configuration)
                for ((key, resource) in listOf("download" to R.string.download, "haptic_strength" to R.string.haptic_strength)) {
                    val expected = entry.getString(key)
                    val actual = context.getString(resource)
                    if (actual != expected) failures += "$currentTag/$key: expected <$expected>, got <$actual>"
                }
                val image = context.getString(R.string.gallery_image, 12)
                assertFalse("$tag: unresolved argument", image.contains("%1\$d"))
                assertTrue("$tag: missing number", image.contains(String.format(locale, "%d", 12)))
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun aboutActionsRemainAccessibleInRtlWithLargeFont() {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }
        val context = compose.activity.createConfigurationContext(configuration)
        var exports = 0
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context,
                LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppTheme {
                    AboutScreen("LiTube", "3.0.0-devx", {}, false, "", {}, {}, {}, {}, { exports++ })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.about_export_logs))
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, exports)
    }

    @Test fun systemFontHasGlyphsForTheMajorScripts() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (glyph in listOf("中", "한", "א", "پ", "ক", "क", "અ", "ਅ", "ଅ", "அ", "త", "ಕ",
            "അ", "අ", "ก", "ກ", "က", "ក", "አ", "ქ", "Ա", "Љ", "Ω")) {
            assumeTrue("System font lacks $glyph", paint.hasGlyph(glyph))
        }
    }

    @Test fun audioTypeLabelsUseTheAppLocaleAndPreserveTrackIdentity() {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("he"))
        }
        val choice = AudioTrackChoice("id:en.d", "English · Descriptive", "en", "descriptive")
        val context = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                Text(audioTrackLabel(choice))
            }
        }
        val suffix = context.getString(R.string.audio_description)
        assertFalse("Audio type fell back to English", suffix == "Audio description")
        val expected = Locale.ENGLISH.getDisplayName(Locale.forLanguageTag("he")) + " · " + suffix
        compose.onNodeWithText(expected).assertIsDisplayed()
        assertEquals("id:en.d", choice.key)
    }
}
