package com.hhst.youtubelite.downloader.android

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.ui.SingleVideoConfirmSheet
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
class DownloadOptionsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun audioQualityKeepsTrackAndCatalog() {
        var loads = 0
        val initial = sampleCatalog()
        val original = initial.formats.first { it.audioOnly }
        val data = initial.copy(formats = listOf(original,
            original.copy(itag = 139, bitrate = 64_000),
            original.copy(itag = 141, bitrate = 256_000, audioTrackId = "ja", audioLocale = "ja",
                audioTrackOriginal = false, audioTrackType = "dubbed"),
            original.copy(itag = 139, bitrate = 96_000, audioTrackId = "ja", audioLocale = "ja",
                audioTrackOriginal = false, audioTrackType = "dubbed")))
        val source = object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String): DownloadCatalog { loads++; return data.copy(videoId = videoId) }
            override suspend fun refresh(videoId: String) = catalog(videoId)
        }
        val harness = DownloadAndroidHarness(source)
        var submitted = false
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                SingleVideoConfirmSheet("abcdefghijk", "Audio quality", null, null, harness.viewModel, {}, { submitted = true })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.download_audio)).performScrollTo().performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.player_quality_auto)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.download_quality)).performClick()
        compose.onNodeWithText("64 kbps").performClick()
        compose.onNodeWithText("64 kbps").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.audio_track)).performClick()
        compose.onNode(hasText("Japanese", substring = true) and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.download_quality)).performClick()
        compose.onNodeWithText("64 kbps").assertDoesNotExist()
        compose.onNodeWithText("128 kbps").assertDoesNotExist()
        compose.onNodeWithText("256 kbps").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.download_quality)).performClick()
        compose.onNode(hasText("96 kbps") and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download)).assertIsEnabled().performClick()
        compose.waitUntil(8_000) { submitted }
        val task = runBlocking { harness.repo.transact { allTasks().single() } }
        assertTrue(task.config.audioOnly)
        assertEquals(96_000, task.config.audioBitrate)
        assertEquals("id:ja", task.config.audioTrack)
        assertEquals(139, task.config.audioItagHint)
        assertEquals(1, loads)
    }

    @Test fun landscapeLargeFontKeepsFooterAndScrollsOptions() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        val h = DownloadAndroidHarness()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme(darkTheme = false, dynamicColor = false) {
                    SingleVideoConfirmSheet("abcdefghijk", "Download options with large text", "Channel", null, h.viewModel, {}, {})
                }
            }
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithText("1080p").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.download_quality)).performScrollTo().performClick()
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.subtitles)).performScrollTo().assertIsDisplayed()
        DeviceEvidence.captureScene("download-landscape-large-font")
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test fun retryAndEveryQualityAudioAndSubtitleSubmitWithoutReload() {
        var loads = 0
        val initial = sampleCatalog()
        val data = initial.copy(formats = initial.formats + initial.formats.first { it.audioOnly }.copy(
            audioTrackId = "ja", audioLocale = "ja", audioTrackOriginal = false, audioTrackType = "dubbed"),
            subtitles = initial.subtitles + initial.subtitles.first().copy(languageCode = "ja", autoGenerated = true))
        val source = object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String): DownloadCatalog {
                loads++
                if (loads == 1) throw IOException("test-network")
                return data.copy(videoId = videoId)
            }
            override suspend fun refresh(videoId: String) = catalog(videoId)
        }
        val h = DownloadAndroidHarness(source)
        var submitted = false
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                SingleVideoConfirmSheet("abcdefghijk", "Options", null, null, h.viewModel, {}, { submitted = true })
            }
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.retry)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("1080p").fetchSemanticsNodes().isNotEmpty() }
        for (quality in listOf("720p", "1080p", "720p")) {
            compose.onNodeWithText(compose.activity.getString(R.string.download_quality)).performClick()
            compose.waitForIdle()
            DeviceEvidence.captureScene("download-quality")
            compose.onNodeWithText(quality).performClick()
            compose.onNodeWithText(quality).assertIsDisplayed()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.audio_track)).performClick()
        compose.onNode(hasText("Japanese", substring = true) and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText("Japanese", substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.subtitles)).performClick()
        compose.waitForIdle()
        DeviceEvidence.captureScene("download-subtitles")
        compose.onNode(hasText("Japanese", substring = true) and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.download_sub_auto), substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download)).assertIsEnabled().performClick()
        compose.waitUntil(8_000) { submitted }
        val task = runBlocking { h.repo.transact { allTasks().single() } }
        assertEquals("720p", task.config.videoQuality)
        assertEquals(22, task.config.videoItagHint)
        assertEquals("id:ja", task.config.audioTrack)
        assertEquals("ja|auto", task.config.subtitleLanguage)
        assertTrue(task.config.includeSubtitle)
        assertEquals(2, loads)
        DeviceEvidence.captureScene("download-options")
    }
}
