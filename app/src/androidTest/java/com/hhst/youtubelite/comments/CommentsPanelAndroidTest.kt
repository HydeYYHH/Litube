package com.hhst.youtubelite.comments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Panel geometry and interactions against the sampled mobile sheet and desktop rail. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class CommentsPanelAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var panelContent by mutableStateOf<CommentsContent>(ready())
    private var panelSort by mutableStateOf(CommentSort.Top)
    private var panelExpanded by mutableStateOf<Set<String>>(emptySet())
    private var panelOverlay by mutableStateOf<CommentsOverlay>(CommentsOverlay.None)
    private var panelCount by mutableStateOf("5.2K")
    private var viewportWidth by mutableStateOf(390)
    private var viewportHeight by mutableStateOf(844)
    private var fontScale by mutableStateOf(1f)
    private var panelLocale by mutableStateOf("en")

    private val actions = mutableListOf<CommentAction>()
    private var closes = 0

    private fun ready(threads: Int = 4, cursor: String? = "4") = CommentsContent.Ready(
        threads = FixtureCommentsRepository.sampleThreads(CommentSort.Top).take(threads),
        nextCursor = cursor,
    )

    @Composable
    private fun Viewport(width: Int, height: Int, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val original = LocalConfiguration.current
        val configuration = remember(original, panelLocale) {
            android.content.res.Configuration(original).apply {
                setLocale(java.util.Locale.forLanguageTag(panelLocale))
            }
        }
        val localized = remember(base, configuration) {
            base.createConfigurationContext(configuration)
        }
        val direction = if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
            androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalContext provides localized,
            LocalConfiguration provides configuration, LocalLayoutDirection provides direction) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { content() }
            }
        }
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun render() {
        compose.setContent {
            Viewport(viewportWidth, viewportHeight) {
                CommentsPanel(
                    state = CommentsUiState(
                        videoId = "bunny",
                        sort = panelSort,
                        content = panelContent,
                        expanded = panelExpanded,
                        overlay = panelOverlay,
                    ),
                    commentCount = panelCount,
                    onAction = { action ->
                        actions += action
                        when (action) {
                            CommentAction.OpenSortMenu -> panelOverlay = CommentsOverlay.SortMenu
                            CommentAction.CloseMenu -> panelOverlay = CommentsOverlay.None
                            is CommentAction.SelectSort -> {
                                panelOverlay = CommentsOverlay.None
                                panelSort = action.sort
                                panelContent = CommentsContent.Ready(
                                    FixtureCommentsRepository.sampleThreads(action.sort).take(4),
                                    null,
                                )
                            }
                            is CommentAction.OpenThreadMenu -> panelOverlay = CommentsOverlay.ThreadMenu(action.commentId)
                            is CommentAction.OpenReplyMenu -> panelOverlay = CommentsOverlay.ReplyMenu(action.commentId, action.replyId)
                            is CommentAction.MenuAction -> panelOverlay = CommentsOverlay.None
                            is CommentAction.ToggleReplies -> panelExpanded =
                                if (action.commentId in panelExpanded) panelExpanded - action.commentId
                                else panelExpanded + action.commentId
                            else -> Unit
                        }
                    },
                    onClose = { closes++ },
                )
            }
        }
    }

    private fun set(content: CommentsContent) {
        compose.runOnIdle { panelContent = content }
    }

    @Test fun compactSheetMatchesSampledAnchors() {
        render()
        val panel = bounds("comments-panel")
        assertEquals(390f, panel.width, 0.5f)
        // Sheet height = viewport 844 − sheet top 219 (rounded 16:9 player; the native watch page has no top bar).
        assertEquals(625f, panel.height, 0.5f)
        assertEquals(bounds("comments-scrim").bottom, panel.bottom, 0.5f)

        val handle = bounds("comments-handle-bar")
        assertEquals(40f, handle.width, 0.5f)
        assertEquals(4f, handle.height, 0.5f)
        assertEquals(175f, handle.left - panel.left, 0.5f)
        assertEquals(8f, handle.top - panel.top, 0.5f)

        val header = bounds("comments-header")
        assertEquals(12f, header.top - panel.top, 0.5f)
        assertEquals(65f, header.height, 0.5f)
        compose.onNodeWithTag("comments-title").assertDoesNotExist()
        assertEquals(12f, bounds("comments-count").left - panel.left, 0.5f)
        val count = textLayout("comments-count")
        assertEquals(14f, count.layoutInput.style.fontSize.value, 0.01f)
        assertEquals(20f, count.layoutInput.style.lineHeight.value, 0.01f)

        val close = bounds("comments-close")
        assertEquals(382f, close.right - panel.left, 0.5f)
        assertEquals(48f, close.width, 0.5f)
        assertEquals(48f, close.height, 0.5f)

        val input = bounds("comments-input")
        assertEquals(77f + 16f, input.top - panel.top, 0.5f)
        assertEquals(68f, input.left - panel.left, 0.5f)
        assertEquals(378f, input.right - panel.left, 0.5f)
        assertEquals(56f, input.height, 0.5f)
    }

    @Test fun threadAnchorsMatchSampledGeometry() {
        render()
        val panel = bounds("comments-panel")
        val thread = bounds("comment-c1")
        assertEquals(77f + 89f, thread.top - panel.top, 0.5f)

        val author = bounds("comment-c1-author")
        assertEquals(68f, author.left - panel.left, 0.5f)
        assertEquals(thread.top + 16f + 4f, author.top, 0.5f)
        assertEquals(12f, textLayout("comment-c1-author").layoutInput.style.fontSize.value, 0.01f)

        val body = bounds("comment-c1-body")
        assertEquals(68f, body.left - panel.left, 0.5f)
        // 5px design gap; Compose's line box for 12/14 text rounds 1px taller than the CSS box.
        assertEquals(5f, body.top - author.bottom, 1.5f)
        assertEquals(14f, textLayout("comment-c1-body").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(17.5f, textLayout("comment-c1-body").layoutInput.style.lineHeight.value, 0.01f)

        val like = bounds("comment-c1-like")
        assertEquals(62f, like.left - panel.left, 0.5f)
        assertEquals(40f, like.height, 0.5f)
        // 3px after the body; the 16px glyph is centred in a 24px box inside this 40px row.
        assertEquals(body.bottom + 3f, like.top, 0.5f)

        val more = bounds("comment-c1-more")
        assertEquals(370f, more.right - panel.left, 0.5f)
        assertEquals(40f, more.width, 0.5f)

        val toggle = bounds("comment-c1-reply-toggle")
        assertEquals(62f, toggle.left - panel.left, 0.5f)
        assertEquals(378f, toggle.right - panel.left, 0.5f)
        assertEquals(40f, toggle.height, 0.5f)
    }

    @Test fun dockedRailMatchesTheSampledWidePanel() {
        viewportWidth = 1280
        viewportHeight = 800
        render()
        val panel = bounds("comments-panel")
        assertEquals(500f, panel.width, 0.5f)
        assertEquals(800f, panel.height, 0.5f)
        compose.onNodeWithTag("comments-title").assertDoesNotExist()
        assertEquals(14f, textLayout("comments-count").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(20f, textLayout("comments-count").layoutInput.style.lineHeight.value, 0.01f)
        assertEquals(12f, bounds("comments-count").left - panel.left, 0.5f)
        assertEquals(65f, bounds("comments-header").height, 0.5f)
        assertEquals(492f, bounds("comments-close").right - panel.left, 0.5f)
        assertEquals(68f, bounds("comments-input").left - panel.left, 0.5f)
        assertEquals(488f, bounds("comments-input").right - panel.left, 0.5f)
        assertEquals(480f, bounds("comment-c1-more").right - panel.left, 0.5f)
        assertEquals(bounds("comments-rail").right, panel.right, 0.5f)
        assertEquals(0f, panel.top - bounds("comments-rail").top, 0.5f)
    }

    @Test fun expandedRepliesRenderUnderTheCommentAndCollapseAgain() {
        render()
        assertFalse(hasTag("comment-c1-replies"))
        compose.onNodeWithTag("comment-c1-reply-toggle").performClick()
        compose.waitForIdle()
        assertEquals(listOf<CommentAction>(CommentAction.ToggleReplies("c1")), actions)
        assertTrue(hasTag("comment-c1-replies"))
        assertEquals(62f, bounds("comment-c1-replies").left, 0.5f)
        assertEquals(370f, bounds("comment-c1-replies").right, 0.5f)
        assertEquals(62f, bounds("reply-c1r1").left, 0.5f)
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-c1-reply-toggle"))
        assertEquals(
            "Hide replies",
            compose.onNodeWithTag("comment-c1-reply-toggle").fetchSemanticsNode()
                .config[SemanticsProperties.Text].first().text,
        )

        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-c1-reply-toggle"))
        compose.onNodeWithTag("comment-c1-reply-toggle").performClick()
        compose.waitForIdle()
        assertFalse(hasTag("comment-c1-replies"))
        compose.onNodeWithTag("comment-c1-reply-toggle").assertIsDisplayed()
        assertEquals(
            "Show more replies",
            compose.onNodeWithTag("comment-c1-reply-toggle").fetchSemanticsNode()
                .config[SemanticsProperties.Text].first().text,
        )
    }

    @Test fun longReplyThreadsComposeOnlyVisibleRepliesAndPaginateAtTheRealListEnd() {
        val thread = FixtureCommentsRepository.sampleThreads(CommentSort.Top).first()
        val reply = thread.replies.first()
        panelContent = CommentsContent.Ready(listOf(thread.copy(
            replies = List(100) { reply.copy(id = "long-reply-$it") },
        )), nextCursor = "next")
        panelExpanded = setOf(thread.id)
        render()
        compose.onNodeWithTag("reply-long-reply-0").assertExists()
        compose.onNodeWithTag("reply-long-reply-99").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Reply rows must not trigger thread pagination early", actions.isEmpty()) }
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("reply-long-reply-99"))
        compose.onNodeWithTag("reply-long-reply-99").assertExists()
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-${thread.id}-reply-toggle"))
        compose.runOnIdle { assertEquals(listOf<CommentAction>(CommentAction.LoadMore), actions) }
        compose.onNodeWithTag("comment-${thread.id}-reply-toggle").performClick()
        compose.onNodeWithTag("comment-${thread.id}-reply-toggle").assertIsDisplayed().assertTextEquals("Show more replies")
    }

    @Test fun sortingStartsAtTheFirstCommentInsteadOfKeepingTheOldAnchor() {
        val threads = (1..40).map { index ->
            FixtureCommentsRepository.sampleThreads(CommentSort.Top).first().copy(
                id = "sort-$index", replies = emptyList(), replyCount = 0, replyKey = null,
            )
        }
        panelContent = CommentsContent.Ready(threads)
        render()
        compose.onNodeWithTag("comments-list").performScrollToIndex(20)
        compose.onNodeWithTag("comments-simplebox").assertDoesNotExist()
        compose.runOnIdle {
            panelSort = CommentSort.Newest
            panelContent = CommentsContent.Ready(threads.reversed())
        }
        compose.onNodeWithTag("comments-simplebox").assertIsDisplayed()
        compose.onNodeWithTag("comment-sort-40").assertIsDisplayed()
    }

    @Test fun sortMenuSwitchesTheVisibleOrderAndCloses() {
        render()
        assertEquals(385f, bounds("comment-c1").top, 0.5f)
        compose.onNodeWithTag("comments-sort").assertTextEquals("Top")
        compose.onNodeWithTag("comments-sort").performClick()
        assertTrue(hasTag("comments-sort-menu"))
        compose.onNodeWithTag("comments-sort-newest").performClick()
        compose.waitForIdle()
        assertFalse(hasTag("comments-sort-menu"))
        compose.onNodeWithTag("comments-sort").assertTextEquals("Newest")
        assertEquals(CommentSort.Newest, panelSort)
        val newestFirst = FixtureCommentsRepository.sampleThreads(CommentSort.Newest).first().id
        assertEquals(385f, bounds("comment-$newestFirst").top, 0.5f)
        assertTrue(actions.contains(CommentAction.SelectSort(CommentSort.Newest)))
    }

    @Test fun sortMenuDismissScrimClosesItWithoutClosingThePanel() {
        render()
        compose.onNodeWithTag("comments-sort").performClick()
        assertTrue(hasTag("comments-sort-menu"))
        compose.onNodeWithTag("comments-sort-dismiss").performClick()
        compose.waitForIdle()
        assertFalse(hasTag("comments-sort-menu"))
        assertEquals(0, closes)
        compose.onNodeWithTag("comments-panel").assertExists()
    }

    @Test fun scrimTapAsksTheHostToClose() {
        render()
        compose.onNodeWithTag("comments-scrim").performTouchInput { click(Offset(width / 2f, 100f)) }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test fun loadingEmptyDisabledAndErrorStatesRender() {
        render()
        set(CommentsContent.Loading)
        assertTrue(hasTag("comments-loading"))
        assertTrue(hasTag("comments-input"))

        set(CommentsContent.Empty)
        assertTrue(hasTag("comments-message"))
        assertTrue(hasTag("comments-input"))

        set(CommentsContent.Disabled)
        compose.onNodeWithTag("comments-message").assertTextContains("turned off", substring = true)
        assertFalse(hasTag("comments-input"))

        set(CommentsContent.Failed)
        assertTrue(hasTag("comments-error"))
        assertTrue(hasTag("comments-retry"))
    }

    @Test fun retryAndPaginationActionsAreReported() {
        render()
        set(CommentsContent.Failed)
        compose.onNodeWithTag("comments-retry").performClick()
        assertEquals(listOf<CommentAction>(CommentAction.Retry), actions)

        actions.clear()
        set(ready())
        compose.onNodeWithTag("comments-list").performScrollToIndex(4)
        compose.waitForIdle()
        assertEquals(listOf<CommentAction>(CommentAction.LoadMore), actions)
    }

    @Test fun paginationFooterShowsProgressAndFailure() {
        render()
        set(ready().copy(loadingMore = true))
        compose.onNodeWithTag("comments-list").performScrollToIndex(4)
        compose.waitForIdle()
        assertTrue(hasTag("comments-footer-loading"))

        set(ready().copy(loadingMore = false, loadMoreFailed = true))
        compose.onNodeWithTag("comments-list").performScrollToIndex(4)
        compose.waitForIdle()
        assertTrue(hasTag("comments-footer-error"))
        assertTrue(hasTag("comments-footer-retry"))
    }

    @Test fun commentActionsEmitTypedActionsWithoutChangingContent() {
        render()
        compose.onNodeWithTag("comment-c1-like").performClick()
        compose.onNodeWithTag("comment-c1-dislike").performClick()
        compose.onNodeWithTag("comments-input").performClick()
        compose.onNodeWithTag("comment-c1-more").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                CommentAction.LikeThread("c1"),
                CommentAction.DislikeThread("c1"),
                CommentAction.Compose,
                CommentAction.OpenThreadMenu("c1"),
            ),
            actions,
        )
        assertEquals(ready(), panelContent)
    }

    @Test fun threadMenuOffersThePublicRowsAndKeepsThePanelState() {
        panelExpanded = setOf("c1")
        render()
        compose.onNodeWithTag("comment-c1-more").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(CommentsOverlay.ThreadMenu("c1"), panelOverlay)
        compose.onNodeWithTag("comments-menu").assertIsDisplayed()
        compose.onNodeWithTag("comment-menu-reply").assertIsDisplayed()
        compose.onNodeWithTag("comment-menu-report").assertIsDisplayed()
        assertEquals("Reply", compose.onNodeWithTag("comment-menu-reply").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text)
        assertEquals("Report", compose.onNodeWithTag("comment-menu-report").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().text)

        compose.onNodeWithTag("comment-menu-report").performClick()
        compose.waitForIdle()
        assertEquals(CommentsOverlay.None, panelOverlay)
        assertFalse(hasTag("comments-menu"))
        assertEquals(listOf(CommentAction.OpenThreadMenu("c1"), CommentAction.MenuAction(CommentMenuAction.Report)), actions)
        assertEquals(ready(), panelContent)
        assertEquals(setOf("c1"), panelExpanded)
        compose.onNodeWithTag("comments-panel").assertExists()
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()
    }

    @Test fun replyMenuCarriesTheReplyIdentity() {
        panelExpanded = setOf("c1")
        render()
        compose.onNodeWithTag("reply-c1r1-more").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(CommentsOverlay.ReplyMenu("c1", "c1r1"), panelOverlay)
        compose.onNodeWithTag("comment-menu-reply").performClick()
        compose.waitForIdle()
        assertEquals(listOf(CommentAction.OpenReplyMenu("c1", "c1r1"), CommentAction.MenuAction(CommentMenuAction.Reply)), actions)
        assertFalse(hasTag("comments-menu"))
    }

    @Test fun menuDismissalKeepsTheListScrollPositionAndExpandedReplies() {
        panelExpanded = setOf("c1")
        render()
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("reply-c1r1-more"))
        compose.onNodeWithTag("reply-c1r1-more").performScrollTo().assertIsDisplayed()
        val before = bounds("reply-c1r1-more")
        compose.onNodeWithTag("reply-c1r1-more").performClick()
        compose.waitForIdle()
        assertTrue(hasTag("comments-menu"))
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
        assertFalse(hasTag("comments-menu"))
        assertEquals(before, bounds("reply-c1r1-more"))
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()
        assertEquals(0, closes)
    }

    @Test fun shortWindowAtLargeFontKeepsBothMenuRowsReachable() {
        viewportWidth = 320
        viewportHeight = 240
        fontScale = 2f
        panelOverlay = CommentsOverlay.ThreadMenu("c1")
        render()
        compose.onNodeWithTag("comments-menu").assertIsDisplayed()
        compose.onNodeWithTag("comment-menu-reply").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("comment-menu-report").performScrollTo().assertIsDisplayed()
        val sheet = bounds("comments-menu")
        val panel = bounds("comments-panel")
        assertTrue(sheet.right <= panel.right)
        assertTrue(sheet.bottom <= panel.bottom)
        assertTrue(sheet.top >= panel.top)
        compose.onNodeWithTag("comment-menu-report").performClick()
        compose.onNodeWithTag("comments-panel").assertExists()
    }

    @Test fun largeFontScaleKeepsTheCloseControlReachable() {
        fontScale = 2f
        render()
        val close = bounds("comments-close")
        assertTrue(close.right <= 390f)
        assertTrue(close.height >= 48f)
        compose.onNodeWithTag("comments-close").assertExists()
    }

    @Test fun translatedHeadersAndSortChoicesRemainReachableAtLargeFont() {
        fontScale = 2f
        render()
        for (locale in listOf("zh", "zh-Hant", "ja", "ko", "de", "fr", "es", "pt", "ru", "hi", "ar")) {
            compose.runOnIdle {
                panelLocale = locale
                panelSort = CommentSort.Top
            }
            compose.waitForIdle()
            val panel = bounds("comments-panel")
            val close = bounds("comments-close")
            assertTrue("Close action outside the panel in $locale", close.left >= panel.left && close.right <= panel.right)
            compose.onNodeWithTag("comments-close").assertIsDisplayed()
            val count = textLayout("comments-count")
            assertFalse("Clipped count in $locale: ${count.layoutInput.text.text}", count.hasVisualOverflow)
            val sort = bounds("comments-sort")
            assertTrue("Sort action outside the panel in $locale", sort.left >= panel.left && sort.right <= panel.right)
            compose.onNodeWithTag("comments-sort").assertIsDisplayed()
            compose.onNodeWithTag("comments-sort").performClick()
            compose.onNodeWithTag("comments-sort-newest").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("comments-close").assertIsDisplayed()
        }
    }

    @Test fun shortWindowKeepsRetryAndBothSortChoicesReachableAtLargeFont() {
        viewportWidth = 320
        viewportHeight = 240
        fontScale = 2f
        render()
        set(CommentsContent.Failed)
        compose.onNodeWithTag("comments-retry").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(actions.contains(CommentAction.Retry))
        compose.onNodeWithTag("comments-close").assertIsDisplayed()
        compose.onNodeWithTag("comments-sort").performClick()
        compose.onNodeWithTag("comments-sort-newest").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("comments-sort").assertTextEquals("Newest")
        val count = textLayout("comments-count")
        assertFalse(count.isLineEllipsized(0))
        assertEquals(panelCount.length, count.getLineEnd(0))
        assertTrue(count.multiParagraph.intrinsics.maxIntrinsicWidth <= count.size.width)
    }

    @Test fun compactRailKeepsReplyActionsInsideItsBounds() {
        viewportWidth = 931
        viewportHeight = 800
        fontScale = 2f
        panelExpanded = setOf("c1")
        render()
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("reply-c1r1-more"))
        compose.onNodeWithTag("reply-c1r1-more").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(bounds("reply-c1r1-more").right <= bounds("comments-panel").right)
        assertTrue(actions.contains(CommentAction.OpenReplyMenu("c1", "c1r1")))
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("comments-close").assertIsDisplayed()
        compose.onNodeWithTag("comments-sort").performClick()
        compose.onNodeWithTag("comments-sort-newest").performScrollTo().performClick()
        compose.onNodeWithTag("comments-close").assertIsDisplayed()
    }

    @Test fun paginationFailureDoesNotAutomaticallyRetryWhenScrolling() {
        render()
        compose.onNodeWithTag("comments-list").performScrollToIndex(4)
        set(ready().copy(loadingMore = true))
        set(ready().copy(loadMoreFailed = true))
        actions.clear()
        compose.onNodeWithTag("comments-list").performScrollToIndex(0)
        compose.onNodeWithTag("comments-list").performScrollToIndex(4)
        assertFalse(actions.contains(CommentAction.LoadMore))
        compose.onNodeWithTag("comments-footer-retry").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf<CommentAction>(CommentAction.LoadMore), actions)
    }

    @Test fun replyButtonsEmitReplyActionsInsteadOfOpeningMenus() {
        render()
        compose.onNodeWithTag("comment-c1-reply").performClick()
        assertEquals(listOf<CommentAction>(CommentAction.Reply("c1")), actions)
    }
}
