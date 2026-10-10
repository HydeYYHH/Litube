package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SponsorTimelineAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()

    @Test fun everyEnabledCategoryHasItsColorAndHighlightPointsAreVisible() {
        val categoryPrefs = linkedMapOf(
            "sponsor" to PreferenceKeys.SKIP_SPONSORS,
            "selfpromo" to PreferenceKeys.SKIP_SELF_PROMO,
            "interaction" to PreferenceKeys.SKIP_INTERACTION,
            "intro" to PreferenceKeys.SKIP_INTRO,
            "outro" to PreferenceKeys.SKIP_OUTRO,
            "preview" to PreferenceKeys.SKIP_PREVIEW,
            "music_offtopic" to PreferenceKeys.SKIP_MUSIC_OFFTOPIC,
            "filler" to PreferenceKeys.SKIP_FILLER,
            "poi_highlight" to PreferenceKeys.SKIP_POI_HIGHLIGHT,
        )
        val expectedColors = listOf(0xFF00D400, 0xFFFFFF00, 0xFFCC00FF, 0xFF00FFFF,
            0xFF0202ED, 0xFF008FD6, 0xFFFF9900, 0xFF7300FF, 0xFFFF1684).map { it.toInt() }
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val previous = categoryPrefs.values.associateWith(prefs::isEnabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loaded = CountDownLatch(1)
        val videoId = "sponsor0001"
        val payload = Gson().toJson(listOf(mapOf("videoID" to videoId, "segments" to
            categoryPrefs.keys.map { category -> mapOf("category" to category,
                "actionType" to if (category == "poi_highlight") "poi" else "skip",
                "segment" to if (category == "poi_highlight") listOf(50, 50) else listOf(20, 80)) })))
        var requestChecked = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(listOf("skip", "poi"), chain.request().url.queryParameterValues("actionType"))
            assertEquals(categoryPrefs.keys.toList(), Gson().fromJson(
                chain.request().url.queryParameter("categories"), Array<String>::class.java).toList())
            requestChecked = true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            categoryPrefs.values.forEach { prefs.setEnabled(it, true) }
            val manager = SponsorBlockManager(http, prefs, scope)
            manager.onSegmentsLoaded = { if (it.size == categoryPrefs.size) loaded.countDown() }
            manager.load(videoId)
            assertTrue("SponsorBlock fixture did not load", loaded.await(10, TimeUnit.SECONDS))
            assertTrue(requestChecked)
            val segments = manager.currentSegments
            assertEquals(categoryPrefs.keys.toList(), segments.map { it.category })
            compose.setContent {
                AppTheme(darkTheme = true, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(16.dp)) {
                        segments.forEach { segment ->
                            Text(segment.category, color = Color.White)
                            PlayerTimeBar(mutableStateOf(40_000L), mutableStateOf(60_000L),
                                100_000L, listOf(segment), {}, Modifier.height(32.dp).testTag(segment.category))
                        }
                        Text("Highlights at timeline boundaries", color = Color.White)
                        PlayerTimeBar(mutableStateOf(10_000L), mutableStateOf(60_000L), 100_000L,
                            listOf(SponsorBlockManager.Segment(0, 0, "poi_highlight"),
                                SponsorBlockManager.Segment(100_000, 100_000, "poi_highlight")),
                            {}, Modifier.height(32.dp).testTag("boundaries"))
                    }
                }
            }
            compose.waitForIdle()
            segments.forEachIndexed { index, segment ->
                val pixels = compose.onNodeWithTag(segment.category).captureToImage().toPixelMap()
                // Check the actual pixels over played, buffered and unplayed regions.
                val positions = if (segment.category == "poi_highlight") listOf(0.5f) else listOf(0.3f, 0.5f, 0.7f)
                positions.forEach { fraction -> assertEquals(segment.category, expectedColors[index],
                    pixels[(pixels.width * fraction).toInt(), pixels.height / 2].toArgb()) }
            }
            val edges = compose.onNodeWithTag("boundaries").captureToImage().toPixelMap()
            assertEquals(expectedColors.last(), edges[0, edges.height / 2].toArgb())
            assertEquals(expectedColors.last(), edges[edges.width - 1, edges.height / 2].toArgb())
            DeviceEvidence.captureScene("sponsorblock-nine-category-colors")
            DeviceEvidence.writeJson("sponsorblock-colors.json", Gson().toJson(mapOf(
                "requestIncludesSkipAndPoi" to true, "allNineCategoryPixelsMatch" to true,
                "zeroDurationHighlightVisible" to true, "boundaryHighlightsVisible" to true)))
        } finally {
            scope.cancel()
            previous.forEach { (key, value) -> prefs.setEnabled(key, value) }
        }
    }
}
