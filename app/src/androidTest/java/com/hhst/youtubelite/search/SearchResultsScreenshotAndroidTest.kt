package com.hhst.youtubelite.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.NativeChoicePicker
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; matrix: 1600x1200@160, first visit: 1170x2532@480. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class SearchResultsScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun results() = SearchContent.Results(SearchPage(QUERY, FixtureSearchRepository.sampleResults()))

    private fun contentOf(state: String) = when (state) {
        "results" -> results()
        "empty" -> SearchContent.Empty(QUERY)
        "loading" -> SearchContent.Loading
        else -> SearchContent.Failed
    }

    @Test fun stackedLockupMatchesMeasuredAnchors() {
        var width by mutableIntStateOf(390)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        val thumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, thumb.left, 0.5f)
        assertEquals(48f, thumb.top, 0.5f)
        assertEquals(390f, thumb.width, 0.5f)
        assertEquals(218.875f, thumb.height, 0.5f)
        val avatar = compose.onNodeWithTag("search-channel-harbour").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, avatar.left, 0.5f)
        assertEquals(thumb.bottom + 4f + 8f, avatar.top, 0.5f)
        assertEquals(40f, avatar.width, 0.5f)
        assertEquals(40f, avatar.height, 0.5f)
        val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(64f, title.left, 0.5f)
        assertEquals(278f, title.width, 0.5f)
        val menu = compose.onNodeWithContentDescription("More actions for ${FixtureSearchRepository.sampleResults()[0].title}")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(342f, menu.left, 0.5f)
        assertEquals(390f, menu.right, 0.5f)
        assertEquals(48f, menu.width, 0.5f)
        val badge = compose.onNodeWithTag("search-badge-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(thumb.right - 8f, badge.right, 0.5f)
        assertEquals(thumb.bottom - 8f, badge.bottom, 0.5f)
        assertEquals(20f, badge.height, 0.5f)
        // Sampled row pitch: cover + 4px + a 51px details row, then 24px between rows. Compose
        // rounds the sampled sub-pixel boxes up (cover 218.875→219, every 17.5px title line box
        // to 18px), so the two-line row sits 1.125px above the browser value.
        val twoLine = compose.onNodeWithTag("search-result-harbour").fetchSemanticsNode().boundsInRoot
        assertEquals(282.875f, twoLine.height, 1.5f)
        val titleStyle = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleStyle) }
        assertEquals(14.sp, titleStyle.single().layoutInput.style.fontSize)
        assertEquals(17.5.sp, titleStyle.single().layoutInput.style.lineHeight)
        assertEquals(FontWeight.Normal, titleStyle.single().layoutInput.style.fontWeight)
        assertEquals(2, titleStyle.single().lineCount)
        compose.onNodeWithTag("search-results-list").performScrollToIndex(3)
        assertEquals(273.875f, compose.onNodeWithTag("search-result-workshop").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("search-results-list").performScrollToIndex(2)
        assertEquals(287.375f, compose.onNodeWithTag("search-result-meadow").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("search-results-list").performScrollToIndex(0)
        compose.runOnIdle { width = 600 }
        val tabletThumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(12f, tabletThumb.left, 0.5f)
        assertEquals(576f, tabletThumb.width, 0.5f)
        assertEquals(324f, tabletThumb.height, 0.5f)
        val tabletTitle = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(64f, tabletTitle.left, 0.5f)
        assertEquals(484f, tabletTitle.width, 0.5f)
    }

    @Test fun listLockupMatchesMeasuredAnchors() {
        var width by mutableIntStateOf(1280)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 800.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        val item = compose.onNodeWithTag("search-result-harbour").fetchSemanticsNode().boundsInRoot
        assertEquals(220f, item.left, 0.5f)
        assertEquals(840f, item.width, 0.5f)
        assertEquals(112f, item.height, 0.5f)
        val thumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(232f, thumb.left, 0.5f)
        assertEquals(200f, thumb.width, 0.5f)
        assertEquals(112f, thumb.height, 0.5f)
        val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(444f, title.left, 0.5f)
        assertEquals(552f, title.width, 0.5f)
        val menu = compose.onNodeWithContentDescription("More actions for ${FixtureSearchRepository.sampleResults()[0].title}")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(996f, menu.left, 0.5f)
        assertEquals(40f, menu.width, 0.5f)
        val badge = compose.onNodeWithTag("search-badge-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(thumb.right - 5f, badge.right, 0.5f)
        assertEquals(thumb.bottom - 5f, badge.bottom, 0.5f)
        val second = compose.onNodeWithTag("search-thumb-studio").fetchSemanticsNode().boundsInRoot
        assertEquals(thumb.bottom + 12f, second.top, 1f)
        compose.runOnIdle { width = 1600 }
        assertEquals(380f, compose.onNodeWithTag("search-result-harbour").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.runOnIdle { width = 840 }
        assertEquals(0f, compose.onNodeWithTag("search-result-harbour").fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }

    @Test fun emptyAndErrorStatesStayDistinctFromResults() {
        var state by mutableStateOf(SearchUiState(query = "no such video", content = SearchContent.Empty("no such video")))
        val actions = mutableListOf<SearchAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { SearchResultsScreen(state, { actions += it }, {}) }
            }
        }
        compose.onNodeWithTag("search-empty").assertIsDisplayed()
        compose.onNodeWithText("No results found").assertIsDisplayed()
        compose.onNodeWithText("Your search for \"no such video\" did not match any videos.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("search-results-list").assertDoesNotExist()
        compose.runOnIdle { state = SearchUiState(query = QUERY, content = SearchContent.Failed) }
        compose.onNodeWithTag("search-error").assertIsDisplayed()
        compose.onNodeWithText("Something went wrong").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(SearchAction.Retry), actions)
    }

    @Test fun searchChromeKeepsTheMeasuredTopBarGeometry() {
        var width by mutableIntStateOf(390)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    SearchResultsScreen(SearchUiState(query = "open movie", content = results()), {}, {})
                }
            }
        }
        val bar = compose.onNodeWithTag("search-topbar").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bar.height, 0.5f)
        val field = compose.onNodeWithTag("search-field").fetchSemanticsNode().boundsInRoot
        assertEquals(53f, field.left, 0.5f)
        assertEquals(8f, field.top, 0.5f)
        assertEquals(32f, field.height, 0.5f)
        assertEquals(298f, field.right, 0.5f)
        val query = compose.onNodeWithTag("search-query-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(65f, query.left, 0.5f)
        val filters = compose.onNodeWithContentDescription("Search filters").fetchSemanticsNode().boundsInRoot
        assertEquals(field.right - 48f, filters.left, 0.5f)
        assertEquals(48f, filters.height, 0.5f)
        val voice = compose.onNodeWithContentDescription("Search with your voice").fetchSemanticsNode().boundsInRoot
        assertEquals(40f, voice.width, 0.5f)
        assertEquals(40f, voice.height, 0.5f)
        val more = compose.onNodeWithContentDescription("More options").fetchSemanticsNode().boundsInRoot
        assertEquals(390f, more.right, 0.5f)
        assertEquals(48f, more.width, 0.5f)
        val nav = compose.onNodeWithTag("search-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(844f, nav.bottom, 0.5f)
        assertEquals(57f, nav.height, 0.5f)
        SearchDestination.entries.forEach { destination ->
            val tabs = compose.onAllNodesWithTag("search-nav-${destination.name}", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("${destination.name} tab", 1, tabs.size)
            assertTrue("${destination.name} tab width", tabs.single().boundsInRoot.width > 0f)
        }
        compose.runOnIdle { width = 1280 }
        assertEquals(320f, compose.onAllNodesWithTag("search-nav-You", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot.width, 0.5f)
    }

    @Test fun splitWindowBoundsKeepTheResultsUsable() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(400)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(390 to 400, 411 to 500, 600 to 400, 240 to 320)) {
            compose.runOnIdle { width = w; height = h }
            val thumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val nav = compose.onNodeWithTag("search-navigation").fetchSemanticsNode().boundsInRoot
            val expectedThumb = if (SearchResultsLayout.listLockup(w.toFloat(), h.toFloat())) 200f else SearchResultsLayout.stackedThumbnailWidth(w.toFloat(), h.toFloat())
            assertEquals("$w × $h thumb", expectedThumb, thumb.width, 0.5f)
            assertTrue("$w × $h title inside window", title.right <= w.toFloat() + 0.5f)
            assertTrue("$w × $h nav inside window", nav.bottom <= h.toFloat() + 0.5f)
            assertTrue("$w × $h list has room", nav.top > 48f)
            capture("split-$w-$h", output, true, emptySet())
        }
    }

    @Test fun viewportAndStateMatrixMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(SearchUiState(query = QUERY, content = results()))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { SearchResultsScreen(state, {}, {}) }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordSearchGoldens") == "true"
        val goldens = listOf(390, 1280).flatMap { w -> listOf("results", "empty", "loading", "error").map { "$it-$w" } }.toSet()
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)) {
            for (scene in listOf("results", "empty", "loading", "error")) {
                compose.runOnIdle {
                    width = w; height = h; fontScale = 1f
                    state = SearchUiState(query = QUERY, content = contentOf(scene))
                }
                capture("$scene-$w", output, record, goldens)
            }
        }
        for ((w, h) in listOf(840 to 390, 320 to 240)) {
            compose.runOnIdle {
                width = w; height = h; fontScale = if (w < 400) 2f else 1f
                state = SearchUiState(query = QUERY, content = results())
            }
            if (w < 400) compose.onNodeWithTag("search-results-list").performScrollToIndex(5)
            capture("results-$w-landscape${if (w < 400) "-font2" else ""}", output, true, emptySet())
        }
    }

    @Test fun verifiedStatesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(SearchUiState(query = QUERY, content = results()))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { SearchResultsScreen(state, {}, {}) }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordSearchGoldens") == "true"
        val names = listOf(390, 1280).flatMap { w -> listOf("results", "empty", "loading", "error").map { "$it-$w" } }.toSet()
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            for (scene in listOf("results", "empty", "loading", "error")) {
                compose.runOnIdle { width = w; height = h; state = SearchUiState(query = QUERY, content = contentOf(scene)) }
                capture("$scene-$w", output, record, names)
            }
        }
    }

    @Test fun enlargedTextKeepsResultRowsReadable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("search-channel-harbour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search filters").assertIsDisplayed()
        compose.onNodeWithTag("search-nav-You").assertIsDisplayed()
        val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Title must stay inside the phone column", title.right <= 390f)
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        capture("results-390-font2", output, true, emptySet())
    }


    @Test fun loadingTextBarsAreVisibleInBothLayouts() {
        var width by mutableIntStateOf(390)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    SearchResultsScreen(SearchUiState(content = SearchContent.Loading), {}, {})
                }
            }
        }
        for (w in listOf(390, 600, 1280)) {
            compose.runOnIdle { width = w }
            val thumb = compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("skeleton-title-0", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val byline = compose.onNodeWithTag("skeleton-byline-0", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(300f, title.width, 0.5f)
            assertEquals(16f, title.height, 0.5f)
            assertEquals(120f, byline.width, 0.5f)
            assertEquals(title.bottom + 8f, byline.top, 0.5f)
            if (w < 931) {
                assertEquals(thumb.bottom + 12f, title.top, 0.5f)
                assertEquals(12f, title.left, 0.5f)
            } else {
                assertEquals(thumb.right + 12f, title.left, 0.5f)
                assertEquals(thumb.top, title.top, 0.5f)
            }
        }
    }

    @Test fun shortWindowsKeepErrorAndEmptyContentReachableAtLargeFont() {
        var content by mutableStateOf<SearchContent>(SearchContent.Failed)
        val actions = mutableListOf<SearchAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(320.dp, 240.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = content), { actions += it }, {})
                }
            }
        }
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(SearchAction.Retry), actions)
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        capture("error-320-landscape-font2", output, true, emptySet())
        compose.runOnIdle { content = SearchContent.Empty(QUERY) }
        compose.onNodeWithText("Your search for \"open movie\" did not match any videos.")
            .performScrollTo().assertIsDisplayed()
        capture("empty-320-landscape-font2", output, true, emptySet())
    }


    @Test fun landscapeVariantsKeepTheirMeasuredThumbnailAndMenuSizes() {
        var width by mutableIntStateOf(900)
        var height by mutableIntStateOf(844)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(900 to 844, 930 to 820, 700 to 352, 840 to 334, 931 to 820)) {
            compose.runOnIdle { width = w; height = h }
            val thumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithContentDescription("More actions for " + FixtureSearchRepository.sampleResults()[0].title)
                .fetchSemanticsNode().boundsInRoot
            val stacked = w == 900 || w == 930
            assertEquals(if (stacked) 445.5f else 112f, thumb.height, 0.6f)
            assertEquals(if (w >= 931) 40f else 48f, menu.width, 0.5f)
            assertEquals(if (stacked) 716f else if (w == 700) 404f else if (w == 840) 544f else 552f, title.width, 0.5f)
            capture("results-landscape-$w-$h", output, true, emptySet())
        }
    }


    @Test fun portraitInsetsFollowTheNarrowAndTabletBreakpoints() {
        var width by mutableIntStateOf(320)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 855.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results()), {}, {})
                }
            }
        }
        for ((w, inset, thumbHeight, titleX, titleWidth) in listOf(
            listOf(320f, 12f, 179.5f, 76f, 184f),
            listOf(528f, 0f, 296.5f, 64f, 416f),
            listOf(549f, 0f, 308.3125f, 64f, 437f),
            listOf(550f, 12f, 295.875f, 64f, 434f),
        )) {
            compose.runOnIdle { width = w.toInt() }
            val thumb = compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("search-title-harbour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
            capture("portrait-" + w.toInt(), output, true, emptySet())
            assertEquals("Inset at " + w + ", root=" + compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot,
                inset, thumb.left, 0.5f)
            assertEquals(w - inset * 2, thumb.width, 0.5f)
            assertEquals(thumbHeight, thumb.height, 0.6f)
            assertEquals(titleX, title.left, 0.5f)
            assertEquals(titleWidth, title.width, 0.5f)
        }
    }

    @Test fun filterAndCardMenuSurfacesFollowTheMeasuredGeometry() {
        val record = InstrumentationRegistry.getArguments().getString("recordMenuGoldens") == "true"
        val goldens = setOf("filters-390", "filters-1280", "video-menu-390", "video-menu-1280")
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(
            SearchUiState(query = QUERY, content = results(), filtersOpen = true),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { SearchResultsScreen(state, {}, {}) }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }

        val bar = compose.onNodeWithTag("search-filter-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bar.left, 0.5f)
        assertEquals(390f, bar.width, 0.5f)
        assertEquals(75f, bar.height, 0.5f)
        val type = compose.onNodeWithTag("search-filter-type").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("search-filter-date").fetchSemanticsNode().boundsInRoot
        assertEquals(104f, type.width, 0.5f)
        assertEquals(117f, date.width, 0.5f)
        assertEquals(12f, type.left - bar.left, 0.5f)
        assertEquals(12f, type.top - bar.top, 0.5f)
        assertEquals(4f, date.left - type.right, 0.5f)
        assertEquals(87f, compose.onNodeWithTag("search-filter-type-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 0.5f)
        assertEquals(208f, compose.onNodeWithTag("search-filter-date-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 0.5f)
        capture("filters-390", output, record, goldens)

        compose.runOnIdle { width = 1280; height = 800 }
        val wideBar = compose.onNodeWithTag("search-filter-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(220f, wideBar.left, 0.5f)
        assertEquals(840f, wideBar.width, 0.5f)
        assertEquals(75f, wideBar.height, 0.5f)
        capture("filters-1280", output, record, goldens)

        compose.runOnIdle {
            width = 390; height = 844
            state = state.copy(filtersOpen = false, overlay = SearchOverlay.ItemMenu("harbour"))
        }
        val sheet = compose.onNodeWithTag("search-video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(390f, sheet.width, 0.5f)
        assertEquals(0f, sheet.left, 0.5f)
        assertEquals(844f - 24f, sheet.bottom, 0.5f)
        val shareRow = compose.onNodeWithTag("search-menu-Share").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, shareRow.height, 0.5f)
        capture("video-menu-390", output, record, goldens)

        compose.runOnIdle { width = 1280; height = 800 }
        val wideSheet = compose.onNodeWithTag("search-video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(400f, wideSheet.width, 0.5f)
        assertEquals(440f, wideSheet.left, 0.5f)
        assertEquals(800f - 24f, wideSheet.bottom, 0.5f)
        capture("video-menu-1280", output, record, goldens)
    }

    @Test fun largeTextFiltersCanScrollToEveryControlInNarrowWindows() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(240.dp, 320.dp)) {
                    SearchResultsScreen(SearchUiState(query = QUERY, content = results(), filtersOpen = true), {}, {})
                }
            }
        }
        for (tag in listOf("search-filter-type", "search-filter-date")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("$tag-label", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertFalse(layouts.first().isLineEllipsized(0))
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            val option = if (tag.endsWith("type")) "Playlists" else "This year"
            NativeChoicePicker.choose(option)
        }
        val output = File(compose.activity.getExternalFilesDir(null), "search-acceptance").apply { mkdirs() }
        capture("filters-240-font2", output, true, emptySet())
    }

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("search-results").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("search/goldens/$name.png").use(BitmapFactory::decodeStream)
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

    private companion object { const val QUERY = "open movie" }
}
