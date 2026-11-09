package com.hhst.youtubelite.post

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.data.media.ContentSource
import com.hhst.youtubelite.data.media.MediaClient
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Production screens and requests, with all HTTP intercepted on the device. */
class ProductionPostActionsAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun ratingsPollAndCommentSubmissionReachTheAuthorizedTransport() {
        val koin = GlobalContext.get()
        val previous = koin.get<ContentRepositories>()
        val fixtures = ContentSource.fixtures
        val native = RequestContext(accountId = "account", accessToken = "fixture", generation = 7)
        val web = native.copy(accessToken = null, webSession = WebSession("SAPISID=fixture; SID=fixture"))
        val writes = AtomicInteger()
        var rating = "INDIFFERENT"
        var selected = false
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("SAPISID=fixture; SID=fixture", request.header("Cookie"))
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val response = when {
                request.url.encodedPath.startsWith("/post/") -> post(rating, selected)
                request.url.encodedPath.endsWith("/browse") -> comments
                else -> {
                    writes.incrementAndGet()
                    when {
                        request.url.encodedPath.endsWith("/create_comment") -> {
                            assertTrue(body.contains("\"createCommentParams\":\"CREATE\""))
                            """{"status":"STATUS_SUCCEEDED","commentRenderer":{"commentId":"created","contentText":{"simpleText":"Posted from the editor"}}}"""
                        }
                        else -> {
                            when {
                                body.contains("\"LIKE\"") -> rating = "LIKE"
                                body.contains("\"UNLIKE\"") -> rating = "INDIFFERENT"
                                body.contains("\"DISLIKE\"") -> rating = "DISLIKE"
                                body.contains("\"SELECT\"") -> selected = true
                                body.contains("\"DESELECT\"") -> selected = false
                            }
                            """{"status":"STATUS_SUCCEEDED"}"""
                        }
                    }
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody()).build()
        }.build()
        val preferences = PreferenceStore.of(ApplicationProvider.getApplicationContext(), "post-actions-test")
        val replacement = ContentRepositories(MediaClient(transport) { native }, preferences, postWebContext = { web })
        var scenario: ActivityScenario<PostDetailActivity>? = null
        try {
            ContentSource.fixtures = false
            koin.declare(replacement, allowOverride = true)
            scenario = ActivityScenario.launch(PostDetailActivity.intent(ApplicationProvider.getApplicationContext(), "UCauthor", "Ugkx_actions"))
            waitFor("post-detail-like")
            compose.onNodeWithTag("post-detail-like").performClick()
            waitForWrites(writes, 1)
            compose.onNodeWithTag("post-detail-like").assertIsSelected()
            compose.onNodeWithTag("post-detail-like").performClick()
            waitForWrites(writes, 2)
            compose.onNodeWithTag("post-detail-like").assertIsNotSelected()
            compose.onNodeWithTag("post-detail-dislike").performClick()
            waitForWrites(writes, 3)
            compose.onNodeWithTag("post-detail-dislike").assertIsSelected()
            compose.onNodeWithTag("post-poll-option-0").performScrollTo().performClick()
            waitForWrites(writes, 4)
            compose.onNodeWithTag("post-poll-option-0").assertIsSelected()
            compose.onNodeWithTag("post-poll-option-0").performClick()
            waitForWrites(writes, 5)
            compose.onNodeWithTag("post-poll-option-0").assertIsNotSelected()
            compose.onNodeWithTag("post-detail-comments").performScrollTo().performClick()
            waitFor("comments-input")
            compose.onNodeWithTag("comments-input").performClick()
            waitFor("comment-composer-input")
            compose.onNodeWithTag("comment-composer-input").performTextInput("Posted from the editor")
            compose.onNodeWithTag("comment-composer-submit").performClick()
            compose.waitUntil(15_000) { writes.get() == 6 }
            waitFor("comment-created-body")
            compose.onNodeWithText("Posted from the editor", useUnmergedTree = true).assertIsDisplayed()
            scenario.onActivity { assertEquals("", it.postCommentsViewModel.uiState.value.draft) }
            assertEquals(6, writes.get())
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("comments-panel").fetchSemanticsNodes().isEmpty() }
        } finally {
            scenario?.close()
            koin.declare(previous, allowOverride = true)
            ContentSource.fixtures = fixtures
            preferences.clearAll()
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForWrites(writes: AtomicInteger, count: Int) {
        compose.waitUntil(15_000) { writes.get() >= count }
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("post-detail-like").fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag("post-detail-like").assertIsEnabled() }.isSuccess
        }
    }

    private fun post(rating: String, selected: Boolean) = """<script>var ytInitialData = {
      "backstagePostRenderer":{"postId":"Ugkx_actions","authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},
        "authorText":{"simpleText":"Author"},"contentText":{"simpleText":"Interactive post"},"voteStatus":"$rating",
        "actionButtons":{"commentActionButtonsRenderer":{
          "likeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"LIKE"}},"toggledServiceEndpoint":{"performCommentActionEndpoint":{"action":"UNLIKE"}}}},
          "dislikeButton":{"toggleButtonRenderer":{"defaultServiceEndpoint":{"performCommentActionEndpoint":{"action":"DISLIKE"}}}}}},
        "backstageAttachment":{"pollRenderer":{"choices":[{"text":{"simpleText":"First"},"selected":$selected,
          "selectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"SELECT"}},"deselectServiceEndpoint":{"feedbackEndpoint":{"feedbackToken":"DESELECT"}}},
          {"text":{"simpleText":"Second"}}]}}},
      "itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"comments"}}}}]}};</script>"""

    private val comments = """{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":{"createCommentEndpoint":{"createCommentParams":"CREATE"}}}}},
      "commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"thread","contentText":{"simpleText":"Original comment"}}}}}"""
}
