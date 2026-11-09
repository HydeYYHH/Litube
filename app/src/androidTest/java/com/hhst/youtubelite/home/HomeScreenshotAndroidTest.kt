package com.hhst.youtubelite.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; matrix: 1600x1200@160, first visit: 1170x2532@480. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class HomeScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingAnchorsMatchMeasuredGeometry() {
        var width by mutableIntStateOf(390)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) { HomeScreen(HomeUiState(content = HomeContent.Loading), {}) }
            }
        }
        val phone = compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, phone.left, 0.5f)
        assertEquals(98f, phone.top, 0.5f)
        assertEquals(390f, phone.width, 0.5f)
        assertEquals(219.375f, phone.height, 0.6f)
        compose.runOnIdle { width = 1280 }
        val wide = compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("skeleton-thumb-2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f, wide.left, 0.5f)
        assertEquals(110f, wide.top, 0.5f)
        assertEquals(404.917f, wide.width, 0.6f)
        assertEquals(857.833f, third.left, 0.6f)
    }

    @Test fun shortsAndPostsMatchMeasuredLayout() {
        val page = (HomeScenario.Feed.initialContent() as HomeContent.Feed).page
        val shelf = page.items.filterIsInstance<FeedItem.Shorts>().single()
        val post = page.items.filterIsInstance<FeedItem.Post>().single()
        var width by mutableIntStateOf(390)
        var state by mutableStateOf(HomeUiState(content = HomeContent.Feed(HomePage(listOf(shelf)))))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) { HomeScreen(state, {}) }
            }
        }
        fun bounds(id: Int) = compose.onNodeWithTag("short-short-$id").fetchSemanticsNode().boundsInRoot
        val first = bounds(1)
        assertEquals(16f, first.left, 0.5f)
        assertEquals(166f, first.top, 0.5f)
        assertEquals(175f, first.width, 0.5f)
        assertEquals(262.5f, first.height, 0.6f)
        assertEquals(first.bottom + 8f, bounds(3).top, 0.5f)
        compose.onNodeWithTag("short-short-5").assertDoesNotExist()
        for (w in listOf(599, 600, 840, 1280, 1600)) {
            compose.runOnIdle { width = w }
            val columns = HomeLayout.shortsColumns(w.toFloat())
            val gap = if (w < 600) 8 else 16
            val cardWidth = (w - 32f - gap * (columns - 1)) / columns
            assertEquals(cardWidth, bounds(1).width, 1f)
            assertEquals(bounds(1).top, bounds(2).top, 0.5f)
            if (w >= 600) assertEquals(bounds(1).top, bounds(columns).top, 0.5f)
        }
        for (w in listOf(390, 1280)) {
            compose.runOnIdle { width = w; state = HomeUiState(content = HomeContent.Feed(HomePage(listOf(post)))) }
            val p = compose.onNodeWithTag("community-post").fetchSemanticsNode().boundsInRoot
            assertEquals(0f, p.left, 0.5f)
            assertEquals(w.toFloat(), p.width, 0.5f)
        }
    }

    @Test fun contentMenusMatchMeasuredActions() {
        var state by mutableStateOf(HomeUiState())
        val selected = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { HomeScreen(state, { selected += it }) }
            }
        }
        for (kind in listOf(FeedMenuKind.Short, FeedMenuKind.Shorts, FeedMenuKind.Post, FeedMenuKind.Mix)) {
            compose.runOnIdle { state = HomeUiState(overlay = HomeOverlay.Menu("fixture", kind)) }
            kind.actions.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
            compose.onNodeWithText(FeedMenuAction.WatchLater.label).assertDoesNotExist()
            if (kind == FeedMenuKind.Mix) {
                val box = compose.onNodeWithTag("mix-menu").fetchSemanticsNode().boundsInRoot
                assertEquals(256f, box.width, 0.5f)
                assertEquals(222f, box.height, 0.5f)
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(HomeAction.CloseOverlay, selected.last())
            } else {
                val box = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
                assertEquals(13f + kind.actions.size * 48f, box.height, 0.5f)
                compose.onNodeWithText(kind.actions.first().label).performClick()
                assertEquals(HomeAction.MenuSelected(kind.actions.first()), selected.last())
            }
        }
    }

    @Test fun contentMenuVariantsMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(HomeUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val names = listOf(390, 1280).flatMap { w -> listOf("short", "shorts", "post", "mix").map { "menu-$it-$w" } }.toSet()
        val record = InstrumentationRegistry.getArguments().getString("recordHomeGoldens") == "true"
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            for (kind in listOf(FeedMenuKind.Short, FeedMenuKind.Shorts, FeedMenuKind.Post, FeedMenuKind.Mix)) {
                compose.runOnIdle { width = w; height = h; state = HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("fixture", kind)) }
                capture("menu-${kind.name.lowercase()}-$w", output, record, names)
            }
        }
        for (kind in listOf(FeedMenuKind.Short, FeedMenuKind.Shorts, FeedMenuKind.Post, FeedMenuKind.Mix)) {
            compose.runOnIdle { width = 320; height = 240; fontScale = 2f; state = HomeUiState(overlay = HomeOverlay.Menu("fixture", kind)) }
            compose.onNodeWithText(kind.actions.last().label).performScrollTo().assertIsDisplayed()
            if (kind == FeedMenuKind.Mix) compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            capture("menu-${kind.name.lowercase()}-320-landscape-font2", output, true, emptySet())
        }
    }

    @Test fun failedHomeCanRetryWithoutBeingAnEmptyFeed() {
        var state by mutableStateOf(HomeUiState(content = HomeContent.Failed))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(320.dp, 240.dp)) {
                    HomeScreen(state, { if (it == HomeAction.Refresh) state = state.copy(content = HomeContent.Loading) })
                }
            }
        }
        compose.onNodeWithTag("home-error").assertIsDisplayed()
        compose.onNodeWithTag("empty-message").assertDoesNotExist()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.onNodeWithTag("home-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("home-error").assertDoesNotExist()
    }

    @Test fun loadingIndicatorChangesOverTime() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    HomeScreen(HomeUiState(content = HomeContent.Feed(HomePage(emptyList()), append = AppendState.Loading)), {})
                }
            }
            compose.mainClock.advanceTimeByFrame()
            val before = compose.onNodeWithContentDescription("Loading").captureToImage().asAndroidBitmap()
            assertEquals(32, before.width)
            assertEquals(32, before.height)
            assertEquals(0xFF666666.toInt(), before.getPixel(16, 1))
            assertEquals(0xFFEEEEEE.toInt(), before.getPixel(16, 30))
            compose.mainClock.advanceTimeBy(400)
            val after = compose.onNodeWithContentDescription("Loading").captureToImage().asAndroidBitmap()
            assertFalse("Loading indicator must rotate", before.sameAs(after))
            compose.mainClock.advanceTimeBy(400)
            val cycle = compose.onNodeWithContentDescription("Loading").captureToImage().asAndroidBitmap()
            val beforePixels = IntArray(32 * 32)
            val cyclePixels = IntArray(beforePixels.size)
            before.getPixels(beforePixels, 0, 32, 0, 0, 32, 32)
            cycle.getPixels(cyclePixels, 0, 32, 0, 0, 32, 32)
            assertTrue("Loading period must be 800ms", beforePixels.indices.all { i ->
                listOf(0, 8, 16).all { shift -> abs((beforePixels[i] shr shift and 255) - (cyclePixels[i] shr shift and 255)) <= 2 }
            })
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun feedAnchorsMatchMeasuredGeometry() {
        var width by mutableIntStateOf(390)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true), {})
                }
            }
        }
        val explore = compose.onNodeWithContentDescription("Explore").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, explore.left, 0.5f)
        assertEquals(56f, explore.top, 0.5f)
        assertEquals(48f, explore.width, 0.5f)
        assertEquals(32f, explore.height, 0.5f)
        val all = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        assertEquals(88f, all.left, 0.5f)
        assertEquals(48f, all.width, 0.5f)
        assertEquals(148f, compose.onNodeWithText("Music").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        val title = "Big Buck Bunny | A short film by the Blender Foundation"
        fun textWidth(): Int {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().size.width
        }
        val phoneTitle = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertEquals(56f, phoneTitle.left, 0.5f)
        assertEquals(322f, phoneTitle.width, 0.5f)
        assertEquals(298, textWidth())
        val menu = compose.onNodeWithContentDescription("More actions for $title").fetchSemanticsNode().boundsInRoot
        assertEquals(348f, menu.left, 0.5f)
        assertEquals(40f, menu.width, 0.5f)
        compose.runOnIdle { width = 1280 }
        val gridTitle = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertEquals(60f, gridTitle.left, 0.5f)
        assertEquals(361.333f, gridTitle.width, 1f)
        assertEquals(337.333f, textWidth().toFloat(), 1f)
        compose.runOnIdle { width = 390 }
        val restoredTitle = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertEquals(56f, restoredTitle.left, 0.5f)
        assertEquals(322f, restoredTitle.width, 0.5f)
        assertEquals(298, textWidth())
    }

    @Test fun feedHeaderFollowsScrollDirection() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true), {})
                }
            }
        }
        compose.onNodeWithTag("home-topbar").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performTouchInput { swipeUp() }
        compose.onNodeWithTag("home-topbar").assertIsNotDisplayed()
        compose.onNodeWithTag("home-chips").assertIsNotDisplayed()
        compose.onNodeWithTag("home-feed").performTouchInput { swipeDown() }
        compose.onNodeWithTag("home-topbar").assertIsDisplayed()
        compose.onNodeWithTag("home-chips").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").performTouchInput { swipeUp() }
        compose.onNodeWithTag("home-topbar").assertIsNotDisplayed()
    }

    @Test fun postMatchesMeasuredGeometryAndImagesScroll() {
        val feed = HomeScenario.Feed.initialContent() as HomeContent.Feed
        val post = feed.page.items.filterIsInstance<FeedItem.Post>().single()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(HomeUiState(content = feed, signedInPreview = true), {})
                }
            }
        }
        compose.onNodeWithTag("home-feed").performScrollToIndex(feed.page.items.indexOf(post))
        val bounds = compose.onNodeWithTag("community-post").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNode(hasContentDescription("Go to channel ${post.author}") and hasAnyAncestor(hasTestTag("community-post")))
            .fetchSemanticsNode().boundsInRoot
        assertEquals(12f, avatar.left, 0.5f)
        assertEquals(bounds.top + 12, avatar.top, 0.5f)
        assertEquals(40f, avatar.width, 0.5f)
        assertEquals(346f, compose.onNodeWithContentDescription("Post actions").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        val body = compose.onNodeWithText(post.text)
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(22.sp, layouts.single().layoutInput.style.lineHeight)
        val image = compose.onNodeWithTag("post-image-post-0").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, image.left, 0.5f)
        assertEquals(342f, image.width, 0.5f)
        assertEquals(342f, image.height, 0.5f)
        assertEquals(0f, compose.onNodeWithContentDescription("Like post").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.onNodeWithTag("post-images-post").performScrollToIndex(1)
        compose.onNodeWithText("2/3").assertIsDisplayed()
    }

    @Test fun firstVisitMatchesLayoutReference() {
        // The 390x844 dp @ 3x reference viewport needs the 1170x2532 @ 480 window;
        // pin it so the test also holds inside the 1600x1200 matrix suite.
        com.hhst.youtubelite.core.PhoneWindow.enter()
        try {
        firstVisitMatchesLayoutReferenceBody()
        } finally {
            com.hhst.youtubelite.core.PhoneWindow.exit()
        }
    }

    private fun firstVisitMatchesLayoutReferenceBody() {
        // Match the captured mobile viewport, including its 24 CSS px bottom safe area.
        val dpr = 3f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(dpr, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp).background(Color.White).testTag("reference-viewport")) {
                    HomeScreen(HomeUiState(), {}, Modifier.padding(bottom = 24.dp))
                }
            }
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        compose.onNodeWithTag("reference-viewport").captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "first-visit-390-final.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val edgeLevels = buildSet {
                for (y in 36 until 108) for (x in 1062 until 1134) {
                    val gray = android.graphics.Color.red(bitmap.getPixel(x, y))
                    if (gray in 16..254) add(gray)
                }
            }
            assertTrue("Icon edges must be anti-aliased", edgeLevels.size > 32)
        }
        val bounds = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, bounds.left / dpr, 1f)
        assertEquals(267f, bounds.top / dpr, 1f)
        assertEquals(358f, bounds.width / dpr, 1f)
        assertEquals(120f, bounds.height / dpr, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Try searching to get started").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val title = layouts.single()
        assertEquals(24.sp, title.layoutInput.style.fontSize)
        assertEquals(32.sp, title.layoutInput.style.lineHeight)
        assertEquals(FontWeight.Black, title.layoutInput.style.fontWeight)
        assertEquals(298.583f, (title.getLineRight(0) - title.getLineLeft(0)) / dpr, 1.5f)
    }

    @Test fun viewportAndStateMatrixMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(HomeUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val sizes = listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)
        val record = InstrumentationRegistry.getArguments().getString("recordHomeGoldens") == "true"
        val goldens = listOf(390, 1280).flatMap { w -> listOf("empty", "feed", "loading", "error", "feed-shorts", "feed-post-top", "feed-post").map { "$it-$w" } }.toSet() + setOf("search-390", "menu-390")
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        for ((w, h) in sizes) {
            for (scene in HomeScenario.entries) {
                compose.runOnIdle {
                    width = w; height = h; fontScale = 1f
                    state = HomeUiState(content = scene.initialContent(), signedInPreview = scene != HomeScenario.Empty)
                }
                resetFeedScroll()
                val feed = scene.initialContent() as? HomeContent.Feed
                when (scene) {
                    HomeScenario.PaginationLoading, HomeScenario.PaginationError -> {
                        compose.onNodeWithTag("home-feed").performScrollToIndex(feed!!.page.items.size)
                        if (scene == HomeScenario.PaginationLoading) {
                            compose.onNode(hasContentDescription("Loading")).assertIsDisplayed()
                        } else {
                            compose.onNodeWithText("Something went wrong. Tap to retry.").assertIsDisplayed()
                        }
                    }
                    else -> Unit
                }
                capture("${scene.argument}-$w", output, record, goldens)
                if (scene == HomeScenario.Feed) {
                    val shelfIndex = feed!!.page.items.indexOfFirst { it is FeedItem.Shorts }
                    compose.onNodeWithTag("home-feed").performScrollToIndex(shelfIndex)
                    hideFeedHeader()
                    compose.onNodeWithTag("shorts-shelf").assertIsDisplayed()
                    capture("feed-shorts-$w", output, record, goldens)
                    val postIndex = feed.page.items.indexOfFirst { it is FeedItem.Post }
                    compose.onNodeWithTag("home-feed").performScrollToIndex(postIndex)
                    hideFeedHeader()
                    compose.onNodeWithTag("community-post").assertIsDisplayed()
                    capture("feed-post-top-$w", output, record, goldens)
                    compose.onNodeWithContentDescription("Like post").performScrollTo()
                    hideFeedHeader()
                    compose.onNodeWithTag("community-post").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Like post").assertIsDisplayed()
                    capture("feed-post-$w", output, record, goldens)
                    resetFeedScroll()
                }
                if (scene == HomeScenario.PaginationLoading || scene == HomeScenario.PaginationError) resetFeedScroll()
                if (scene == HomeScenario.Empty && w == 390) {
                    val bounds = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
                    assertEquals(16f, bounds.left, 1f)
                    assertEquals(267f, bounds.top, 1f)
                    assertEquals(358f, bounds.width, 1f)
                    assertEquals(120f, bounds.height, 1f)
                }
            }
        }
        compose.runOnIdle { width = 390; height = 844; state = HomeUiState(query = "nature", overlay = HomeOverlay.Search) }
        compose.onNodeWithTag("search-input").performImeAction()
        capture("search-390", output, record, goldens)
        compose.runOnIdle { state = HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("bunny")) }
        capture("menu-390", output, record, goldens)
        compose.runOnIdle { width = 320; state = HomeUiState(); fontScale = 2f }
        capture("empty-320-font2", output, record, emptySet())
        val message = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
        assertTrue(message.width <= 320f)
        compose.onNodeWithText("Try searching to get started").assertIsDisplayed()
    }

    private fun resetFeedScroll() {
        if (compose.onAllNodesWithTag("home-feed").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("home-feed").performScrollToIndex(0)
        }
    }

    private fun hideFeedHeader() {
        compose.onNodeWithTag("home-feed").performTouchInput { swipe(center, center.copy(y = center.y - 48f), 200) }
    }

    @Test fun verifiedHomeStatesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(HomeUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        val names = setOf("empty-390", "feed-390", "empty-1280", "feed-1280")
        val record = InstrumentationRegistry.getArguments().getString("recordHomeGoldens") == "true"
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            for (scene in listOf(HomeScenario.Empty, HomeScenario.Feed)) {
                compose.runOnIdle {
                    width = w; height = h
                    state = HomeUiState(content = scene.initialContent(), signedInPreview = scene == HomeScenario.Feed)
                }
                resetFeedScroll()
                capture("${scene.argument}-$w", output, record, names)
            }
        }
    }

    @Test fun overlayAnchorsMatchMeasuredGeometry() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var state by mutableStateOf(HomeUiState(overlay = HomeOverlay.Search))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val input = compose.onNodeWithTag("search-input").fetchSemanticsNode().boundsInRoot
        assertEquals(60f, input.left, 0.5f)
        assertEquals(238f, input.width, 0.5f)
        val search = compose.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        assertEquals(298f, search.left, 0.5f)
        assertEquals(36f, search.width, 0.5f)
        assertEquals(32f, search.height, 0.5f)
        val voice = compose.onNodeWithContentDescription("Search with your voice").fetchSemanticsNode().boundsInRoot
        assertEquals(342f, voice.left, 0.5f)
        assertEquals(40f, voice.width, 0.5f)
        compose.runOnIdle { state = state.copy(query = "nature") }
        compose.onNodeWithContentDescription("Search with your voice").assertDoesNotExist()
        val clear = compose.onNodeWithContentDescription("Clear search").fetchSemanticsNode().boundsInRoot
        assertEquals(296f, clear.left, 0.5f)
        assertEquals(50f, clear.width, 0.5f)
        assertEquals(16f, clear.height, 0.5f)
        assertEquals(48f, compose.onNodeWithTag("suggestion-0").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertEquals(86f, compose.onNodeWithTag("suggestion-1").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("nature documentary").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(14.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(FontWeight.Medium, layouts.single().layoutInput.text.spanStyles.last().item.fontWeight)
        compose.onNodeWithTag("search-input").performImeAction()
        compose.runOnIdle { state = HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("bunny")) }
        val phone = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, phone.left, 0.5f)
        assertEquals(390f, phone.width, 0.5f)
        assertEquals(13f + FeedMenuKind.Video.actions.size * 48f, phone.height, 0.5f)
        assertEquals(820f, phone.bottom, 0.5f)
        assertEquals(64f, compose.onNodeWithText("Save to Watch later", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.runOnIdle { width = 1280; height = 800 }
        val wide = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(440f, wide.left, 0.5f)
        assertEquals(400f, wide.width, 0.5f)
        assertEquals(776f, wide.bottom, 0.5f)
        compose.runOnIdle { width = 500; height = 844 }
        val portrait = compose.onNodeWithTag("video-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, portrait.left, 0.5f)
        assertEquals(500f, portrait.width, 0.5f)
    }

    @Test fun overlaysMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(HomeUiState(overlay = HomeOverlay.Search))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        val names = setOf("search-empty-390", "search-390", "menu-390", "search-empty-1280", "search-1280", "menu-1280")
        val record = InstrumentationRegistry.getArguments().getString("recordHomeGoldens") == "true"
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)) {
            for (query in listOf("", "nature")) {
                compose.runOnIdle { width = w; height = h; state = HomeUiState(query = query, overlay = HomeOverlay.Search) }
                compose.onNodeWithTag("search-input").performImeAction()
                capture("${if (query.isEmpty()) "search-empty" else "search"}-$w", output, record, names)
            }
            compose.runOnIdle { state = HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("bunny")) }
            resetFeedScroll()
            capture("menu-$w", output, record, names)
        }
        compose.runOnIdle { width = 320; height = 240; fontScale = 2f }
        compose.onNodeWithText("Don't recommend channel").performScrollTo().assertIsDisplayed()
        capture("menu-320-landscape-font2", output, true, emptySet())
        compose.runOnIdle { state = HomeUiState(query = "nature", overlay = HomeOverlay.Search) }
        compose.onNodeWithTag("search-input").performImeAction()
        compose.onNodeWithTag("suggestion-3").performScrollTo().assertIsDisplayed()
        capture("search-320-landscape-font2", output, true, emptySet())
    }

    @Test fun enlargedTextStaysInsideNavigation() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(320.dp, 844.dp)) {
                    HomeScreen(HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true), {})
                }
            }
        }
        val strip = compose.onNodeWithTag("home-chips").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText("All", useUnmergedTree = true)
        val chipBounds = chip.fetchSemanticsNode().boundsInRoot
        val chipLayouts = mutableListOf<TextLayoutResult>()
        chip.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(chipLayouts) }
        assertEquals(1, chipLayouts.single().lineCount)
        val chipLayout = chipLayouts.single()
        assertFalse(chipLayout.isLineEllipsized(0))
        assertTrue("Chip text must fit within one raster pixel", chipLayout.getLineRight(0) <= chipBounds.width + 1f)
        assertTrue(chipLayout.multiParagraph.height <= chipBounds.height + 1f)
        assertTrue(chipBounds.top >= strip.top && chipBounds.bottom <= strip.bottom)
        assertTrue(strip.height > 48f)
        assertEquals(28f, compose.onNodeWithText("Litube", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, 0.5f)
        HomeDestination.entries.forEach { destination ->
            val tab = compose.onNodeWithTag("nav-${destination.name}").fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(destination.label, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("${destination.label} label lines", 1, layouts.single().lineCount)
            val bounds = label.fetchSemanticsNode().boundsInRoot
            assertTrue("${destination.label} must fit inside its tab", bounds.top >= tab.top && bounds.bottom <= tab.bottom)
        }
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        capture("feed-320-font2", output, true, emptySet())
    }

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("home-screen").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("home/goldens/$name.png").use(BitmapFactory::decodeStream)
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
