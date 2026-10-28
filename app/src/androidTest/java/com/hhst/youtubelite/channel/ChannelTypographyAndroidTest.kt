package com.hhst.youtubelite.channel

import android.content.res.Configuration
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.hhst.youtubelite.R
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelUiState
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.ui.design.AppTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChannelTypographyAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelHeadingsRemainReadableAcrossScriptsAndFontScales() {
        val samples = listOf(
            "en" to "Creative Studio", "vi" to "Nghệ thuật", "ru" to "Искусство", "el" to "Μουσική",
            "ar" to "الموسيقى", "he" to "מוזיקה", "hi" to "संगीत", "bn" to "সঙ্গীত",
            "ta" to "இசை", "th" to "ดนตรี", "zh-Hans" to "创意工作室", "ja" to "音楽スタジオ", "ko" to "음악 스튜디오",
        )
        var sample by mutableStateOf(samples.first())
        var scale by mutableStateOf(1f)
        val base = FixtureChannelRepository.samplePage()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(compose.activity, R.font.barlow_semicondensed)
        }
        compose.setContent {
            val locale = Locale.forLanguageTag(sample.first)
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
            val context = compose.activity.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context,
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (sample.first in setOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AppTheme {
                    Box(Modifier.requiredSize(390.dp, 844.dp)) {
                        ChannelScreen(ChannelUiState(content = ChannelContent.Ready(base.copy(
                            profile = base.profile.copy(name = sample.second)))), {}, {})
                    }
                }
            }
        }
        for (entry in samples) {
            entry.second.codePoints().filter { !Character.isWhitespace(it) }.forEach {
                assertTrue("${entry.first}: missing glyph U+${it.toString(16)}", paint.hasGlyph(String(Character.toChars(it))))
            }
            for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle { sample = entry; scale = fontScale }
                val node = compose.onNodeWithTag("channel-name").assertIsDisplayed().fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
                val layout = layouts.single()
                assertEquals(entry.second, layout.layoutInput.text.text)
                assertFalse("${entry.first} at $fontScale: clipped heading", layout.didOverflowHeight)
                assertFalse("${entry.first} at $fontScale: truncated heading", layout.isLineEllipsized(layout.lineCount - 1))
            }
        }
    }
}
